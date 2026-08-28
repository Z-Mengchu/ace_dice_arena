package com.acedicearena;

import com.acedicearena.domain.GameStateRecord;
import com.acedicearena.domain.GameControl;
import com.acedicearena.repository.BattleReportRepository;
import com.acedicearena.repository.GameStateRepository;
import com.acedicearena.repository.GameControlRepository;
import com.acedicearena.repository.RequestAuditRepository;
import com.acedicearena.repository.UserAccountRepository;
import com.acedicearena.service.OnlineGameService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class ApplicationIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired GameStateRepository gameStateRepository;
    @Autowired GameControlRepository gameControlRepository;
    @Autowired BattleReportRepository battleReportRepository;
    @Autowired RequestAuditRepository requestAuditRepository;
    @Autowired OnlineGameService onlineGameService;
    @Autowired UserAccountRepository userAccountRepository;

    @Test
    void groupedPlayerCanSeeCurrentOpponentRosterButNotUnrelatedTeams() throws Exception {
        MockHttpSession session = registerAssignedPlayer("roster_home", "t1");
        registerAssignedPlayer("roster_opponent", "t2");
        registerAssignedPlayer("roster_unrelated", "t3");

        String content = mockMvc.perform(get("/api/lobby").session(session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode view = objectMapper.readTree(content);
        assertThat(view.at("/teams/1/members").findValuesAsText("username")).contains("roster_opponent");
        assertThat(view.at("/teams/2/members").findValuesAsText("username")).doesNotContain("roster_unrelated");
    }

    @Test
    void loginIsRequiredAndDefaultAccountCanLogin() throws Exception {
        mockMvc.perform(get("/api/state")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/").header("Host", "arena.example:3004"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/login"));
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("主持人"));
        assertThat(requestAuditRepository.count()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void gameStateAndBattleReportsArePersisted() throws Exception {
        MockHttpSession session = login();
        mockMvc.perform(put("/api/game-state").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"screen\":\"setup\",\"teams\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
        mockMvc.perform(post("/api/battle-reports").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"雷霆战区拿下第一局\"}"))
                .andExpect(status().isOk());

        assertThat(gameStateRepository.findById(1L)).isPresent();
        assertThat(battleReportRepository.findTop300ByOrderByIdDesc())
                .extracting("content").contains("雷霆战区拿下第一局");
    }

    @Test
    void regularUserUsesLobbyAndCannotOpenAdminControls() throws Exception {
        HttpSession rawSession = (HttpSession) mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"employee1\",\"displayName\":\"测试队员\",\"department\":\"技术部\",\"password\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("USER"))
                .andReturn().getRequest().getSession(false);
        MockHttpSession session = (MockHttpSession) rawSession;

        mockMvc.perform(get("/api/lobby").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.me.department").value("技术部"))
                .andExpect(jsonPath("$.teams[8].id").value("spectator"));
        mockMvc.perform(get("/api/admin/dashboard").session(session)).andExpect(status().isForbidden());
        mockMvc.perform(get("/").session(session)).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/lobby"));
        mockMvc.perform(get("/sandbox-player").session(session)).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/lobby"));
    }

    @Test
    void localOrdinaryUsersAlsoUseTheUnifiedPassword() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"fixed_password_user\",\"displayName\":\"统一密码用户\",\"department\":\"技术部\",\"password\":\"654321\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"fixed_password_user\",\"password\":\"654321\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"fixed_password_user\",\"password\":\"123456\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void adminCannotStartBeforeEightTeamsOfTwentyFiveAreReady() throws Exception {
        mockMvc.perform(post("/api/admin/start").session(login()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("需要 8 队各 30 人且 240 名参赛用户全部准备"));
    }

    @Test
    void adminCanMarkAllGroupedPlayersReadyWithoutAffectingSpectators() throws Exception {
        MockHttpSession afkSession = registerAssignedPlayer("ready_player", "t1");
        registerAssignedPlayer("already_ready_player", "t1");
        registerAssignedPlayer("ready_spectator", null);
        var alreadyReady = userAccountRepository.findByUsername("already_ready_player").orElseThrow();
        alreadyReady.setReady(true);
        userAccountRepository.save(alreadyReady);

        mockMvc.perform(post("/api/admin/ready-all").session(login()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());

        assertThat(userAccountRepository.findByUsername("ready_player").orElseThrow().isReady()).isTrue();
        assertThat(userAccountRepository.findByUsername("ready_player").orElseThrow().isAfk()).isTrue();
        assertThat(userAccountRepository.findByUsername("already_ready_player").orElseThrow().isAfk()).isFalse();
        assertThat(userAccountRepository.findByUsername("ready_spectator").orElseThrow().isReady()).isFalse();
        assertThat(userAccountRepository.findByUsername("ready_spectator").orElseThrow().isAfk()).isFalse();

        mockMvc.perform(get("/api/lobby").session(afkSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.me.afk").value(true));
        mockMvc.perform(post("/api/lobby/afk/cancel").session(afkSession)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.afk").value(false));
        assertThat(userAccountRepository.findByUsername("ready_player").orElseThrow().isAfk()).isFalse();

        var readyOnlyPlayer = userAccountRepository.findByUsername("ready_player").orElseThrow();
        readyOnlyPlayer.setReady(false);
        userAccountRepository.save(readyOnlyPlayer);
        mockMvc.perform(post("/api/admin/ready-all").session(login()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"markAfk\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.markAfk").value(false));
        assertThat(userAccountRepository.findByUsername("ready_player").orElseThrow().isReady()).isTrue();
        assertThat(userAccountRepository.findByUsername("ready_player").orElseThrow().isAfk()).isFalse();
    }

    @Test
    void assignedPlayerCanReadyWhenLobbyPhaseRecordIsNotGrouped() throws Exception {
        MockHttpSession playerSession = registerAssignedPlayer("ready_phase_mismatch", "t1");
        GameControl control = gameControlRepository.findById(1L).orElseGet(() -> new GameControl(1L));
        control.changePhase("PREPARING");
        gameControlRepository.save(control);

        mockMvc.perform(get("/api/lobby").session(playerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("PREPARING"))
                .andExpect(jsonPath("$.me.ready").value(false))
                .andExpect(jsonPath("$.canReady").value(true));

        mockMvc.perform(post("/api/lobby/ready").session(playerSession)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ready\":true}"))
                .andExpect(status().isOk());
        assertThat(userAccountRepository.findByUsername("ready_phase_mismatch").orElseThrow().isReady()).isTrue();
    }

    @Test
    void adminCanKeepOrClearTeamsWhenPreparingTheSecondDay() throws Exception {
        registerAssignedPlayer("next_day_player", "t1");
        var player = userAccountRepository.findByUsername("next_day_player").orElseThrow();
        player.setReady(true);
        userAccountRepository.save(player);

        mockMvc.perform(post("/api/admin/reset-ready").session(login())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"regroup\":false}"))
                .andExpect(status().isOk());
        player = userAccountRepository.findByUsername("next_day_player").orElseThrow();
        assertThat(player.getTeamId()).isEqualTo("t1");
        assertThat(player.isReady()).isFalse();

        mockMvc.perform(post("/api/admin/reset-ready").session(login())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"regroup\":true}"))
                .andExpect(status().isOk());
        assertThat(userAccountRepository.findByUsername("next_day_player").orElseThrow().getTeamId()).isNull();
    }

    @Test
    void adminCanReplaceAGroupedPlayerWithManagedStandIn() throws Exception {
        MockHttpSession admin = login();
        mockMvc.perform(post("/api/admin/reset-tournament").session(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        registerAssignedPlayer("low_participation_player", "t2");
        var original = userAccountRepository.findByUsername("low_participation_player").orElseThrow();
        mockMvc.perform(put("/api/admin/users/" + original.getId() + "/team").session(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"teamId\":\"t2\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/admin/users/" + original.getId() + "/stand-in").session(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.standIn").value(true))
                .andExpect(jsonPath("$.teamId").value("t2"))
                .andExpect(jsonPath("$.ready").value(true));

        assertThat(userAccountRepository.findByUsername("low_participation_player").orElseThrow().getTeamId()).isNull();
        var standIn = userAccountRepository.findByUsername("__arena_stand_in_" + original.getId()).orElseThrow();
        assertThat(standIn.getTeamId()).isEqualTo("t2");
        assertThat(standIn.isReady()).isTrue();

        mockMvc.perform(post("/api/admin/users/" + standIn.getId() + "/stand-in/restore").session(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.standIn").value(false))
                .andExpect(jsonPath("$.teamId").value("t2"))
                .andExpect(jsonPath("$.ready").value(false));

        var restored = userAccountRepository.findByUsername("low_participation_player").orElseThrow();
        assertThat(restored.getTeamId()).isEqualTo("t2");
        assertThat(restored.isReady()).isFalse();
        assertThat(userAccountRepository.findByUsername("__arena_stand_in_" + original.getId())).isEmpty();
    }

    @Test
    void playerReadyRecoversPreparationSessionAfterApplicationRestart() throws Exception {
        onlineGameService.reset();
        MockHttpSession session = registerAssignedPlayer("restarted_ready_player", "t1");
        var user = userAccountRepository.findByUsername("restarted_ready_player").orElseThrow();
        String playerId = "u" + user.getId();

        var root = objectMapper.createObjectNode();
        root.put("mode", "parallel");
        var teams = root.putArray("teams");
        var teamA = teams.addObject(); teamA.put("id", "t1");
        teamA.putArray("players").addObject().put("id", playerId).put("name", user.getDisplayName());
        teams.addObject().put("id", "t2").putArray("players");
        var lineup = objectMapper.createArrayNode();
        lineup.add(playerId).add("u-dummy-2").add("u-dummy-3").add("u-dummy-4").add("u-dummy-5");
        var match = root.putObject("matches").putObject("restart-match");
        match.put("id", "restart-match"); match.put("a", "t1"); match.put("b", "t2");
        match.put("status", "active"); match.put("phase", "ATTACKING");
        match.putObject("lineups").set("A", lineup);
        match.putObject("sidePhases").put("A", "PREPARING").put("B", "PREPARING");
        GameStateRecord state = gameStateRepository.findById(1L).orElse(null);
        if (state == null) state = new GameStateRecord(1L, root.toString(), "test");
        else state.update(root.toString(), "test");
        gameStateRepository.saveAndFlush(state);

        String joined = mockMvc.perform(post("/api/join").session(session)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token = objectMapper.readTree(joined).path("token").asText();
        mockMvc.perform(post("/api/ping").session(session).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"c0\":" + System.currentTimeMillis() + "}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/calibrate").session(session).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"rtt\":20}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/player-ready").session(session).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"ready\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ready").value(true));
    }

    @Test
    void fiveCalibratedPlayersCanCompleteAnOnlineRoll() throws Exception {
        onlineGameService.reset();
        MockHttpSession admin = login();
        MockHttpSession[] sessions = new MockHttpSession[5];
        var root = objectMapper.createObjectNode(); root.put("mode", "parallel");
        var teams = root.putArray("teams"); var team = teams.addObject(); team.put("id", "t1"); var players = team.putArray("players");
        var selected = objectMapper.createArrayNode();
        for (int slot = 1; slot <= 5; slot++) {
            sessions[slot - 1] = registerAssignedPlayer("roller" + slot, "t1");
            var user = userAccountRepository.findByUsername("roller" + slot).orElseThrow();
            String playerId = "u" + user.getId(); selected.add(playerId);
            players.addObject().put("id", playerId).put("name", user.getDisplayName());
        }
        teams.addObject().put("id", "t2").putArray("players");
        var match = root.putObject("matches").putObject("g1");
        match.put("id", "g1"); match.put("a", "t1"); match.put("b", "t2");
        match.put("status", "active"); match.put("phase", "ROLL_A"); match.putObject("lineups").set("A", selected);
        GameStateRecord state = gameStateRepository.findById(1L).orElse(null);
        if (state == null) state = new GameStateRecord(1L, root.toString(), "test"); else state.update(root.toString(), "test");
        gameStateRepository.save(state);

        String[] tokens = new String[5];
        for (int slot = 1; slot <= 5; slot++) {
            String joined = mockMvc.perform(post("/api/join").session(sessions[slot - 1])
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"teamId\":\"t1\",\"slot\":" + slot + ",\"name\":\"ignored\"}"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(objectMapper.readTree(joined).get("slot").asInt()).isEqualTo(slot);
            tokens[slot - 1] = objectMapper.readTree(joined).get("token").asText();
            mockMvc.perform(post("/api/ping").session(sessions[slot - 1])
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"token\":\"" + tokens[slot - 1] + "\",\"c0\":" + System.currentTimeMillis() + "}"))
                    .andExpect(status().isOk());
            mockMvc.perform(post("/api/calibrate").session(sessions[slot - 1])
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"token\":\"" + tokens[slot - 1] + "\",\"offset\":0,\"rtt\":20}"))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(post("/api/arm").session(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teamId\":\"t1\"}"))
                .andExpect(status().isOk());
        String goResult = mockMvc.perform(post("/api/go").session(admin).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long goTs = objectMapper.readTree(goResult).get("goTs").asLong();
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/roll").session(sessions[i])
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"token\":\"" + tokens[i] + "\",\"clientTs\":" + (goTs + i * 50) + "}"))
                    .andExpect(status().isOk());
        }
        assertThat(onlineGameService.stateView().get("rolling")).isEqualTo(false);
        JsonNode timed = objectMapper.readTree(gameStateRepository.findById(1L).orElseThrow().getContent());
        assertThat(timed.at("/matches/g1/phase").asText()).isEqualTo("ATTACKING");
        assertThat(timed.at("/matches/g1/sidePhases/A").asText()).isEqualTo("PITCHER_ROLL");
        onlineGameService.finalRoll("t1");
        JsonNode revealed = objectMapper.readTree(gameStateRepository.findById(1L).orElseThrow().getContent());
        assertThat(revealed.at("/matches/g1/rolls/A/dice").size()).isEqualTo(5);
        assertThat(revealed.at("/matches/g1/sidePhases/A").asText()).isEqualTo("WAITING");
        assertThat(revealed.at("/matches/g1/phase").asText()).isEqualTo("ATTACKING");
    }

    private MockHttpSession login() throws Exception {
        HttpSession session = (HttpSession) mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
        return (MockHttpSession) session;
    }

    private MockHttpSession registerAssignedPlayer(String username, String teamId) throws Exception {
        HttpSession session = (HttpSession) mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"displayName\":\"掷骰队员\",\"department\":\"技术部\",\"password\":\"123456\"}"))
                .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
        var user = userAccountRepository.findByUsername(username).orElseThrow();
        user.assignTeam(teamId);
        userAccountRepository.save(user);
        return (MockHttpSession) session;
    }
}
