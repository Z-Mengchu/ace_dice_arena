package com.acedicearena;

import com.acedicearena.domain.GameStateRecord;
import com.acedicearena.domain.PlayerRoll;
import com.acedicearena.domain.UserAccount;
import com.acedicearena.repository.GameStateRepository;
import com.acedicearena.repository.PlayerRollRepository;
import com.acedicearena.repository.UserAccountRepository;
import com.acedicearena.service.OnlineGameService;
import com.acedicearena.service.ParallelTournamentService;
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
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * dice 拆表（player_roll）的 H2 集成测试：独立内存库隔离其他测试上下文的 500ms 定时扫描。
 * 覆盖并发同人重复掷骰唯一键幂等、JSON 已有点数的双读兼容、全员掷齐锁内推进进 BLIND_BOX
 * 并合并回 JSON、以及 ROLL 阶段视图从表注入。
 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:player-roll-test;DB_CLOSE_DELAY=-1"})
class PlayerRollServiceTest {
    @Autowired ParallelTournamentService tournament;
    @Autowired OnlineGameService onlineGameService;
    @Autowired PlayerRollRepository playerRolls;
    @Autowired GameStateRepository states;
    @Autowired UserAccountRepository users;
    @Autowired ObjectMapper mapper;

    @BeforeEach
    void cleanUp() {
        playerRolls.deleteAll();
        states.deleteAll();
        users.deleteAll();
    }

    @Test
    void concurrentRollsFromTheSamePlayerAreRecordedOnce() throws Exception {
        long now = System.currentTimeMillis();
        UserAccount roller = newUser("roller", "t1");
        List<UserAccount> t1 = new ArrayList<>(List.of(roller));
        for (int i = 0; i < 4; i++) t1.add(newUser("t1mate" + i, "t1"));
        List<UserAccount> t2 = new ArrayList<>();
        for (int i = 0; i < 5; i++) t2.add(newUser("t2mate" + i, "t2"));
        saveState(rollState(t1, t2, now - 1_000L, now + 600_000L));

        String token = onlineGameService.join("roller", "u" + roller.getId()).token();
        onlineGameService.ping(token, (double) System.currentTimeMillis());
        onlineGameService.calibrate(token, 20d);

        int threads = 8;
        List<ParallelTournamentService.LiveRoll> results = runConcurrent(threads, () -> {
            // 唯一键冲突兜底「读已有行」存在提交可见性窗口，等价于客户端重试
            for (int attempt = 0; ; attempt++) {
                try {
                    return onlineGameService.roll(token, (double) System.currentTimeMillis());
                } catch (RuntimeException e) {
                    if (attempt >= 9) throw e;
                    Thread.sleep(25);
                }
            }
        });

        assertThat(results).hasSize(threads);
        int die = results.get(0).die();
        long rollTs = results.get(0).rollTs();
        assertThat(results).allSatisfy(roll -> {
            assertThat(roll.die()).isEqualTo(die);
            assertThat(roll.rollTs()).isEqualTo(rollTs);
        });
        List<PlayerRoll> rows = playerRolls.findByGameDayAndBracketRound(1, 1);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getPlayerId()).isEqualTo("u" + roller.getId());
        assertThat(rows.get(0).getDice()).isEqualTo(die);
        assertThat(rows.get(0).getRollTs()).isEqualTo(rollTs);
        // JSON 行内没有 dice，但席位查询经双读仍报告已掷
        assertThat(tournament.rollAssignment("roller").alreadyRolled()).isTrue();
    }

    @Test
    void playerWithDiceAlreadyInJsonGetsTheJsonResultBackWithoutATableRow() throws Exception {
        long now = System.currentTimeMillis();
        UserAccount roller = newUser("jsonroller", "t1");
        List<UserAccount> t1 = new ArrayList<>(List.of(roller));
        for (int i = 0; i < 4; i++) t1.add(newUser("t1jsonmate" + i, "t1"));
        List<UserAccount> t2 = new ArrayList<>();
        for (int i = 0; i < 5; i++) t2.add(newUser("t2jsonmate" + i, "t2"));
        ObjectNode root = rollState(t1, t2, now - 1_000L, now + 600_000L);
        // 部署瞬间进行中的局：点数仍在 JSON 里（diceFinal 被重掷过，与 dice 不同）
        ((ObjectNode) playerNode(root, "t1", "u" + roller.getId()))
                .put("dice", 4).put("diceFinal", 5).put("rollTs", 123_456L);
        saveState(root);

        ParallelTournamentService.LiveRoll roll = tournament.recordLiveRoll("jsonroller", now);

        assertThat(roll.die()).isEqualTo(4);
        assertThat(roll.rollTs()).isEqualTo(123_456L);
        assertThat(playerRolls.findAll()).isEmpty();
    }

    @Test
    void lastRollAdvancesToBlindBoxAndMergesTableRowsIntoJson() throws Exception {
        long now = System.currentTimeMillis();
        UserAccount last = newUser("lastroller", "t1");
        List<UserAccount> t1 = new ArrayList<>(List.of(last));
        for (int i = 0; i < 4; i++) t1.add(newUser("t1lastmate" + i, "t1"));
        List<UserAccount> t2 = new ArrayList<>();
        for (int i = 0; i < 5; i++) t2.add(newUser("t2lastmate" + i, "t2"));
        ObjectNode root = rollState(t1, t2, now - 1_000L, now + 600_000L);
        // 除 lastroller 外全员已掷（JSON 口径）
        String lastId = "u" + last.getId();
        for (JsonNode team : root.path("teams"))
            for (JsonNode player : team.path("players"))
                if (!lastId.equals(player.path("id").asText()))
                    ((ObjectNode) player).put("dice", 3).put("diceFinal", 3).put("rollTs", now - 1_000L);
        saveState(root);

        ParallelTournamentService.LiveRoll roll = tournament.recordLiveRoll("lastroller", now);

        JsonNode saved = mapper.readTree(states.findById(1L).orElseThrow().getContent());
        assertThat(saved.path("stage").asText()).isEqualTo("BLIND_BOX");
        JsonNode merged = playerNode(saved, "t1", lastId);
        assertThat(merged.path("dice").asInt()).isEqualTo(roll.die());
        assertThat(merged.path("diceFinal").asInt()).isEqualTo(roll.die());
        assertThat(merged.path("rollTs").asLong()).isEqualTo(roll.rollTs());
        assertThat(merged.has("autoRolled")).isFalse();
        // 已有 JSON 点数的玩家保持原值（skip-if-present，JSON 旧值优先）
        JsonNode mate = playerNode(saved, "t1", "u" + t1.get(1).getId());
        assertThat(mate.path("dice").asInt()).isEqualTo(3);
    }

    @Test
    void injectRollResultsFillsMissingPlayersFromTableRowsOnlyDuringRollStage() throws Exception {
        long now = System.currentTimeMillis();
        UserAccount injector = newUser("injector", "t1");
        UserAccount keeper = newUser("keeper", "t1");
        List<UserAccount> t1 = new ArrayList<>(List.of(injector, keeper));
        for (int i = 0; i < 3; i++) t1.add(newUser("t1injectmate" + i, "t1"));
        List<UserAccount> t2 = new ArrayList<>();
        for (int i = 0; i < 5; i++) t2.add(newUser("t2injectmate" + i, "t2"));
        ObjectNode root = rollState(t1, t2, now - 1_000L, now + 600_000L);
        ((ObjectNode) playerNode(root, "t1", "u" + keeper.getId()))
                .put("dice", 2).put("diceFinal", 2).put("rollTs", 111L);
        saveState(root);
        playerRolls.save(new PlayerRoll(1, 1, "u" + injector.getId(), "t1", 6, 6, 777L, true));

        ObjectNode state = (ObjectNode) mapper.readTree(states.findById(1L).orElseThrow().getContent());
        tournament.injectRollResults(state);

        JsonNode injected = playerNode(state, "t1", "u" + injector.getId());
        assertThat(injected.path("dice").asInt()).isEqualTo(6);
        assertThat(injected.path("diceFinal").asInt()).isEqualTo(6);
        assertThat(injected.path("rollTs").asLong()).isEqualTo(777L);
        assertThat(injected.path("autoRolled").asBoolean()).isTrue();
        // JSON 已有点数者不覆盖
        JsonNode kept = playerNode(state, "t1", "u" + keeper.getId());
        assertThat(kept.path("dice").asInt()).isEqualTo(2);
        assertThat(kept.path("rollTs").asLong()).isEqualTo(111L);

        // 非 ROLL 阶段不注入
        ObjectNode other = (ObjectNode) mapper.readTree(states.findById(1L).orElseThrow().getContent());
        other.put("stage", "BLIND_BOX");
        tournament.injectRollResults(other);
        assertThat(playerNode(other, "t1", "u" + injector.getId()).has("dice")).isFalse();
    }

    /* ---------- 构造 ---------- */

    private UserAccount newUser(String username, String teamId) {
        UserAccount account = users.save(
                new UserAccount(username, "玩家" + username, "销售部", "USER", "hash", "salt"));
        account.assignTeam(teamId);
        return users.save(account);
    }

    /** ROLL 阶段：t1/t2 单小队全员 0 号小队（go 时刻开掷），g1 进行中。 */
    private ObjectNode rollState(List<UserAccount> t1, List<UserAccount> t2, long goAt, long deadlineAt) {
        ObjectNode root = mapper.createObjectNode();
        root.put("version", 5);
        root.put("mode", "parallel");
        root.put("day", 1);
        root.put("stage", "ROLL");
        root.put("rollGoAt", goAt);
        root.put("stageDeadlineAt", deadlineAt);
        root.putArray("rollOpenAts").add(goAt);
        ArrayNode teams = root.putArray("teams");
        teams.add(teamNode("t1", t1));
        teams.add(teamNode("t2", t2));
        ObjectNode match = root.putObject("matches").putObject("g1");
        match.put("id", "g1").put("a", "t1").put("b", "t2");
        match.put("winsA", 0).put("winsB", 0).put("round", 1);
        match.put("status", "active").put("phase", "PENDING");
        match.putArray("rounds");
        return root;
    }

    private ObjectNode teamNode(String teamId, List<UserAccount> accounts) {
        ObjectNode team = mapper.createObjectNode();
        team.put("id", teamId).put("name", teamId);
        ArrayNode players = team.putArray("players");
        ArrayNode squad = team.putArray("squads").addArray();
        for (UserAccount account : accounts) {
            String playerId = "u" + account.getId();
            players.addObject().put("id", playerId).put("name", account.getDisplayName());
            squad.add(playerId);
        }
        return team;
    }

    private void saveState(ObjectNode root) {
        states.save(new GameStateRecord(1L, root.toString(), "test"));
    }

    private JsonNode playerNode(JsonNode root, String teamId, String playerId) {
        for (JsonNode team : root.path("teams")) {
            if (!teamId.equals(team.path("id").asText())) continue;
            for (JsonNode player : team.path("players"))
                if (playerId.equals(player.path("id").asText())) return player;
        }
        throw new AssertionError("player not found: " + teamId + "/" + playerId);
    }

    private <T> List<T> runConcurrent(int threads, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++)
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return task.call();
                }));
            ready.await(10, TimeUnit.SECONDS);
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) results.add(future.get(30, TimeUnit.SECONDS));
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
