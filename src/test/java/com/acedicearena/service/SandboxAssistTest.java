package com.acedicearena.service;

import com.acedicearena.domain.GameStateRecord;
import com.acedicearena.domain.UserAccount;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 双人沙盘：指定真人顶替沙盘队员后，其余位置全部由系统按全自动流程代打。 */
class SandboxAssistTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void sandboxAssignmentReplacesThePlayerAndAutoElectsCaptainsEverywhereElse() {
        ObjectNode root = votingRoot();
        GameStateRecord record = new GameStateRecord(1L, root.toString(), "system");
        ParallelTournamentService service = service(record);
        UserAccount player = user(5001L, "player_a", "t3");
        UserAccount replaced = user(9001L, "__arena_test_061", "t3");

        service.applySandboxAssignments(List.of(
                new ParallelTournamentService.SandboxAssignment(player, replaced, "t3", "front")));

        ObjectNode saved = read(record);
        assertThat(saved.path("sandboxPlayers")).hasSize(1);
        assertThat(saved.at("/sandboxPlayers/0/username").asText()).isEqualTo("player_a");
        ObjectNode t3 = team(saved, "t3");
        assertThat(t3.at("/roles/captain").isMissingNode()).isTrue();   // 等沙盘玩家自己投出最后一票
        assertThat(t3.at("/roleVotes/u9002").asText()).isEqualTo("u5001");
        Set<String> t3Ids = new HashSet<>();
        t3.path("players").forEach(p -> t3Ids.add(p.path("id").asText()));
        assertThat(t3Ids).contains("u5001").doesNotContain("u9001");
        for (JsonNode team : saved.path("teams")) {
            if ("t3".equals(team.path("id").asText())) continue;
            assertThat(team.at("/roles/captain").asText())
                    .as("%s 应已自动选出队长", team.path("id").asText())
                    .isEqualTo(team.path("players").get(0).path("id").asText());
        }
        assertThat(saved.path("stage").asText()).isEqualTo("CAPTAIN_VOTE");
    }

    @Test
    void sandboxPlayersOwnVoteCompletesTheElectionAndMovesToSquadForm() {
        ObjectNode root = votingRoot();
        GameStateRecord record = new GameStateRecord(1L, root.toString(), "system");
        ParallelTournamentService service = service(record);
        UserAccount player = user(5001L, "player_a", "t3");
        service.applySandboxAssignments(List.of(
                new ParallelTournamentService.SandboxAssignment(player, user(9001L, "__arena_test_061", "t3"), "t3", "front")));

        ObjectNode voted = read(record);
        service.submitRoleVote(voted, player, List.of("u5001"));

        assertThat(team(voted, "t3").at("/roles/captain").asText()).isEqualTo("u5001");
        assertThat(voted.path("stage").asText()).isEqualTo("SQUAD_FORM");
    }

    @Test
    void midGameAssignmentHandsOverSquadSeatAndRoundData() {
        ObjectNode root = votingRoot();
        root.put("stage", "BATTLE");
        ObjectNode t3 = team(root, "t3");
        t3.putArray("squads").addArray().add("u9001").add("u9002");
        ObjectNode replacedNode = (ObjectNode) t3.path("players").get(0);
        replacedNode.put("dice", 5).put("diceFinal", 5).put("rollTs", 999L)
                .put("blindBox", 2).put("blindBoxOpened", true).put("autoRolled", true);
        GameStateRecord record = new GameStateRecord(1L, root.toString(), "system");
        ParallelTournamentService service = service(record);
        UserAccount player = user(5001L, "player_a", "t3");

        service.applySandboxAssignments(List.of(
                new ParallelTournamentService.SandboxAssignment(player, user(9001L, "__arena_test_061", "t3"), "t3", "front")));

        ObjectNode saved = read(record);
        assertThat(team(saved, "t3").at("/squads/0/0").asText()).isEqualTo("u5001");
        ObjectNode node = null;
        for (JsonNode candidate : team(saved, "t3").path("players"))
            if ("u5001".equals(candidate.path("id").asText())) node = (ObjectNode) candidate;
        assertThat(node).isNotNull();
        assertThat(node.path("diceFinal").asInt()).isEqualTo(5);
        assertThat(node.path("rollTs").asLong()).isEqualTo(999L);
        assertThat(node.path("blindBox").asInt()).isEqualTo(2);
    }

    @Test
    void sandboxActionsShareTheRegularDispatchTable() {
        ObjectNode root = votingRoot();
        GameStateRecord record = new GameStateRecord(1L, root.toString(), "system");
        ParallelTournamentService service = service(record);
        UserAccount player = user(5001L, "player_a", "t3");
        service.applySandboxAssignments(List.of(
                new ParallelTournamentService.SandboxAssignment(player, user(9001L, "__arena_test_061", "t3"), "t3", "front")));

        ObjectNode saved = read(record);
        service.dispatchPlayerAction(saved, player, "role-vote", List.of("u9002"));
        assertThat(team(saved, "t3").at("/roleVotes/u5001").asText()).isEqualTo("u9002");
        assertThatThrownBy(() -> service.dispatchPlayerAction(saved, player, "prophet", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("未知的玩家操作");
    }

    /* ---------- 构造 ---------- */

    private ParallelTournamentService service(GameStateRecord record) {
        com.acedicearena.repository.GameStateRepository states =
                mock(com.acedicearena.repository.GameStateRepository.class);
        when(states.findLockedById(1L)).thenReturn(Optional.of(record));
        var users = mock(com.acedicearena.repository.UserAccountRepository.class);
        var blindBoxes = mock(com.acedicearena.repository.PlayerBlindBoxRepository.class);
        var events = mock(LobbyEventService.class);
        var txManager = mock(org.springframework.transaction.PlatformTransactionManager.class);
        return new ParallelTournamentService(states, users,
                mock(com.acedicearena.repository.PerformanceRecordRepository.class),
                mock(com.acedicearena.repository.GameControlRepository.class), mapper,
                events, 6_000L,
                mock(com.acedicearena.repository.BattleReportRepository.class),
                mock(com.acedicearena.repository.MatchReportRepository.class),
                blindBoxes,
                mock(BlindBoxRoundService.class),
                txManager);
    }

    private UserAccount user(long id, String username, String teamId) {
        UserAccount user = new UserAccount(username, "玩家" + id, "销售部", "USER", "hash", "salt");
        ReflectionTestUtils.setField(user, "id", id);
        user.assignTeam(teamId);
        return user;
    }

    /** 8 队各 2 人的队长投票现场；t3 的两名队员是 u9001/u9002。 */
    private ObjectNode votingRoot() {
        ObjectNode root = mapper.createObjectNode();
        root.put("mode", "parallel"); root.put("stage", "CAPTAIN_VOTE");
        com.fasterxml.jackson.databind.node.ArrayNode teams = root.putArray("teams");
        for (int t = 0; t < 8; t++) {
            String teamId = "t" + (t + 1);
            ObjectNode team = teams.addObject();
            team.put("id", teamId); team.put("name", teamId);
            team.putObject("roles"); team.putObject("roleVotes");
            team.put("roleVoteDeadlineAt", System.currentTimeMillis() + 19_000L);
            long base = "t3".equals(teamId) ? 9_000L : 100L * (t + 1);
            com.fasterxml.jackson.databind.node.ArrayNode players = team.putArray("players");
            players.addObject().put("id", "u" + (base + 1)).put("name", teamId + "队员1").put("managed", false);
            players.addObject().put("id", "u" + (base + 2)).put("name", teamId + "队员2").put("managed", false);
        }
        root.putObject("matches");
        return root;
    }

    private ObjectNode read(GameStateRecord record) {
        try { return (ObjectNode) mapper.readTree(record.getContent()); }
        catch (Exception e) { throw new AssertionError(e); }
    }

    private ObjectNode team(ObjectNode root, String teamId) {
        for (JsonNode team : root.path("teams")) if (teamId.equals(team.path("id").asText())) return (ObjectNode) team;
        throw new AssertionError("team not found: " + teamId);
    }
}
