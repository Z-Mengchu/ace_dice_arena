package com.acedicearena;

import com.acedicearena.domain.UserAccount;
import com.acedicearena.domain.GameStateRecord;
import com.acedicearena.repository.UserAccountRepository;
import com.acedicearena.service.AdminTestModeService;
import com.acedicearena.service.LobbyService;
import com.acedicearena.service.PlayerActionService;
import com.acedicearena.service.ParallelTournamentService;
import com.acedicearena.repository.GameStateRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {"app.test-mode.enabled=true", "app.game.result-display-ms=1"})
class AdminTestModeServiceTest {
    @Autowired AdminTestModeService testMode;
    @Autowired UserAccountRepository users;
    @Autowired LobbyService lobby;
    @Autowired PlayerActionService playerActions;
    @Autowired ParallelTournamentService tournament;
    @Autowired GameStateRepository states;
    @Autowired ObjectMapper mapper;

    @Test
    void adminCanRunWholeTournamentWithoutPlayerAccountsLoggingIn() throws Exception {
        users.deleteAll(users.findAll().stream().filter(user -> "USER".equals(user.getRole())).toList());
        users.save(new UserAccount("real_user", "真实用户", "业务部", "USER", "hash", "00"));

        var prepared = testMode.prepare();
        assertThat(prepared.enabled()).isTrue();
        assertThat(prepared.active()).isTrue();
        assertThat(prepared.testUsers()).isEqualTo(LobbyService.PARTICIPANT_COUNT);
        assertThat(prepared.phase()).isEqualTo("PLAYING");
        var ordinaryView = lobby.view("real_user");
        assertThat(ordinaryView.phase()).isEqualTo("PREPARING");
        assertThat(ordinaryView.teams()).flatExtracting(LobbyService.TeamView::members)
                .noneMatch(user -> user.username().startsWith(AdminTestModeService.USERNAME_PREFIX));
        var playerView = testMode.playerView("t3");
        assertThat(playerView.phase()).isEqualTo("PLAYING");
        assertThat(playerView.me().teamId()).isEqualTo("t3");
        assertThat(playerView.teams().stream().filter(team -> "t3".equals(team.id())).findFirst().orElseThrow().members())
                .hasSize(LobbyService.TEAM_SIZE).allMatch(LobbyService.UserView::ready);

        JsonNode voting = readRoot();
        assertThat(voting.path("stage").asText()).isEqualTo("CAPTAIN_VOTE");
        for (JsonNode team : voting.path("teams")) {
            assertThat(team.path("roleVoteDeadlineAt").asLong()).isGreaterThan(System.currentTimeMillis() - 1_000L);
            assertThat(team.path("rerollQuota").asInt())
                    .isEqualTo(team.path("gmv").decimalValue()
                            .divide(java.math.BigDecimal.valueOf(100_000L), 0, java.math.RoundingMode.FLOOR).intValue());
        }

        testMode.advance("admin");
        JsonNode squadForm = readRoot();
        assertThat(squadForm.path("stage").asText()).isEqualTo("SQUAD_FORM");
        squadForm.path("teams").forEach(team -> assertThat(team.at("/roles/captain").asText()).isNotBlank());

        testMode.advance("admin");
        JsonNode rolling = readRoot();
        assertThat(rolling.path("stage").asText()).isEqualTo("ROLL");
        rolling.path("teams").forEach(team -> {
            assertThat(team.path("squads")).hasSize(6);
            team.path("squads").forEach(squad -> assertThat(squad).hasSize(5));
        });

        testMode.advance("admin");
        JsonNode blindBox = readRoot();
        assertThat(blindBox.path("stage").asText()).isEqualTo("BLIND_BOX");
        blindBox.path("teams").forEach(team -> team.path("players").forEach(player -> {
            assertThat(player.path("dice").asInt()).isBetween(1, 6);
            assertThat(player.path("autoRolled").asBoolean()).isTrue();
        }));

        testMode.advance("admin");
        assertThat(readRoot().path("stage").asText()).isEqualTo("TACTICS");
        testMode.advance("admin");
        JsonNode battle = readRoot();
        assertThat(battle.path("stage").asText()).isEqualTo("BATTLE");
        battle.path("matches").forEach(match -> {
            if ("active".equals(match.path("status").asText())) {
                assertThat(match.path("phase").asText()).isEqualTo("BATTLE");
                assertThat(match.path("roundPhase").asText()).isEqualTo("GUESS");
            }
        });

        var current = testMode.status();
        for (int step = 0; step < 120 && !"FINISHED".equals(current.phase()); step++) {
            current = testMode.advance("admin");
        }
        assertThat(current.phase()).isEqualTo("FINISHED");
        assertThat(current.champion()).startsWith("t");
        JsonNode dayOne = readRoot();
        assertThat(dayOne.path("day").asInt()).isEqualTo(1);
        assertThat(dayOne.at("/dayResults/day1/teams")).hasSize(8);
        assertThat(dayOne.at("/dayResults/day1/teams/0/players")).hasSize(LobbyService.TEAM_SIZE);
        assertThat(dayOne.at("/dayResults/day1/teams/0/players/0/name").asText()).isNotBlank();
        assertThat(dayOne.at("/dayResults/day1/teams/0/players/0/participated").asBoolean()).isTrue();
        dayOne.at("/dayResults/day1/matches").forEach(match -> {
            assertThat(match.path("winner").asText()).isNotBlank();
            assertThat(match.path("winsA").asInt() + match.path("winsB").asInt()).isLessThanOrEqualTo(6);
        });
        dayOne.path("matches").forEach(match -> {
            assertThat(match.path("status").asText()).isEqualTo("done");
            assertThat(match.path("rounds")).hasSize(6);
        });

        var secondDay = testMode.prepare();
        assertThat(secondDay.phase()).isEqualTo("PLAYING");
        JsonNode secondDayStart = readRoot();
        assertThat(secondDayStart.path("day").asInt()).isEqualTo(2);
        assertThat(secondDayStart.at("/dayResults/day1").isObject()).isTrue();
        for (int step = 0; step < 120 && !"FINISHED".equals(secondDay.phase()); step++) secondDay = testMode.advance("admin");
        assertThat(secondDay.phase()).isEqualTo("FINISHED");
        JsonNode bothDays = readRoot();
        assertThat(bothDays.at("/dayResults/day1").isObject()).isTrue();
        assertThat(bothDays.at("/dayResults/day2").isObject()).isTrue();
        assertThat(bothDays.at("/overallResult/standings")).hasSize(8);
        assertThat(bothDays.at("/overallResult/status").asText()).isEqualTo("DECIDED");
        assertThat(bothDays.path("overallChampion").asText())
                .isEqualTo(bothDays.at("/overallResult/champion").asText());
        // 总冠军只能出自两天的日冠军
        assertThat(bothDays.at("/overallResult/champion").asText())
                .isIn(bothDays.at("/dayResults/day1/champion").asText(),
                        bothDays.at("/dayResults/day2/champion").asText());
        for (int index = 1; index < bothDays.at("/overallResult/standings").size(); index++) {
            JsonNode previous = bothDays.at("/overallResult/standings").get(index - 1);
            JsonNode currentTeam = bothDays.at("/overallResult/standings").get(index);
            assertThat(previous.path("totalMatchWins").asInt()).isGreaterThanOrEqualTo(currentTeam.path("totalMatchWins").asInt());
            if (previous.path("totalMatchWins").asInt() == currentTeam.path("totalMatchWins").asInt())
                assertThat(previous.path("totalGmv").decimalValue())
                        .isGreaterThanOrEqualTo(currentTeam.path("totalGmv").decimalValue());
        }

        lobby.resetTwoDayTournament();
        assertThat(states.findById(1L)).isEmpty();

        var cleaned = testMode.cleanup();
        assertThat(cleaned.active()).isFalse();
        assertThat(cleaned.testUsers()).isZero();
        assertThat(cleaned.phase()).isEqualTo("PREPARING");
    }

    @Test
    void assignedSandboxPlayersCanDriveThroughTheNewFlow() throws Exception {
        testMode.cleanup();
        users.deleteAll(users.findAll().stream().filter(user -> "USER".equals(user.getRole())).toList());
        UserAccount first = users.save(new UserAccount("player_a", "测试玩家甲", "业务部", "USER", "hash", "00"));
        UserAccount second = users.save(new UserAccount("player_b", "测试玩家乙", "技术部", "USER", "hash", "00"));
        testMode.prepare();

        var assigned = testMode.assignSandboxPlayers(first.getUsername(), "t3", "front",
                second.getUsername(), "t4", "back");
        assertThat(assigned.sandboxPlayers()).extracting(AdminTestModeService.SandboxPlayerStatus::username)
                .containsExactly("player_a", "player_b");
        assertThatThrownBy(() -> testMode.advance("admin")).hasMessageContaining("指定玩家推进");

        JsonNode voting = readRoot();
        assertThat(voting.path("stage").asText()).isEqualTo("CAPTAIN_VOTE");
        // 没有沙盘玩家的队伍已经由系统代投自动产生队长
        voting.path("teams").forEach(team -> {
            if (List.of("t3", "t4").contains(team.path("id").asText()))
                assertThat(team.at("/roles").has("captain")).as(team.path("id").asText()).isFalse();
            else
                assertThat(team.at("/roles").has("captain")).as(team.path("id").asText()).isTrue();
        });
        var playerLobby = lobby.view("player_a");
        assertThat(playerLobby.phase()).isEqualTo("PLAYING");
        assertThat(playerLobby.me().teamId()).isEqualTo("t3");
        assertThat(lobby.view("player_b").me().teamId()).isEqualTo("t4");

        String firstId = "u" + first.getId(), secondId = "u" + second.getId();
        playerActions.submit("player_a", "role-vote", List.of(firstId));
        assertThat(readRoot().path("stage").asText()).isEqualTo("CAPTAIN_VOTE");
        playerActions.submit("player_b", "role-vote", List.of(secondId));
        JsonNode squadForm = readRoot();
        assertThat(squadForm.path("stage").asText()).isEqualTo("SQUAD_FORM");
        // 沙盘玩家被本队自动票选为队长
        assertThat(team(squadForm, "t3").at("/roles/captain").asText()).isEqualTo(firstId);
        assertThat(team(squadForm, "t4").at("/roles/captain").asText()).isEqualTo(secondId);

        playerActions.submit("player_a", "squad-form", rosterIds(team(squadForm, "t3")));
        playerActions.submit("player_b", "squad-form", rosterIds(team(readRoot(), "t4")));
        JsonNode formed = readRoot();
        assertThat(formed.path("stage").asText()).isEqualTo("SQUAD_FORM");   // 其余队伍等系统兜底
        assertThat(team(formed, "t3").path("squads")).hasSize(6);
        assertThat(team(formed, "t4").path("squads")).hasSize(6);

        for (int step = 0; step < 200; step++) {
            JsonNode root = readRoot();
            if (root.hasNonNull("champion")) break;
            forceDeadlinesPast();
            // 盲盒阶段的截止由内存运行态持有：走管理员强制推进同步截止并立即统一关闭，不能只改数据库
            if ("BLIND_BOX".equals(root.path("stage").asText())) {
                for (JsonNode match : root.path("matches")) {
                    if ("active".equals(match.path("status").asText())) {
                        tournament.forceMatch(match.path("id").asText());
                        break;
                    }
                }
            }
            tournament.advanceDueResults();
        }
        JsonNode finished = readRoot();
        assertThat(finished.hasNonNull("champion")).as("200 次扫描内必须产生日冠军").isTrue();
        assertThat(testMode.status().phase()).isEqualTo("FINISHED");
        // 两名沙盘玩家同组相对，g2 就是他们对阵的那一场
        JsonNode g2 = finished.path("matches").path("g2");
        assertThat(g2.path("status").asText()).isEqualTo("done");
        assertThat(g2.path("rounds")).hasSize(6);
        finished.path("matches").forEach(match ->
                assertThat(match.path("rounds")).as(match.path("id").asText()).hasSize(6));
        // 沙盘玩家全程由系统代打：掷骰齐全且标记为代掷；盲盒无人操作按放弃处理，不留盲盒字段
        JsonNode firstNode = null;
        for (JsonNode player : team(finished, "t3").path("players"))
            if (firstId.equals(player.path("id").asText())) firstNode = player;
        assertThat(firstNode).isNotNull();
        assertThat(firstNode.path("diceFinal").isNumber()).isTrue();
        assertThat(firstNode.has("blindBox")).isFalse();
        assertThat(firstNode.path("autoRolled").asBoolean()).isTrue();
        assertThat(finished.at("/dayResults/day1").isObject()).isTrue();

        testMode.cleanup();
        assertThat(users.findByUsername("player_a").orElseThrow().getTeamId()).isNull();
        assertThat(users.findByUsername("player_b").orElseThrow().getTeamId()).isNull();
    }

    private JsonNode readRoot() throws Exception {
        return mapper.readTree(states.findById(1L).orElseThrow().getContent());
    }

    /** 把所有等待中的截止时间提前到此刻之前，模拟"没有任何人操作"的超时兜底路径。 */
    private void forceDeadlinesPast() throws Exception {
        GameStateRecord record = states.findById(1L).orElseThrow();
        ObjectNode root = (ObjectNode) mapper.readTree(record.getContent());
        long past = System.currentTimeMillis() - 1;
        if (root.has("stageDeadlineAt")) root.put("stageDeadlineAt", past);
        root.path("teams").forEach(team -> {
            if (team.has("roleVoteDeadlineAt")) ((ObjectNode) team).put("roleVoteDeadlineAt", past);
        });
        root.path("matches").forEach(match -> {
            ObjectNode node = (ObjectNode) match;
            for (String field : List.of("guessDeadlineAt", "revealUntil", "resultReadyAt"))
                if (node.has(field)) node.put(field, past);
        });
        record.update(root.toString(), "test");
        states.save(record);
    }

    private JsonNode team(JsonNode root, String teamId) {
        for (JsonNode candidate : root.path("teams")) if (teamId.equals(candidate.path("id").asText())) return candidate;
        throw new AssertionError("team not found: " + teamId);
    }

    private List<String> rosterIds(JsonNode team) {
        List<String> ids = new ArrayList<>();
        team.path("players").forEach(player -> ids.add(player.path("id").asText()));
        return ids;
    }
}
