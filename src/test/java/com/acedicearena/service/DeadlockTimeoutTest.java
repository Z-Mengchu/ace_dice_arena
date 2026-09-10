package com.acedicearena.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 任何一个环节都不能因为某个玩家不操作而永久卡住整届赛事。 */
class DeadlockTimeoutTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void captainVoteTimeoutElectsByRosterOrderAndMovesToSquadForm() {
        ObjectNode root = state("CAPTAIN_VOTE");
        root.path("teams").forEach(team ->
                ((ObjectNode) team).put("roleVoteDeadlineAt", System.currentTimeMillis() - 1));

        assertThat(service().expireVoting(root, System.currentTimeMillis())).isTrue();

        root.path("teams").forEach(team ->
                assertThat(team.at("/roles/captain").asText()).isEqualTo(team.path("players").get(0).path("id").asText()));
        assertThat(root.path("stage").asText()).isEqualTo("SQUAD_FORM");
        assertThat(root.path("stageDeadlineAt").asLong()).isGreaterThan(System.currentTimeMillis());
    }

    @Test
    void captainVoteTimeoutSkipsEmptyTeamsAndStillAdvances() {
        ObjectNode root = state("CAPTAIN_VOTE");
        ObjectNode emptyTeam = ((ArrayNode) root.path("teams")).addObject();
        emptyTeam.put("id", "t3").put("name", "t3");
        emptyTeam.putObject("roles");
        emptyTeam.putObject("roleVotes");
        emptyTeam.putArray("players");
        root.path("teams").forEach(team ->
                ((ObjectNode) team).put("roleVoteDeadlineAt", System.currentTimeMillis() - 1));

        assertThat(service().expireVoting(root, System.currentTimeMillis())).isTrue();

        assertThat(emptyTeam.path("roles").has("captain")).isFalse();
        assertThat(root.path("stage").asText()).isEqualTo("SQUAD_FORM");
    }

    @Test
    void squadFormTimeoutSplitsThirtyPlayersIntoSixSquadsOfFive() {
        ObjectNode root = state("SQUAD_FORM");
        root.put("stageDeadlineAt", System.currentTimeMillis() - 1);

        assertThat(service().expireSquadForm(root, System.currentTimeMillis())).isTrue();

        root.path("teams").forEach(team -> {
            JsonNodeSquadsAssert.assertSixByFive(team.path("squads"));
            Set<String> all = new HashSet<>();
            team.path("squads").forEach(squad -> squad.forEach(id -> all.add(id.asText())));
            Set<String> roster = new HashSet<>();
            team.path("players").forEach(player -> roster.add(player.path("id").asText()));
            assertThat(all).isEqualTo(roster);
        });
        assertThat(root.path("stage").asText()).isEqualTo("ROLL");
        assertThat(root.has("rollGoAt")).isTrue();
    }

    @Test
    void squadFormCompletesEarlyWhenEveryTeamHasFormed() {
        ObjectNode root = state("SQUAD_FORM");
        root.put("stageDeadlineAt", System.currentTimeMillis() + 60_000L);
        root.path("teams").forEach(team -> formSquads((ObjectNode) team));

        assertThat(service().expireSquadForm(root, System.currentTimeMillis())).isTrue();
        assertThat(root.path("stage").asText()).isEqualTo("ROLL");
    }

    @Test
    void rollTimeoutAutoRollsPerSquadWithSquadOpenPlusOneSecondTimestamp() {
        ObjectNode root = state("ROLL");
        root.path("teams").forEach(team -> formSquads((ObjectNode) team));
        root.put("rollGoAt", 10_000L);
        ArrayNode rollOpenAts = root.putArray("rollOpenAts");
        for (int k = 0; k < 6; k++) rollOpenAts.add(10_000L + k * 1_000L);
        root.put("stageDeadlineAt", System.currentTimeMillis() - 1);

        assertThat(service().expireRoll(root, System.currentTimeMillis())).isTrue();

        root.path("teams").forEach(team -> {
            for (int k = 0; k < 6; k++) {
                long expectedRollTs = 10_000L + k * 1_000L + 1_000L;
                team.path("squads").get(k).forEach(idNode -> {
                    JsonNode player = findPlayer(team, idNode.asText());
                    assertThat(player.path("dice").asInt()).isBetween(1, 6);
                    assertThat(player.path("diceFinal").asInt()).isEqualTo(player.path("dice").asInt());
                    assertThat(player.path("rollTs").asLong()).isEqualTo(expectedRollTs);
                    assertThat(player.path("autoRolled").asBoolean()).isTrue();
                });
            }
        });
        assertThat(root.path("stage").asText()).isEqualTo("BLIND_BOX");
        assertThat(root.path("stageDeadlineAt").asLong()).isGreaterThan(System.currentTimeMillis());
    }

    @Test
    void squadWindowPassedDoesNotAutoRollBeforeTheSharedDeadline() {
        ObjectNode root = state("ROLL");
        root.path("teams").forEach(team -> formSquads((ObjectNode) team));
        // 1 号小队开掷已过 15.5s，但全局截止（go+20s，全员统一）还没到：不代掷、不推进
        long now = System.currentTimeMillis();
        long go = now - 15_500L;
        root.put("rollGoAt", go);
        ArrayNode rollOpenAts = root.putArray("rollOpenAts");
        for (int k = 0; k < 6; k++) rollOpenAts.add(go + k * 1_000L);
        root.put("stageDeadlineAt", go + 20_000L);

        assertThat(service().expireRoll(root, now)).isFalse();

        assertThat(root.path("stage").asText()).isEqualTo("ROLL");
        root.path("teams").forEach(team -> team.path("players")
                .forEach(player -> assertThat(player.has("dice")).isFalse()));
    }

    @Test
    void everyoneRolledMovesToBlindBoxBeforeTheStageDeadline() {
        ObjectNode root = state("ROLL");
        root.path("teams").forEach(team -> formSquads((ObjectNode) team));
        long now = System.currentTimeMillis();
        long go = now - 2_000L;
        root.put("rollGoAt", go);
        ArrayNode rollOpenAts = root.putArray("rollOpenAts");
        for (int k = 0; k < 6; k++) rollOpenAts.add(go + k * 1_000L);
        root.put("stageDeadlineAt", go + 20_000L);
        giveDice(root);

        assertThat(service().expireRoll(root, now)).isTrue();

        assertThat(root.path("stage").asText()).isEqualTo("BLIND_BOX");
        root.path("teams").forEach(team -> team.path("players").forEach(player ->
                assertThat(player.has("autoRolled")).isFalse()));
    }

    /**
     * 盲盒超时：未开者按放弃处理（不写字段，个人点数按 0 计），已开结果从 player_blind_box
     * 合并回 JSON；推进由盲盒内存运行态的统一关闭入口完成。
     */
    @Test
    void blindBoxTimeoutForfeitsUnopenedBoxesAndMovesToTactics() throws Exception {
        ObjectNode root = state("BLIND_BOX");
        root.put("stageDeadlineAt", System.currentTimeMillis() + 60_000L);
        // 全员已掷骰但都还没开盲盒
        root.path("teams").forEach(team -> team.path("players").forEach(playerNode -> {
            ObjectNode player = (ObjectNode) playerNode;
            player.put("dice", 3).put("diceFinal", 3).put("rollTs", 1_000L);
        }));
        // t1 一名队员已经自己开过（结果在 player_blind_box 表，不在 JSON 行内）
        var states = mock(com.acedicearena.repository.GameStateRepository.class);
        var users = mock(com.acedicearena.repository.UserAccountRepository.class);
        var blindBoxes = mock(com.acedicearena.repository.PlayerBlindBoxRepository.class);
        var txManager = mock(org.springframework.transaction.PlatformTransactionManager.class);
        org.mockito.Mockito.when(txManager.getTransaction(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> new org.springframework.transaction.support.SimpleTransactionStatus());
        var record = new com.acedicearena.domain.GameStateRecord(1L, root.toString(), "test");
        org.mockito.Mockito.when(states.findLockedById(1L)).thenReturn(java.util.Optional.of(record));
        org.mockito.Mockito.when(blindBoxes.findByGameDayAndBracketRound(1, 1)).thenReturn(
                List.of(new com.acedicearena.domain.PlayerBlindBox(1, 1, "t1-p1", "t1", 3)));
        BlindBoxRoundService rounds = new BlindBoxRoundService(states, users, blindBoxes, mapper,
                mock(LobbyEventService.class), txManager);
        rounds.activateAfterCommit(root);

        // 截止/强制推进：统一关闭入口合并已开结果并进入 TACTICS
        assertThat(rounds.advanceIfReady(System.currentTimeMillis(), true)).isTrue();

        ObjectNode advanced = (ObjectNode) mapper.readTree(record.getContent());
        ObjectNode opened = (ObjectNode) advanced.path("teams").get(0).path("players").get(0);
        assertThat(opened.path("blindBox").asInt()).isEqualTo(3);
        assertThat(opened.path("blindBoxOpened").asBoolean()).isTrue();
        advanced.path("teams").forEach(team -> team.path("players").forEach(player -> {
            if (player == opened) return;
            assertThat(player.has("blindBox")).isFalse();
            assertThat(player.has("blindBoxOpened")).isFalse();
        }));
        assertThat(advanced.path("stage").asText()).isEqualTo("TACTICS");
        assertThat(advanced.path("stageDeadlineAt").asLong()).isGreaterThan(System.currentTimeMillis());
    }

    @Test
    void tacticsTimeoutLocksDefaultOrderAndStartsBattle() {
        ObjectNode root = state("TACTICS");
        root.put("stageDeadlineAt", System.currentTimeMillis() - 1);
        root.path("teams").forEach(team -> formSquads((ObjectNode) team));
        giveDice(root);

        assertThat(service().expireTactics(root, System.currentTimeMillis())).isTrue();

        assertThat(root.path("stage").asText()).isEqualTo("BATTLE");
        assertThat(root.has("stageDeadlineAt")).isFalse();
        ObjectNode match = match(root);
        assertThat(match.path("phase").asText()).isEqualTo("BATTLE");
        assertThat(match.path("round").asInt()).isEqualTo(1);
        assertThat(match.path("roundPhase").asText()).isEqualTo("GUESS");
        assertThat(match.path("guessDeadlineAt").asLong()).isGreaterThan(System.currentTimeMillis());
        assertThat(match.path("rounds")).isEmpty();
    }

    @Test
    void guessTimeoutRevealsTheRoundWithZeroHits() {
        ObjectNode root = battleState(1);
        ObjectNode match = match(root);
        match.put("guessDeadlineAt", System.currentTimeMillis() - 1);

        assertThat(service().expireGuesses(root, System.currentTimeMillis())).isTrue();

        assertThat(match.path("roundPhase").asText()).isEqualTo("REVEAL");
        assertThat(match.path("revealUntil").asLong()).isGreaterThan(System.currentTimeMillis());
        assertThat(match.has("guessDeadlineAt")).isFalse();
        JsonNode round = match.path("rounds").get(0);
        assertThat(round.path("round").asInt()).isEqualTo(1);
        assertThat(round.path("guessHitsA").asInt()).isZero();
        assertThat(round.path("guessHitsB").asInt()).isZero();
        // t1 全 6 无暴击（时刻拉得很开），t2 全 1
        assertThat(round.path("powerA").asDouble()).isEqualTo(30d);
        assertThat(round.path("powerB").asDouble()).isEqualTo(5d);
        assertThat(round.path("critA").asBoolean()).isFalse();
        assertThat(round.path("winner").asText()).isEqualTo("A");
        assertThat(match.path("winsA").asInt()).isEqualTo(1);
    }

    @Test
    void autoRolledSquadCannotCritEvenWithPerfectlyAlignedTimestamps() {
        ObjectNode root = battleState(1);
        ObjectNode match = match(root);
        // 两队时刻都完全对齐，但 t2 是系统代掷
        root.path("teams").forEach(team -> {
            boolean auto = "t2".equals(team.path("id").asText());
            int index = 0;
            for (var player : team.path("players")) {
                ObjectNode node = (ObjectNode) player;
                node.put("rollTs", 5_000L);
                if (auto) node.put("autoRolled", true);
                index++;
            }
        });

        service().revealRound(root, match);

        JsonNode round = match.path("rounds").get(0);
        assertThat(round.path("critA").asBoolean()).isTrue();
        assertThat(round.path("critB").asBoolean()).isFalse();
        assertThat(round.path("powerA").asDouble()).isEqualTo(45d);   // 30 × 1.5
        assertThat(round.path("powerB").asDouble()).isEqualTo(5d);
    }

    @Test
    void revealTimeoutAdvancesToTheNextRoundAndTheSixthRevealProducesTheResult() {
        ObjectNode root = battleState(1);
        ObjectNode match = match(root);
        match.put("roundPhase", "REVEAL");
        match.put("revealUntil", System.currentTimeMillis() - 1);

        assertThat(service().completeRoundReveals(root, System.currentTimeMillis())).isTrue();
        assertThat(match.path("round").asInt()).isEqualTo(2);
        assertThat(match.path("roundPhase").asText()).isEqualTo("GUESS");
        assertThat(match.path("guessDeadlineAt").asLong()).isGreaterThan(System.currentTimeMillis());

        match.put("round", 6);
        match.put("roundPhase", "REVEAL");
        match.put("revealUntil", System.currentTimeMillis() - 1);
        match.put("winsA", 4).put("winsB", 1);
        assertThat(service().completeRoundReveals(root, System.currentTimeMillis())).isTrue();
        assertThat(match.path("phase").asText()).isEqualTo("RESULT");
        assertThat(match.path("winner").asText()).isEqualTo("t1");
        assertThat(match.path("resultReadyAt").asLong()).isGreaterThan(System.currentTimeMillis());
    }

    @Test
    void resultDisplayExpiryMarksTheMatchDone() {
        ObjectNode root = battleState(1);
        ObjectNode match = match(root);
        match.put("phase", "RESULT");
        match.remove("roundPhase");
        match.put("winner", "t1");
        match.put("resultReadyAt", System.currentTimeMillis() - 1);

        assertThat(service().completeDueResults(root, System.currentTimeMillis())).isTrue();
        assertThat(match.path("status").asText()).isEqualTo("done");
        assertThat(match.path("phase").asText()).isEqualTo("FINISHED");
    }

    @Test
    void nothingIsForcedWhileThereIsStillTimeLeft() {
        long future = System.currentTimeMillis() + 60_000L;
        ObjectNode root = state("ROLL");
        root.put("stageDeadlineAt", future);
        var service = service();
        assertThat(service.expireRoll(root, System.currentTimeMillis())).isFalse();
        assertThat(root.path("stage").asText()).isEqualTo("ROLL");

        root.put("stage", "BLIND_BOX");
        // 盲盒阶段的截止推进已由内存运行态接管：未全员完成且未到点时 advanceIfReady 不动（规格见 BlindBoxRoundServiceTest）

        root.put("stage", "TACTICS");
        assertThat(service.expireTactics(root, System.currentTimeMillis())).isFalse();

        ObjectNode battleRoot = battleState(1);
        assertThat(service.expireGuesses(battleRoot, System.currentTimeMillis())).isFalse();
        assertThat(service.completeRoundReveals(battleRoot, System.currentTimeMillis())).isFalse();
        assertThat(match(battleRoot).path("rounds")).isEmpty();
    }

    /* ---------- 盲盒阶段读写锁 ---------- */

    /**
     * 公平阶段读写锁的确定性顺序：进行中的开盒（读锁）阻塞截止（写锁）；
     * 写锁已排队后，新的开盒读锁不能插队，写锁释放后看到的是已关闭的运行态。
     */
    @Test
    void blindBoxFairLockQueuesNewReadersBehindPendingWriter() throws Exception {
        var states = mock(com.acedicearena.repository.GameStateRepository.class);
        var users = mock(com.acedicearena.repository.UserAccountRepository.class);
        var blindBoxes = mock(com.acedicearena.repository.PlayerBlindBoxRepository.class);
        var events = mock(LobbyEventService.class);
        var txManager = mock(org.springframework.transaction.PlatformTransactionManager.class);
        org.mockito.Mockito.when(txManager.getTransaction(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> new org.springframework.transaction.support.SimpleTransactionStatus());
        java.util.Map<String, com.acedicearena.domain.PlayerBlindBox> committed = new java.util.concurrent.ConcurrentHashMap<>();
        ThreadLocal<java.util.Map<String, com.acedicearena.domain.PlayerBlindBox>> pending =
                ThreadLocal.withInitial(java.util.LinkedHashMap::new);
        org.mockito.Mockito.doAnswer(inv -> {
            committed.putAll(pending.get());
            pending.remove();
            return null;
        }).when(txManager).commit(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.doAnswer(inv -> {
            pending.remove();
            return null;
        }).when(txManager).rollback(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.when(blindBoxes.findByGameDayAndBracketRoundAndPlayerId(
                        org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(inv -> java.util.Optional.ofNullable(committed.get(inv.getArgument(2))));
        org.mockito.Mockito.when(blindBoxes.findByGameDayAndBracketRound(
                        org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(inv -> List.copyOf(committed.values()));

        BlindBoxRoundService rounds = new BlindBoxRoundService(states, users, blindBoxes, mapper, events, txManager);
        ObjectNode root = mapper.createObjectNode();
        root.put("mode", "parallel");
        root.put("day", 1);
        root.put("stage", "BLIND_BOX");
        root.put("stageDeadlineAt", System.currentTimeMillis() + 60_000L);
        ArrayNode teams = root.putArray("teams");
        teams.addObject().put("id", "t1").putArray("players")
                .addObject().put("id", "u1").put("name", "甲");
        teams.addObject().put("id", "t2").putArray("players");
        ObjectNode match = root.putObject("matches").putObject("g1");
        match.put("id", "g1").put("a", "t1").put("b", "t2").put("status", "active");
        rounds.activateAfterCommit(root);
        org.mockito.Mockito.when(states.findLockedById(1L)).thenReturn(java.util.Optional.of(
                new com.acedicearena.domain.GameStateRecord(1L, root.toString(), "test")));
        com.acedicearena.domain.UserAccount alice = new com.acedicearena.domain.UserAccount(
                "alice", "甲", "技术部", "USER", "hash", "salt");
        alice.assignTeam("t1");
        org.springframework.test.util.ReflectionTestUtils.setField(alice, "id", 1L);
        org.mockito.Mockito.when(users.findByUsername("alice")).thenReturn(java.util.Optional.of(alice));

        // 开盒事务停在提交前（读锁持有中）
        java.util.concurrent.CountDownLatch inTransaction = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doAnswer(inv -> {
            com.acedicearena.domain.PlayerBlindBox row = inv.getArgument(0);
            pending.get().put(row.getPlayerId(), row);
            inTransaction.countDown();
            release.await(5, java.util.concurrent.TimeUnit.SECONDS);
            return row;
        }).when(blindBoxes).saveAndFlush(org.mockito.ArgumentMatchers.any());

        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(3);
        var openFuture = pool.submit(() -> rounds.open("alice", 0));
        assertThat(inTransaction.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        // 截止线程排队等写锁
        var advanceFuture = pool.submit(() -> rounds.advanceIfReady(System.currentTimeMillis(), true));
        Thread.sleep(200);
        assertThat(advanceFuture.isDone()).isFalse();
        // 写锁已排队后新读者不能插队：第二个开盒请求在写锁之后获得读锁，看到已关闭
        var lateOpenFuture = pool.submit(() -> {
            try {
                rounds.open("alice", 0);
                return null;
            } catch (IllegalStateException e) {
                return e;
            }
        });
        Thread.sleep(100);
        release.countDown();

        assertThat(openFuture.get(5, java.util.concurrent.TimeUnit.SECONDS).boxes()).isNotNull();
        assertThat(advanceFuture.get(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(lateOpenFuture.get(5, java.util.concurrent.TimeUnit.SECONDS))
                .isNotNull().hasMessage("当前不在开盲盒阶段");
        pool.shutdown();
    }

    /* ---------- 构造 ---------- */

    private ParallelTournamentService service() {
        return new ParallelTournamentService(
                mock(com.acedicearena.repository.GameStateRepository.class),
                mock(com.acedicearena.repository.UserAccountRepository.class),
                mock(com.acedicearena.repository.PerformanceRecordRepository.class),
                mock(com.acedicearena.repository.GameControlRepository.class), mapper,
                mock(LobbyEventService.class), 6_000L,
                mock(com.acedicearena.repository.BattleReportRepository.class),
                mock(com.acedicearena.repository.MatchReportRepository.class),
                mock(com.acedicearena.repository.PlayerBlindBoxRepository.class),
                mock(BlindBoxRoundService.class),
                mock(org.springframework.transaction.PlatformTransactionManager.class));
    }

    /** t1/t2 各 30 人，一场 g1 进行中。 */
    private ObjectNode state(String stage) {
        ObjectNode root = mapper.createObjectNode();
        root.put("mode", "parallel"); root.put("stage", stage);
        ArrayNode teams = root.putArray("teams");
        for (String id : List.of("t1", "t2")) {
            ObjectNode team = teams.addObject();
            team.put("id", id); team.put("name", id);
            team.put("growthCoefficient", 1.0);
            team.put("rerollQuota", 10); team.put("rerollUsed", 0);
            team.putObject("roles"); team.putObject("roleVotes");
            ArrayNode players = team.putArray("players");
            for (int i = 1; i <= 30; i++)
                players.addObject().put("id", id + "-p" + i).put("name", id + "队员" + i)
                        .put("role", i <= 15 ? "front" : "back").put("managed", false);
        }
        ObjectNode match = root.putObject("matches").putObject("g1");
        match.put("id", "g1"); match.put("a", "t1"); match.put("b", "t2");
        match.put("winsA", 0); match.put("winsB", 0); match.put("round", 1);
        match.put("status", "active"); match.put("phase", "PENDING");
        match.putArray("rounds");
        return root;
    }

    /** BATTLE 阶段第 round 局：两队已分队且有掷骰数据（t1 全 6、t2 全 1，时刻都拉得很开不暴击）。 */
    private ObjectNode battleState(int round) {
        ObjectNode root = state("BATTLE");
        root.path("teams").forEach(team -> formSquads((ObjectNode) team));
        giveDice(root);
        ObjectNode match = match(root);
        match.put("phase", "BATTLE").put("round", round).put("roundPhase", "GUESS");
        match.put("guessDeadlineAt", System.currentTimeMillis() + 30_000L);
        match.putObject("guesses").putObject("A");
        ((ObjectNode) match.path("guesses")).putObject("B");
        return root;
    }

    private void formSquads(ObjectNode team) {
        List<String> roster = new java.util.ArrayList<>();
        team.path("players").forEach(player -> roster.add(player.path("id").asText()));
        ArrayNode squads = team.putArray("squads");
        for (int s = 0; s < 6; s++) {
            ArrayNode squad = squads.addArray();
            for (int i = s * 5; i < s * 5 + 5; i++) squad.add(roster.get(i));
        }
    }

    private void giveDice(ObjectNode root) {
        root.path("teams").forEach(team -> {
            int die = "t1".equals(team.path("id").asText()) ? 6 : 1;
            int index = 0;
            for (var playerNode : team.path("players")) {
                ObjectNode player = (ObjectNode) playerNode;
                player.put("dice", die).put("diceFinal", die);
                player.put("rollTs", 1_000L + index * 600L);
                player.put("blindBox", 0).put("blindBoxOpened", true);
                index++;
            }
        });
    }

    private ObjectNode match(ObjectNode root) {
        return (ObjectNode) root.path("matches").path("g1");
    }

    private JsonNode findPlayer(JsonNode team, String playerId) {
        for (JsonNode player : team.path("players"))
            if (playerId.equals(player.path("id").asText())) return player;
        throw new AssertionError("player not found: " + playerId);
    }

    /** 结构断言助手，避免在每个用例里重复两层 size 检查。 */
    private static final class JsonNodeSquadsAssert {
        static void assertSixByFive(JsonNode squads) {
            assertThat(squads).hasSize(6);
            squads.forEach(squad -> assertThat(squad).hasSize(5));
        }
    }
}
