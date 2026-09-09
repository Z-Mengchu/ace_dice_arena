package com.acedicearena;

import com.acedicearena.domain.GameStateRecord;
import com.acedicearena.domain.MatchGuess;
import com.acedicearena.domain.UserAccount;
import com.acedicearena.repository.GameStateRepository;
import com.acedicearena.repository.MatchGuessRepository;
import com.acedicearena.repository.UserAccountRepository;
import com.acedicearena.service.ParallelTournamentService;
import com.acedicearena.service.PlayerActionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * guess 拆表（match_guess）的 H2 集成测试：独立内存库隔离其他测试上下文的 500ms 定时扫描。
 * 覆盖双 side 齐 5 提前 reveal、超时强制 reveal 合并表数据、pre-guess 改投覆盖与开窗合并、
 * retract 表/JSON 双边幂等、密封视图只注入状态布尔不泄漏内容、JSON 兜底双读兼容、重赛清理旧表行。
 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:match-guess-test;DB_CLOSE_DELAY=-1"})
class MatchGuessServiceTest {
    @Autowired ParallelTournamentService tournament;
    @Autowired PlayerActionService playerActions;
    @Autowired MatchGuessRepository matchGuesses;
    @Autowired GameStateRepository states;
    @Autowired UserAccountRepository users;
    @Autowired ObjectMapper mapper;
    @Autowired com.acedicearena.repository.PlayerRollRepository playerRolls;
    @Autowired com.acedicearena.repository.PlayerBlindBoxRepository blindBoxes;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;

    @BeforeEach
    void cleanUp() {
        matchGuesses.deleteAll();
        playerRolls.deleteAll();
        blindBoxes.deleteAll();
        states.deleteAll();
        users.deleteAll();
    }

    @Test
    void bothSidesCompleteTriggersEarlyRevealFromTableRows() throws Exception {
        long now = System.currentTimeMillis();
        List<UserAccount> t1 = squad("ga", "t1");
        List<UserAccount> t2 = squad("gb", "t2");
        // 开窗已超过最短时长（5s），交齐即可提前揭晓；截止时间在将来避免扫描抢先强制揭晓
        saveState(battleState(t1, t2, now - 10_000L, now + 600_000L));
        List<String> enemySquadB = squadIds(t2, 0);
        List<String> enemySquadA = squadIds(t1, 0);

        for (int i = 0; i < 5; i++)
            playerActions.submit(t1.get(i).getUsername(), "round-guess", enemySquadB);
        for (int i = 0; i < 4; i++)
            playerActions.submit(t2.get(i).getUsername(), "round-guess", enemySquadA);
        JsonNode before = readState();
        assertThat(matchOf(before).path("roundPhase").asText()).as("B 方未交齐不提前揭晓").isEqualTo("GUESS");

        playerActions.submit(t2.get(4).getUsername(), "round-guess", enemySquadA);

        JsonNode match = matchOf(readState());
        assertThat(match.path("roundPhase").asText()).isEqualTo("REVEAL");
        assertThat(match.path("rounds")).hasSize(1);
        assertThat(match.path("rounds").get(0).path("guessHitsA").asInt()).isEqualTo(25);
        assertThat(match.path("rounds").get(0).path("guessHitsB").asInt()).isEqualTo(25);
        // 揭晓后 guesses 立即清空（原行为保持），内容只留在 match_guess 表
        assertThat(match.path("guesses").path("A")).isEmpty();
        assertThat(match.path("guesses").path("B")).isEmpty();
        assertThat(matchGuesses.findByGameDayAndMatchIdAndGuessTypeAndRoundNo(
                1, "g1", MatchGuess.TYPE_ROUND, 1)).hasSize(10);
    }

    @Test
    void forcedRevealAtDeadlineMergesTableRowsIntoSettlement() throws Exception {
        long now = System.currentTimeMillis();
        List<UserAccount> t1 = squad("fa", "t1");
        List<UserAccount> t2 = squad("fb", "t2");
        saveState(battleState(t1, t2, now - 10_000L, now + 600_000L));
        playerActions.submit(t1.get(0).getUsername(), "round-guess", squadIds(t2, 0));
        playerActions.submit(t1.get(1).getUsername(), "round-guess", squadIds(t2, 0));
        playerActions.submit(t2.get(0).getUsername(), "round-guess", squadIds(t1, 0));

        // 到点：把截止时间改到过去，由同一套超时逻辑强制揭晓
        ObjectNode root = (ObjectNode) readState();
        ((ObjectNode) root.path("matches").path("g1")).put("guessDeadlineAt", now - 1);
        saveState(root);
        tournament.advanceDueResults();

        JsonNode match = matchOf(readState());
        assertThat(match.path("roundPhase").asText()).isEqualTo("REVEAL");
        assertThat(match.path("rounds")).hasSize(1);
        // 表内 2 份 A 方猜阵（各 5 中）与 1 份 B 方猜阵并入结算
        assertThat(match.path("rounds").get(0).path("guessHitsA").asInt()).isEqualTo(10);
        assertThat(match.path("rounds").get(0).path("guessHitsB").asInt()).isEqualTo(5);
    }

    @Test
    void preGuessRevoteOverwritesAndMergesWhenRoundOpens() throws Exception {
        long now = System.currentTimeMillis();
        List<UserAccount> t1 = squad("pa", "t1");
        List<UserAccount> t2 = squad("pb", "t2");
        saveState(battleState(t1, t2, now - 10_000L, now + 600_000L));
        UserAccount preGuesser = t1.get(5);  // 1 号小队，出战轮 = 2
        String playerId = "u" + preGuesser.getId();

        playerActions.submit(preGuesser.getUsername(), "pre-guess", squadIds(t2, 0));
        var rows = matchGuesses.findByGameDayAndMatchIdAndGuessTypeAndRoundNo(
                1, "g1", MatchGuess.TYPE_PRE, 2);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getTargets()).contains("u" + t2.get(0).getId());

        // 改投 = 同键覆盖，不新增行
        List<String> revote = new ArrayList<>(squadIds(t2, 0).subList(1, 5));
        revote.add("u" + t2.get(5).getId());
        playerActions.submit(preGuesser.getUsername(), "pre-guess", revote);
        rows = matchGuesses.findByGameDayAndMatchIdAndGuessTypeAndRoundNo(
                1, "g1", MatchGuess.TYPE_PRE, 2);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getTargets()).startsWith("[\"u" + t2.get(1).getId() + "\"");

        // 开窗：第 1 局揭晓结束，推进到第 2 局时 pre 表行并入 guesses 并删行
        ObjectNode root = (ObjectNode) readState();
        ObjectNode match = (ObjectNode) root.path("matches").path("g1");
        match.put("roundPhase", "REVEAL");
        match.put("revealUntil", now - 1);
        match.remove("guessDeadlineAt");
        saveState(root);
        tournament.advanceDueResults();

        JsonNode advanced = matchOf(readState());
        assertThat(advanced.path("round").asInt()).isEqualTo(2);
        assertThat(advanced.path("roundPhase").asText()).isEqualTo("GUESS");
        JsonNode merged = advanced.path("guesses").path("A").path(playerId);
        assertThat(merged).hasSize(5);
        assertThat(merged.get(0).asText()).isEqualTo("u" + t2.get(1).getId());
        assertThat(advanced.path("preGuesses").has("2")).isFalse();
        assertThat(matchGuesses.findByGameDayAndMatchIdAndGuessTypeAndRoundNo(
                1, "g1", MatchGuess.TYPE_PRE, 2)).isEmpty();
    }

    @Test
    void retractRemovesTableRowsAndJsonFallbackIdempotently() throws Exception {
        long now = System.currentTimeMillis();
        List<UserAccount> t1 = squad("ra", "t1");
        List<UserAccount> t2 = squad("rb", "t2");
        saveState(battleState(t1, t2, now - 10_000L, now + 600_000L));
        UserAccount live = t1.get(0);   // 0 号小队，当前轮
        UserAccount future = t1.get(5); // 1 号小队，pre 第 2 轮
        playerActions.submit(live.getUsername(), "round-guess", squadIds(t2, 0));
        playerActions.submit(future.getUsername(), "pre-guess", squadIds(t2, 0));

        playerActions.submit(future.getUsername(), "retract-guess", List.of());
        assertThat(matchGuesses.findByGameDayAndMatchIdAndGuessType(1, "g1", MatchGuess.TYPE_PRE)).isEmpty();
        assertThatThrownBy(() -> playerActions.submit(future.getUsername(), "retract-guess", List.of()))
                .isInstanceOf(IllegalStateException.class).hasMessage("你没有可撤回的猜阵");

        playerActions.submit(live.getUsername(), "retract-guess", List.of());
        assertThat(matchGuesses.findByGameDayAndMatchIdAndGuessTypeAndRoundNo(
                1, "g1", MatchGuess.TYPE_ROUND, 1)).isEmpty();
        assertThatThrownBy(() -> playerActions.submit(live.getUsername(), "retract-guess", List.of()))
                .isInstanceOf(IllegalStateException.class).hasMessage("你没有可撤回的猜阵");

        // JSON 兜底（部署瞬间进行中的局）：无表行时短事务清 JSON，双边幂等
        ObjectNode root = (ObjectNode) readState();
        ObjectNode match = (ObjectNode) root.path("matches").path("g1");
        ArrayNode liveGuess = match.withObject("/guesses").withObject("/A").putArray("u" + live.getId());
        squadIds(t2, 0).forEach(liveGuess::add);
        ArrayNode preGuess = match.withObject("/preGuesses").withObject("/2").withObject("/A")
                .putArray("u" + future.getId());
        squadIds(t2, 0).forEach(preGuess::add);
        saveState(root);

        playerActions.submit(live.getUsername(), "retract-guess", List.of());
        playerActions.submit(future.getUsername(), "retract-guess", List.of());
        JsonNode cleaned = matchOf(readState());
        assertThat(cleaned.path("guesses").path("A").has("u" + live.getId())).isFalse();
        assertThat(cleaned.at("/preGuesses/2/A").has("u" + future.getId())).isFalse();
        assertThatThrownBy(() -> playerActions.submit(live.getUsername(), "retract-guess", List.of()))
                .isInstanceOf(IllegalStateException.class).hasMessage("你没有可撤回的猜阵");
    }

    @Test
    void sealedViewInjectsStatusWithoutLeakingTargets() throws Exception {
        long now = System.currentTimeMillis();
        List<UserAccount> t1 = squad("sa", "t1");
        List<UserAccount> t2 = squad("sb", "t2");
        saveState(battleState(t1, t2, now - 10_000L, now + 600_000L));
        UserAccount guesser = t1.get(0);
        UserAccount preGuesser = t1.get(5);
        playerActions.submit(guesser.getUsername(), "round-guess", squadIds(t2, 0));
        playerActions.submit(preGuesser.getUsername(), "pre-guess", squadIds(t2, 0));

        ObjectNode state = (ObjectNode) readState();
        tournament.injectGuessStatus(state);
        JsonNode match = state.path("matches").path("g1");
        String guesserId = "u" + guesser.getId();
        String preGuesserId = "u" + preGuesser.getId();
        // 注入的只有状态布尔：guesses/preGuesses 内容不进快照
        assertThat(match.path("guessStatus").path("A").path(guesserId).asBoolean()).isTrue();
        assertThat(match.path("preGuessStatus").path("2").path("A").path(preGuesserId).asBoolean()).isTrue();
        assertThat(match.path("guesses").path("A").has(guesserId)).isFalse();
        assertThat(match.path("preGuesses").has("2")).isFalse();

        // 密封视图（深拷贝）：状态保留、内容移除，且不污染注入后的缓存对象
        ObjectNode original = (ObjectNode) state.path("matches").path("g1");
        JsonNode view = tournament.publicStateView(state, "t1", guesserId);
        JsonNode viewMatch = view.path("matches").path("g1");
        assertThat(viewMatch.has("guesses")).isFalse();
        assertThat(viewMatch.has("preGuesses")).isFalse();
        assertThat(viewMatch.at("/guessStatus/A/" + guesserId).asBoolean()).isTrue();
        assertThat(viewMatch.path("guessStatus").has("B")).isTrue();
        assertThat(viewMatch.at("/preGuessStatus/2/A/" + preGuesserId).asBoolean()).isTrue();
        assertThat(viewMatch.at("/preGuessStatus/2").has("B")).isTrue();
        assertThat(original.has("guesses")).as("sealGuesses 不污染缓存快照").isTrue();
        assertThat(original.path("guessStatus").path("A").path(guesserId).asBoolean()).isTrue();
    }

    @Test
    void jsonFallbackBlocksDuplicateAndCountsTowardEarlyReveal() throws Exception {
        long now = System.currentTimeMillis();
        List<UserAccount> t1 = squad("ja", "t1");
        List<UserAccount> t2 = squad("jb", "t2");
        ObjectNode root = battleState(t1, t2, now - 10_000L, now + 600_000L);
        // 部署瞬间进行中的局：A 方 5 份猜阵已在 JSON
        ObjectNode sideA = ((ObjectNode) root.path("matches").path("g1")).withObject("/guesses").withObject("/A");
        for (int i = 0; i < 5; i++) {
            ArrayNode guess = sideA.putArray("u" + t1.get(i).getId());
            squadIds(t2, 0).forEach(guess::add);
        }
        saveState(root);

        // JSON 已有即视为已提交（双读幂等），不写表
        assertThatThrownBy(() -> playerActions.submit(t1.get(0).getUsername(), "round-guess", squadIds(t2, 0)))
                .isInstanceOf(IllegalStateException.class).hasMessage("你已经提交过本轮猜阵");
        assertThat(matchGuesses.findAll()).isEmpty();

        // B 方 5 份走表；齐 5 判定 = 表 ∪ JSON，交齐即提前揭晓
        for (int i = 0; i < 5; i++)
            playerActions.submit(t2.get(i).getUsername(), "round-guess", squadIds(t1, 0));
        JsonNode match = matchOf(readState());
        assertThat(match.path("roundPhase").asText()).isEqualTo("REVEAL");
        assertThat(match.path("rounds").get(0).path("guessHitsA").asInt()).isEqualTo(25);
        assertThat(match.path("rounds").get(0).path("guessHitsB").asInt()).isEqualTo(25);
    }

    @Test
    void rematchClearsStaleActionRowsOnlyForThisMatch() throws Exception {
        long now = System.currentTimeMillis();
        List<UserAccount> t1 = squad("ma", "t1");
        List<UserAccount> t2 = squad("mb", "t2");
        ObjectNode root = battleState(t1, t2, now - 10_000L, now + 600_000L);
        ObjectNode match = (ObjectNode) root.path("matches").path("g1");
        // 三连环全平后的待加赛状态（唯一在赛场次，可重赛）
        match.put("phase", "OVERTIME_PENDING");
        match.remove(List.of("roundPhase", "guessDeadlineAt", "guessOpenedAt"));
        saveState(root);
        matchGuesses.save(new MatchGuess(1, "g1", MatchGuess.TYPE_ROUND, 1, "A",
                "u" + t1.get(0).getId(), "[\"u1\"]"));
        matchGuesses.save(new MatchGuess(1, "g1", MatchGuess.TYPE_PRE, 3, "B",
                "u" + t2.get(5).getId(), "[\"u2\"]"));
        for (UserAccount player : java.util.stream.Stream.concat(t1.stream(), t2.stream()).toList()) {
            playerRolls.save(new com.acedicearena.domain.PlayerRoll(1, 1, "u" + player.getId(),
                    player.getTeamId(), 6, 6, now, false));
            blindBoxes.save(new com.acedicearena.domain.PlayerBlindBox(1, 1, "u" + player.getId(),
                    player.getTeamId(), 2));
        }
        // 同轮别队、别天、别轮的记录必须保留。
        for (int[] scope : List.of(new int[]{1, 1}, new int[]{2, 1}, new int[]{1, 2})) {
            String team = scope[0] == 1 && scope[1] == 1 ? "t3" : "t1";
            playerRolls.save(new com.acedicearena.domain.PlayerRoll(scope[0], scope[1], "other", team, 4, 4, now, false));
            blindBoxes.save(new com.acedicearena.domain.PlayerBlindBox(scope[0], scope[1], "other", team, 1));
        }

        tournament.rematch("admin", "g1");

        // 重赛复用同一 (game_day, match_id, round_no)：旧猜阵表行必须清理
        assertThat(matchGuesses.findByGameDayAndMatchId(1, "g1")).isEmpty();
        JsonNode rematched = readState();
        assertThat(rematched.path("stage").asText()).isEqualTo("ROLL");
        JsonNode rematch = rematched.path("matches").path("g1");
        assertThat(rematch.path("phase").asText()).isEqualTo("PENDING");
        assertThat(rematch.path("round").asInt()).isEqualTo(1);
        assertThat(playerRolls.findAll()).hasSize(3).allSatisfy(row -> assertThat(row.getPlayerId()).isEqualTo("other"));
        assertThat(blindBoxes.findAll()).hasSize(3).allSatisfy(row -> assertThat(row.getPlayerId()).isEqualTo("other"));
        tournament.injectRollResults(rematched);
        assertThat(rematched.at("/teams/0/players/0").has("dice")).isFalse();
        tournament.advanceDueResults();
        assertThat(readState().path("stage").asText()).isEqualTo("ROLL");
        // 同一唯一键可重新落库，不能恢复为上次的结果。
        String playerId = "u" + t1.getFirst().getId();
        playerRolls.saveAndFlush(new com.acedicearena.domain.PlayerRoll(1, 1, playerId, "t1", 1, 1, now + 1, false));
        blindBoxes.saveAndFlush(new com.acedicearena.domain.PlayerBlindBox(1, 1, playerId, "t1", 0));
        assertThat(playerRolls.findByGameDayAndBracketRoundAndPlayerId(1, 1, playerId).orElseThrow().getDice()).isEqualTo(1);
        ((ObjectNode) rematched).put("stage", "BLIND_BOX");
        ((ObjectNode) rematched).put("stageDeadlineAt", now + 600_000);
        saveState((ObjectNode) rematched);
        tournament.advanceDueResults();
        assertThat(readState().path("stage").asText()).isEqualTo("BLIND_BOX");
    }

    @Test
    void notificationsAdvanceVersionOnlyAfterSuccessfulCommit() {
        var clock = new com.acedicearena.service.StateVersionClock();
        var events = new com.acedicearena.service.LobbyEventService(clock);
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactions);
        try {
            for (Runnable notify : List.<Runnable>of(events::stateChanged, events::gameChanged,
                    events::gameChangedNow, events::adminGameChanged, () -> events.teamGameChanged("t1"))) {
                long before = clock.current();
                tx.executeWithoutResult(status -> {
                    notify.run();
                    assertThat(clock.current()).isEqualTo(before);
                    status.setRollbackOnly();
                });
                assertThat(clock.current()).isEqualTo(before);
                tx.executeWithoutResult(status -> {
                    notify.run();
                    assertThat(clock.current()).isEqualTo(before);
                });
                assertThat(clock.current()).isEqualTo(before + 1);
            }
        } finally {
            events.close();
        }
    }

    /* ---------- 构造 ---------- */

    private UserAccount newUser(String username, String teamId) {
        UserAccount account = users.save(
                new UserAccount(username, "玩家" + username, "销售部", "USER", "hash", "salt"));
        account.assignTeam(teamId);
        return users.save(account);
    }

    /** 10 人：前 5 人 0 号小队（第 1 轮出战），后 5 人 1 号小队（第 2 轮出战）。 */
    private List<UserAccount> squad(String prefix, String teamId) {
        List<UserAccount> accounts = new ArrayList<>();
        for (int i = 0; i < 10; i++) accounts.add(newUser(prefix + i, teamId));
        return accounts;
    }

    private List<String> squadIds(List<UserAccount> accounts, int squadIndex) {
        List<String> ids = new ArrayList<>();
        for (int i = squadIndex * 5; i < squadIndex * 5 + 5; i++) ids.add("u" + accounts.get(i).getId());
        return ids;
    }

    /** BATTLE 阶段：t1/t2 各 10 人两个小队，g1 进行中、第 1 轮 GUESS 窗口。 */
    private ObjectNode battleState(List<UserAccount> t1, List<UserAccount> t2, long openedAt, long deadlineAt) {
        ObjectNode root = mapper.createObjectNode();
        root.put("version", 5);
        root.put("mode", "parallel");
        root.put("day", 1);
        root.put("stage", "BATTLE");
        ArrayNode teams = root.putArray("teams");
        teams.add(teamNode("t1", t1));
        teams.add(teamNode("t2", t2));
        ObjectNode match = root.putObject("matches").putObject("g1");
        match.put("id", "g1").put("a", "t1").put("b", "t2");
        match.put("winsA", 0).put("winsB", 0).put("round", 1);
        match.put("status", "active").put("phase", "BATTLE").put("roundPhase", "GUESS");
        match.putArray("rounds");
        match.withObject("/guesses").putObject("A");
        match.withObject("/guesses").putObject("B");
        match.putObject("preGuesses");
        match.put("guessOpenedAt", openedAt);
        match.put("guessDeadlineAt", deadlineAt);
        return root;
    }

    private ObjectNode teamNode(String teamId, List<UserAccount> accounts) {
        ObjectNode team = mapper.createObjectNode();
        team.put("id", teamId).put("name", teamId);
        ArrayNode players = team.putArray("players");
        ArrayNode squads = team.putArray("squads");
        squads.addArray();
        squads.addArray();
        for (int i = 0; i < accounts.size(); i++) {
            String playerId = "u" + accounts.get(i).getId();
            players.addObject().put("id", playerId).put("name", accounts.get(i).getDisplayName());
            ((ArrayNode) squads.get(i / 5)).add(playerId);
        }
        return team;
    }

    private void saveState(ObjectNode root) {
        states.save(new GameStateRecord(1L, root.toString(), "test"));
    }

    private JsonNode readState() throws Exception {
        return mapper.readTree(states.findById(1L).orElseThrow().getContent());
    }

    private JsonNode matchOf(JsonNode root) {
        return root.path("matches").path("g1");
    }
}
