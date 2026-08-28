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

        JsonNode roleVoting = mapper.readTree(states.findById(1L).orElseThrow().getContent());
        assertThat(roleVoting.path("stage").asText()).isEqualTo("ROLE_VOTE");
        roleVoting.path("teams").forEach(team -> assertThat(team.path("roleVoteStage").asText()).isEqualTo("captain"));
        for (JsonNode team : roleVoting.path("teams"))
            assertThat(team.path("accumulationQuota").asInt())
                    .isEqualTo(team.path("gmv").decimalValue().divide(java.math.BigDecimal.valueOf(100_000L), 0, java.math.RoundingMode.FLOOR).intValue());

        testMode.advance("admin");
        JsonNode accumulation = mapper.readTree(states.findById(1L).orElseThrow().getContent());
        assertThat(accumulation.path("stage").asText()).isEqualTo("ACCUMULATION");
        var accumulated = testMode.advance("admin");
        assertThat(accumulated.phase()).isEqualTo("PLAYING");
        JsonNode attack = mapper.readTree(states.findById(1L).orElseThrow().getContent());
        assertThat(attack.path("stage").asText()).isEqualTo("ATTACK");
        for (JsonNode team : attack.path("teams"))
            assertThat(team.path("accumulationRolled").asInt()).isEqualTo(team.path("accumulationQuota").asInt());

        var current = accumulated;
        for (int step = 0; step < 120 && !"FINISHED".equals(current.phase()); step++) {
            current = testMode.advance("admin");
        }
        assertThat(current.phase()).isEqualTo("FINISHED");
        assertThat(current.champion()).startsWith("t");
        JsonNode dayOne = mapper.readTree(states.findById(1L).orElseThrow().getContent());
        assertThat(dayOne.path("day").asInt()).isEqualTo(1);
        assertThat(dayOne.at("/dayResults/day1/teams").size()).isEqualTo(8);
        assertThat(dayOne.at("/dayResults/day1/teams/0/players").size()).isEqualTo(LobbyService.TEAM_SIZE);
        assertThat(dayOne.at("/dayResults/day1/teams/0/players/0/name").asText()).isNotBlank();
        assertThat(dayOne.at("/dayResults/day1/teams/0/players/0/participated").asBoolean()).isTrue();
        assertThat(dayOne.at("/dayResults/day1/teams/0/players/0/standIn").isBoolean()).isTrue();
        dayOne.at("/dayResults/day1/matches").forEach(match -> {
            assertThat(match.path("winsA").asInt() + match.path("winsB").asInt()).isEqualTo(1);
        });

        var secondDay = testMode.prepare();
        assertThat(secondDay.phase()).isEqualTo("PLAYING");
        JsonNode secondDayStart = mapper.readTree(states.findById(1L).orElseThrow().getContent());
        assertThat(secondDayStart.path("day").asInt()).isEqualTo(2);
        assertThat(secondDayStart.at("/dayResults/day1").isObject()).isTrue();
        for (int step = 0; step < 120 && !"FINISHED".equals(secondDay.phase()); step++) secondDay = testMode.advance("admin");
        assertThat(secondDay.phase()).isEqualTo("FINISHED");
        JsonNode bothDays = mapper.readTree(states.findById(1L).orElseThrow().getContent());
        assertThat(bothDays.at("/dayResults/day1").isObject()).isTrue();
        assertThat(bothDays.at("/dayResults/day2").isObject()).isTrue();
        assertThat(bothDays.at("/overallResult/standings").size()).isEqualTo(8);
        assertThat(bothDays.path("overallChampion").asText())
                .isEqualTo(bothDays.at("/overallResult/champion").asText())
                .isEqualTo(bothDays.at("/overallResult/standings/0/id").asText());
        for (int index = 1; index < bothDays.at("/overallResult/standings").size(); index++) {
            JsonNode previous = bothDays.at("/overallResult/standings").get(index - 1);
            JsonNode currentTeam = bothDays.at("/overallResult/standings").get(index);
            assertThat(previous.path("totalMatchWins").asInt()).isGreaterThanOrEqualTo(currentTeam.path("totalMatchWins").asInt());
            if (previous.path("totalMatchWins").asInt() == currentTeam.path("totalMatchWins").asInt())
                assertThat(previous.path("totalGrowthRate").asDouble()).isGreaterThanOrEqualTo(currentTeam.path("totalGrowthRate").asDouble());
        }

        lobby.resetTwoDayTournament();
        assertThat(states.findById(1L)).isEmpty();

        var cleaned = testMode.cleanup();
        assertThat(cleaned.active()).isFalse();
        assertThat(cleaned.testUsers()).isZero();
        assertThat(cleaned.phase()).isEqualTo("PREPARING");
    }

    @Test
    void twoOpposingRealPlayersCanLeadTournamentThroughAllRoles() throws Exception {
        testMode.cleanup();
        users.deleteAll(users.findAll().stream().filter(user -> "USER".equals(user.getRole())).toList());
        UserAccount first = users.save(new UserAccount("player_a", "测试玩家甲", "技术部", "USER", "hash", "00"));
        UserAccount second = users.save(new UserAccount("player_b", "测试玩家乙", "业务部", "USER", "hash", "00"));
        testMode.prepare();

        var assigned = testMode.assignSandboxPlayers(first.getUsername(), "t3", "front",
                second.getUsername(), "t4", "back");
        assertThat(assigned.sandboxPlayers()).extracting(AdminTestModeService.SandboxPlayerStatus::username)
                .containsExactly("player_a", "player_b");
        assertThat(assigned.sandboxPlayers()).extracting(AdminTestModeService.SandboxPlayerStatus::identity)
                .containsExactly("front", "back");
        assertThatThrownBy(() -> testMode.advance("admin")).hasMessageContaining("指定玩家推进");

        JsonNode assignedDuringVoting = mapper.readTree(states.findById(1L).orElseThrow().getContent());
        assertThat(assignedDuringVoting.path("stage").asText()).isEqualTo("ROLE_VOTE");
        assignedDuringVoting.path("teams").forEach(team -> assertThat(team.has("roleVoteStage")).isTrue());

        var playerLobby = lobby.view("player_a");
        assertThat(playerLobby.phase()).isEqualTo("PLAYING");
        assertThat(playerLobby.me().teamId()).isEqualTo("t3");
        assertThat(playerLobby.teams().stream().filter(team -> "t3".equals(team.id())).findFirst().orElseThrow().members())
                .hasSize(LobbyService.TEAM_SIZE).anyMatch(member -> "player_a".equals(member.username()));
        assertThat(lobby.view("player_b").me().teamId()).isEqualTo("t4");

        completeRoleElection("player_a", "player_b");
        JsonNode accumulation = mapper.readTree(states.findById(1L).orElseThrow().getContent());
        assertThat(accumulation.path("stage").asText()).isEqualTo("ACCUMULATION");
        completeControlledAccumulation("player_a", "player_b");
        JsonNode elected = mapper.readTree(states.findById(1L).orElseThrow().getContent());
        assertThat(elected.path("stage").asText()).isEqualTo("ATTACK");
        assertThat(elected.at("/teams/2/roles/strategist").asText()).isNotBlank();
        assertThat(elected.at("/teams/3/roles/captain").asText()).isNotBlank();

        // 同步点击与王牌投手最终投骰已拆成两个独立动作，完整淘汰赛需要更多推进步数。
        for (int action = 0; action < 240; action++) {
            JsonNode root = mapper.readTree(states.findById(1L).orElseThrow().getContent());
            if (root.hasNonNull("champion")) break;
            JsonNode match = configuredMatchOrNull(root);
            if (match == null) {
                tournament.advanceDueResults();
                continue;
            }
            String phase = match.path("phase").asText();
            String teamA = match.path("a").asText(), teamB = match.path("b").asText();
            switch (phase) {
                case "PROPHET" -> {
                    String strategistA = configuredRoleUsername(root, teamA, "strategist");
                    if (strategistA != null && !match.at("/submitted/prophetA").asBoolean())
                        playerActions.submit(strategistA, "prophet", firstFive(root, teamB, false));
                    JsonNode current = activeConfiguredMatch(mapper.readTree(states.findById(1L).orElseThrow().getContent()));
                    String strategistB = configuredRoleUsername(root, teamB, "strategist");
                    if ("PROPHET".equals(current.path("phase").asText()) && strategistB != null
                            && !current.at("/submitted/prophetB").asBoolean())
                        playerActions.submit(strategistB, "prophet", firstFive(root, teamA, false));
                }
                case "LINEUP" -> {
                    String captainA = configuredRoleUsername(root, teamA, "captain");
                    if (captainA != null && !match.at("/submitted/lineupA").asBoolean())
                        playerActions.submit(captainA, "lineup", firstFive(root, teamA, true));
                    JsonNode current = activeConfiguredMatch(mapper.readTree(states.findById(1L).orElseThrow().getContent()));
                    String captainB = configuredRoleUsername(root, teamB, "captain");
                    if ("LINEUP".equals(current.path("phase").asText()) && captainB != null
                            && !current.at("/submitted/lineupB").asBoolean())
                        playerActions.submit(captainB, "lineup", firstFive(root, teamB, true));
                }
                case "CONFIRM_A", "CONFIRM_B" -> {
                    String side = phase.endsWith("A") ? "A" : "B";
                    String actingTeam = "A".equals(side) ? teamA : teamB;
                    if (!match.at("/sync/captain" + side).asBoolean()) {
                        String username = configuredRoleUsername(root, actingTeam, "captain");
                        playerActions.submit(username, "captain-ready", java.util.List.of());
                    } else {
                        String username = configuredRoleUsername(root, actingTeam, "pitcher");
                        playerActions.submit(username, "pitcher-ready", java.util.List.of());
                    }
                }
                case "ROLL_A", "ROLL_B" -> {
                    String side = "ROLL_A".equals(phase) ? "A" : "B";
                    String actingTeam = "A".equals(side) ? teamA : teamB;
                    String username = configuredRoleUsername(root, actingTeam, "pitcher");
                    java.util.Set<String> rolled = new java.util.HashSet<>();
                    match.at("/sandboxRolled/" + side).forEach(id -> rolled.add(id.asText()));
                    String roller = null;
                    for (JsonNode id : match.at("/lineups/" + side)) if (!rolled.contains(id.asText())) { roller = id.asText(); break; }
                    playerActions.submit(username, "sandbox-roll", java.util.List.of(roller));
                }
                case "PITCHER_ROLL_A", "PITCHER_ROLL_B" -> {
                    String side = "PITCHER_ROLL_A".equals(phase) ? "A" : "B";
                    String actingTeam = "A".equals(side) ? teamA : teamB;
                    String username = configuredRoleUsername(root, actingTeam, "pitcher");
                    playerActions.submit(username, "pitcher-roll", java.util.List.of());
                }
                case "ATTACKING" -> {
                    for (String side : java.util.List.of("A", "B")) {
                        JsonNode latestRoot = mapper.readTree(states.findById(1L).orElseThrow().getContent());
                        JsonNode latestMatch = activeConfiguredMatch(latestRoot);
                        if (latestMatch == null || !"ATTACKING".equals(latestMatch.path("phase").asText())) break;
                        String sidePhase = latestMatch.at("/sidePhases/" + side).asText();
                        String actingTeam = "A".equals(side) ? latestMatch.path("a").asText() : latestMatch.path("b").asText();
                        if ("PREPARING".equals(sidePhase)) {
                            java.util.Set<String> ready = new java.util.HashSet<>();
                            latestMatch.at("/sandboxReady/" + side).forEach(id -> ready.add(id.asText()));
                            String waiting = null;
                            for (JsonNode id : latestMatch.at("/lineups/" + side))
                                if (!ready.contains(id.asText())) { waiting = id.asText(); break; }
                            if (waiting != null)
                                playerActions.submit(configuredRoleUsername(latestRoot, actingTeam, "captain"), "sandbox-ready", java.util.List.of(waiting));
                            else
                                playerActions.submit(configuredRoleUsername(latestRoot, actingTeam, "captain"), "captain-command", java.util.List.of());
                        } else if ("COUNTDOWN".equals(sidePhase)) {
                            GameStateRecord countdownRecord = states.findById(1L).orElseThrow();
                            ObjectNode countdownRoot = (ObjectNode) mapper.readTree(countdownRecord.getContent());
                            ((ObjectNode) activeConfiguredMatch(countdownRoot).path("countdownUntil")).put(side, 0L);
                            countdownRecord.update(countdownRoot.toString(), "test");
                            states.save(countdownRecord);
                            tournament.advanceDueResults();
                        } else if ("ROLL".equals(sidePhase)) {
                            java.util.Set<String> rolled = new java.util.HashSet<>();
                            latestMatch.at("/sandboxRolled/" + side).forEach(id -> rolled.add(id.asText()));
                            String roller = null;
                            for (JsonNode id : latestMatch.at("/lineups/" + side))
                                if (!rolled.contains(id.asText())) { roller = id.asText(); break; }
                            playerActions.submit(configuredRoleUsername(latestRoot, actingTeam, "pitcher"), "sandbox-roll", java.util.List.of(roller));
                        } else if ("PITCHER_ROLL".equals(sidePhase)) {
                            playerActions.submit(configuredRoleUsername(latestRoot, actingTeam, "pitcher"), "pitcher-roll", java.util.List.of());
                        }
                    }
                }
                case "RESULT" -> {
                    Thread.sleep(5);
                    tournament.advanceDueResults();
                }
                default -> throw new AssertionError("unexpected phase " + phase);
            }
            JsonNode updated = mapper.readTree(states.findById(1L).orElseThrow().getContent());
            java.util.Set<String> activePhases = new java.util.HashSet<>();
            updated.path("matches").elements().forEachRemaining(candidate -> {
                if ("active".equals(candidate.path("status").asText())) activePhases.add(candidate.path("phase").asText());
            });
            assertThat(activePhases).hasSizeLessThanOrEqualTo(1);
        }

        var completed = testMode.status();
        assertThat(completed.phase()).isEqualTo("FINISHED");
        assertThat(completed.champion()).isIn("t3", "t4");
        JsonNode finishedState = mapper.readTree(states.findById(1L).orElseThrow().getContent());
        finishedState.path("matches").forEach(match -> {
            assertThat(match.path("round").asInt()).isEqualTo(1);
            assertThat(match.path("winsA").asInt() + match.path("winsB").asInt()).isEqualTo(1);
        });
        testMode.cleanup();
        assertThat(users.findByUsername("player_a").orElseThrow().getTeamId()).isNull();
        assertThat(users.findByUsername("player_b").orElseThrow().getTeamId()).isNull();
    }

    @Test
    void twoRealPlayersCanShareOneTeamForChatTesting() {
        testMode.cleanup();
        users.deleteAll(users.findAll().stream().filter(user -> "USER".equals(user.getRole())).toList());
        UserAccount chatA = new UserAccount("chat_a", "频道玩家甲", "业务部", "USER", "hash", "00");
        UserAccount chatB = new UserAccount("chat_b", "频道玩家乙", "技术部", "USER", "hash", "00");
        chatA.setPerformance(true, java.math.BigDecimal.valueOf(100_000L));
        chatB.setPerformance(false, java.math.BigDecimal.ZERO);
        users.saveAll(java.util.List.of(chatA, chatB));
        testMode.prepare();

        var assigned = testMode.assignSandboxPlayers("chat_a", "t1", "chat_b", "t1");
        assertThat(assigned.sandboxPlayers()).hasSize(2).allMatch(player -> "t1".equals(player.teamId()));
        assertThat(lobby.view("chat_a").teams().stream().filter(team -> "t1".equals(team.id())).findFirst().orElseThrow().members())
                .hasSize(LobbyService.TEAM_SIZE).extracting(LobbyService.UserView::username).contains("chat_a", "chat_b");
        assertThat(lobby.view("chat_b").phase()).isEqualTo("PLAYING");
        try {
            completeRoleElection("chat_a", "chat_b");
            completeControlledAccumulation("chat_a", "chat_b");
            JsonNode elected = mapper.readTree(states.findById(1L).orElseThrow().getContent());
            String chatAId = "u" + users.findByUsername("chat_a").orElseThrow().getId();
            String chatBId = "u" + users.findByUsername("chat_b").orElseThrow().getId();
            assertThat(elected.at("/teams/0/roles/captain").asText()).isEqualTo(chatAId);
            assertThat(elected.at("/teams/0/roles/pitcher").asText()).isEqualTo(chatBId);
            assertThat(elected.at("/teams/0/roles/strategist").asText()).isNotBlank();
            playerActions.submit("chat_a", "prophet", firstFive(elected, "t2", false));
            JsonNode predicted = mapper.readTree(states.findById(1L).orElseThrow().getContent());
            assertThat(predicted.at("/matches/g1/prophet/A").size()).isEqualTo(5);
            assertThatThrownBy(() -> playerActions.submit("chat_b", "prophet", firstFive(elected, "t2", false)))
                    .hasMessageContaining("只有当选军师");
            assertThatThrownBy(() -> playerActions.submit("chat_b", "lineup", firstFive(predicted, "t1", true)))
                    .hasMessageContaining("只有当选队长");
            playerActions.submit("chat_a", "lineup", firstFive(predicted, "t1", true));
            JsonNode lineupSelected = mapper.readTree(states.findById(1L).orElseThrow().getContent());
            assertThat(lineupSelected.at("/matches/g1/lineups/A").size()).isEqualTo(5);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        testMode.cleanup();
    }

    @Test
    void roleVotingUsesThreeTwentySecondDeadlinesAndLineupBelongsToCaptain() throws Exception {
        testMode.cleanup();
        testMode.prepare();
        var record = states.findById(1L).orElseThrow();
        var root = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(record.getContent());
        long now = System.currentTimeMillis();
        for (String role : java.util.List.of("captain", "strategist", "pitcher")) {
            final long roleVoteNow = now;
            root.path("teams").forEach(team -> {
                assertThat(team.path("roleVoteStage").asText()).isEqualTo(role);
                assertThat(team.path("roleVoteDeadlineAt").asLong()).isBetween(roleVoteNow, roleVoteNow + 20_500L);
                ((com.fasterxml.jackson.databind.node.ObjectNode) team).put("roleVoteDeadlineAt", roleVoteNow - 1);
            });
            record.update(root.toString(), "test"); states.saveAndFlush(record);
            tournament.advanceDueResults();
            root = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(states.findById(1L).orElseThrow().getContent());
            root.path("teams").forEach(team -> assertThat(team.at("/roles/" + role).asText()).isNotBlank());
            record = states.findById(1L).orElseThrow();
            now = System.currentTimeMillis();
        }
        root.path("teams").forEach(team -> assertThat(team.path("roleVoteStage").asText()).isEqualTo("complete"));
        assertThat(root.path("stage").asText()).isEqualTo("ACCUMULATION");

        testMode.advance("admin");
        root = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(states.findById(1L).orElseThrow().getContent());
        assertThat(root.path("stage").asText()).isEqualTo("ATTACK");

        testMode.advance("admin");
        root = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(states.findById(1L).orElseThrow().getContent());
        root.path("matches").forEach(match -> {
            if ("active".equals(match.path("status").asText())) {
                assertThat(match.path("phase").asText()).isEqualTo("LINEUP");
                assertThat(match.has("lineupVoteDeadlineAt")).isFalse();
            }
        });

        testMode.advance("admin");
        root = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(states.findById(1L).orElseThrow().getContent());
        root.path("matches").forEach(match -> {
            if ("active".equals(match.path("status").asText())) {
                assertThat(match.path("phase").asText()).isEqualTo("ATTACKING");
                assertThat(match.at("/sidePhases/A").asText()).isEqualTo("PREPARING");
                assertThat(match.at("/sidePhases/B").asText()).isEqualTo("PREPARING");
                assertThat(match.at("/lineups/A").size()).isEqualTo(5);
                assertThat(match.at("/lineups/B").size()).isEqualTo(5);
            }
        });
        testMode.cleanup();
    }

    private String configuredRoleUsername(JsonNode root, String teamId, String role) {
        JsonNode team = null;
        for (JsonNode candidate : root.path("teams")) if (teamId.equals(candidate.path("id").asText())) team = candidate;
        if (team == null) return null;
        String playerId = team.at("/roles/" + role).asText(null);
        for (JsonNode player : root.path("sandboxPlayers"))
            if (playerId != null && playerId.equals(player.path("playerId").asText())) return player.path("username").asText();
        for (JsonNode player : root.path("sandboxPlayers"))
            if (teamId.equals(player.path("teamId").asText())) return player.path("username").asText();
        return null;
    }

    private void completeControlledAccumulation(String... usernames) throws Exception {
        for (int step = 0; step < 500; step++) {
            JsonNode root = mapper.readTree(states.findById(1L).orElseThrow().getContent());
            if (!"ACCUMULATION".equals(root.path("stage").asText())) return;
            boolean submitted = false;
            for (String username : usernames) {
                String teamId = users.findByUsername(username).orElseThrow().getTeamId();
                JsonNode team = null;
                for (JsonNode candidate : root.path("teams")) if (teamId.equals(candidate.path("id").asText())) team = candidate;
                if (team != null && team.path("accumulationRolled").asInt() < team.path("accumulationQuota").asInt()) {
                    playerActions.submit(username, "accumulation-roll", java.util.List.of());
                    submitted = true;
                    break;
                }
            }
            if (!submitted) throw new AssertionError("controlled accumulation did not advance");
        }
        throw new AssertionError("accumulation did not finish");
    }

    private JsonNode activeConfiguredMatch(JsonNode root) {
        JsonNode match = configuredMatchOrNull(root);
        if (match != null) return match;
        throw new AssertionError("configured players have no active match");
    }

    private JsonNode configuredMatchOrNull(JsonNode root) {
        try { return activeMatch(root, "t3"); }
        catch (AssertionError ignored) {
            try { return activeMatch(root, "t4"); }
            catch (AssertionError alsoIgnored) { return null; }
        }
    }

    private JsonNode activeMatch(JsonNode root, String teamId) {
        var iterator = root.path("matches").elements();
        while (iterator.hasNext()) {
            JsonNode match = iterator.next();
            if ("active".equals(match.path("status").asText())
                    && (teamId.equals(match.path("a").asText()) || teamId.equals(match.path("b").asText()))) return match;
        }
        throw new AssertionError("solo team has no active match");
    }

    private java.util.List<String> firstFive(JsonNode root, String teamId, boolean needBack) {
        JsonNode team = null;
        for (JsonNode candidate : root.path("teams")) if (teamId.equals(candidate.path("id").asText())) team = candidate;
        if (team == null) throw new AssertionError("team not found");
        java.util.List<String> selected = new java.util.ArrayList<>();
        if (needBack) for (JsonNode player : team.path("players")) if ("back".equals(player.path("role").asText())) {
            selected.add(player.path("id").asText()); break;
        }
        for (JsonNode player : team.path("players")) {
            String id = player.path("id").asText();
            if (!selected.contains(id) && selected.size() < 5) selected.add(id);
        }
        return selected;
    }

    private void completeRoleElection(String... usernames) throws Exception {
        for (int step = 0; step < 20; step++) {
            JsonNode root = mapper.readTree(states.findById(1L).orElseThrow().getContent());
            boolean complete = true, submitted = false;
            for (String username : usernames) {
                var user = users.findByUsername(username).orElseThrow();
                JsonNode team = team(root, user.getTeamId());
                if ("complete".equals(team.path("roleVoteStage").asText())) continue;
                complete = false;
                String voterId = "u" + user.getId();
                String stage = team.path("roleVoteStage").asText("captain");
                if (!team.at("/roleVotes/" + stage).has(voterId)) {
                    playerActions.submit(username, "role-vote", roleChoice(root, user.getTeamId()));
                    submitted = true;
                }
            }
            if (complete) return;
            if (!submitted) throw new AssertionError("role election did not advance");
        }
        throw new AssertionError("role election did not finish");
    }

    private JsonNode team(JsonNode root, String teamId) {
        for (JsonNode candidate : root.path("teams")) if (teamId.equals(candidate.path("id").asText())) return candidate;
        throw new AssertionError("team not found");
    }

    private java.util.List<String> roleChoice(JsonNode root, String teamId) {
        JsonNode team = null;
        for (JsonNode candidate : root.path("teams")) if (teamId.equals(candidate.path("id").asText())) team = candidate;
        if (team == null) throw new AssertionError("team not found");
        String role = team.path("roleVoteStage").asText("captain");
        java.util.Set<String> elected = new java.util.HashSet<>(); team.path("roles").forEach(value -> elected.add(value.asText()));
        for (JsonNode player : team.path("players")) {
            String id = player.path("id").asText();
            if (elected.contains(id)) continue;
            if ("captain".equals(role) && "back".equals(player.path("role").asText())) continue;
            if ("pitcher".equals(role) && !"back".equals(player.path("role").asText())) continue;
            return java.util.List.of(id);
        }
        throw new AssertionError("role candidate not found");
    }
}
