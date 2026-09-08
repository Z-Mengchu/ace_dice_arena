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
    void squadWindowExpiryAutoRollsOnlyThatSquad() {
        ObjectNode root = state("ROLL");
        root.path("teams").forEach(team -> formSquads((ObjectNode) team));
        // 1 号小队窗口刚结束 500ms，2 号小队窗口还剩 500ms，全局截止仍在将来
        long now = System.currentTimeMillis();
        long go = now - 15_500L;
        root.put("rollGoAt", go);
        ArrayNode rollOpenAts = root.putArray("rollOpenAts");
        for (int k = 0; k < 6; k++) rollOpenAts.add(go + k * 1_000L);
        root.put("stageDeadlineAt", go + 20_000L);

        assertThat(service().expireRoll(root, now)).isTrue();

        assertThat(root.path("stage").asText()).isEqualTo("ROLL");
        root.path("teams").forEach(team -> {
            team.path("squads").get(0).forEach(idNode -> {
                JsonNode player = findPlayer(team, idNode.asText());
                assertThat(player.path("dice").asInt()).isBetween(1, 6);
                assertThat(player.path("rollTs").asLong()).isEqualTo(go + 1_000L);
                assertThat(player.path("autoRolled").asBoolean()).isTrue();
            });
            for (int k = 1; k < 6; k++)
                team.path("squads").get(k).forEach(idNode ->
                        assertThat(findPlayer(team, idNode.asText()).has("dice")).isFalse());
        });
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

    @Test
    void blindBoxTimeoutForfeitsUnopenedBoxesAndMovesToTactics() {
        ObjectNode root = state("BLIND_BOX");
        root.put("stageDeadlineAt", System.currentTimeMillis() - 1);
        // 全员已掷骰但都还没开盲盒
        root.path("teams").forEach(team -> team.path("players").forEach(playerNode -> {
            ObjectNode player = (ObjectNode) playerNode;
            player.put("dice", 3).put("diceFinal", 3).put("rollTs", 1_000L);
        }));
        // t1 一名队员已经自己开过
        ObjectNode opened = (ObjectNode) root.path("teams").get(0).path("players").get(0);
        opened.put("blindBox", 3).put("blindBoxOpened", true);

        assertThat(service().expireBlindBox(root, System.currentTimeMillis())).isTrue();

        assertThat(opened.path("blindBox").asInt()).isEqualTo(3);
        // 未开者按放弃处理：不写入盲盒字段，个人点数按 0 计
        root.path("teams").forEach(team -> team.path("players").forEach(player -> {
            if (player == opened) return;
            assertThat(player.has("blindBox")).isFalse();
            assertThat(player.has("blindBoxOpened")).isFalse();
        }));
        assertThat(root.path("stage").asText()).isEqualTo("TACTICS");
        assertThat(root.path("stageDeadlineAt").asLong()).isGreaterThan(System.currentTimeMillis());
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
        assertThat(service.expireBlindBox(root, System.currentTimeMillis())).isFalse();

        root.put("stage", "TACTICS");
        assertThat(service.expireTactics(root, System.currentTimeMillis())).isFalse();

        ObjectNode battleRoot = battleState(1);
        assertThat(service.expireGuesses(battleRoot, System.currentTimeMillis())).isFalse();
        assertThat(service.completeRoundReveals(battleRoot, System.currentTimeMillis())).isFalse();
        assertThat(match(battleRoot).path("rounds")).isEmpty();
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
                mock(com.acedicearena.repository.PlayerBlindBoxRepository.class));
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
