package com.acedicearena;

import com.acedicearena.domain.GameStateRecord;
import com.acedicearena.domain.GameControl;
import com.acedicearena.domain.UserAccount;
import com.acedicearena.repository.BattleReportRepository;
import com.acedicearena.repository.GameStateRepository;
import com.acedicearena.repository.GameControlRepository;
import com.acedicearena.repository.RequestAuditRepository;
import com.acedicearena.repository.UserAccountRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// 测试库默认是固定名共享 H2，其他存活的测试上下文的 500ms 定时扫描（advanceDueResults）会消费本测试
// 写入的阶段状态并提前推进；给本测试类独立的内存库，隔离其他上下文的调度器（与 SandboxFullBracketE2ETest 同理）。
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:app-integration;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
class ApplicationIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired GameStateRepository gameStateRepository;
    @Autowired GameControlRepository gameControlRepository;
    @Autowired BattleReportRepository battleReportRepository;
    @Autowired RequestAuditRepository requestAuditRepository;
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
        // 审计降载后快速成功请求只做内存聚合、不逐条落库（慢请求/5xx 才落库），本用例请求均为快速请求。
        assertThat(requestAuditRepository.count()).isZero();
    }

    @Test
    void gameStateAndBattleReportsArePersisted() throws Exception {
        // H2 跨用例共享，新建语义（版本从 1 起）只在记录不存在时成立
        gameStateRepository.deleteAll();
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
    void playerCanRejoinDuringRollAndTheOldTokenStopsWorking() throws Exception {
        MockHttpSession session = registerAssignedPlayer("rejoining_roller", "t1");
        var user = userAccountRepository.findByUsername("rejoining_roller").orElseThrow();
        saveRollState(List.of(user), List.of());

        String first = join(session);
        String second = join(session);
        assertThat(second).isNotEqualTo(first);

        mockMvc.perform(post("/api/calibrate").session(session).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + first + "\",\"rtt\":20}"))
                .andExpect(status().isForbidden());
        pingAndCalibrate(session, second);

        String rolled = roll(session, second);
        JsonNode dice = objectMapper.readTree(rolled);
        assertThat(dice.path("die").asInt()).isBetween(1, 6);
        assertThat(dice.path("rollTs").asLong()).isPositive();

        mockMvc.perform(get("/api/roll-assignment").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(true))
                .andExpect(jsonPath("$.stage").value("ROLL"))
                .andExpect(jsonPath("$.alreadyRolled").value(true))
                .andExpect(jsonPath("$.teamId").value("t1"))
                .andExpect(jsonPath("$.rollGoAt").isNumber())
                .andExpect(jsonPath("$.stageDeadlineAt").isNumber())
                .andExpect(jsonPath("$.squadIndex").value(0))
                .andExpect(jsonPath("$.rollOpenAt").isNumber())
                .andExpect(jsonPath("$.rollDeadlineAt").isNumber());
    }

    @Test
    void laterSquadsOpenLaterButShareTheSameDeadline() throws Exception {
        MockHttpSession squad0Session = registerAssignedPlayer("squad0_roller", "t1");
        MockHttpSession squad5Session = registerAssignedPlayer("squad5_roller", "t1");
        var squad0User = userAccountRepository.findByUsername("squad0_roller").orElseThrow();
        var squad5User = userAccountRepository.findByUsername("squad5_roller").orElseThrow();

        // go 已过去 17s：1 号小队已开掷，6 号小队（go+5s 开）也在窗口内；全局截止 go+20s 未到，双方都能掷。
        ObjectNode root = rollStateRoot(List.of(), List.of());
        long go = System.currentTimeMillis() - 17_000L;
        root.put("rollGoAt", go);
        var rollOpenAts = root.putArray("rollOpenAts");
        for (int k = 0; k < 6; k++) rollOpenAts.add(go + k * 1_000L);
        root.put("stageDeadlineAt", go + 20_000L);
        placeInSquad(root, "t1", 0, "u" + squad0User.getId(), squad0User.getDisplayName());
        placeInSquad(root, "t1", 5, "u" + squad5User.getId(), squad5User.getDisplayName());
        saveState(root);

        String token0 = join(squad0Session);
        pingAndCalibrate(squad0Session, token0);
        String token5 = join(squad5Session);
        pingAndCalibrate(squad5Session, token5);

        JsonNode assign0 = objectMapper.readTree(mockMvc.perform(get("/api/roll-assignment").session(squad0Session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode assign5 = objectMapper.readTree(mockMvc.perform(get("/api/roll-assignment").session(squad5Session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(assign0.path("squadIndex").asInt()).isEqualTo(0);
        assertThat(assign5.path("squadIndex").asInt()).isEqualTo(5);
        // 开掷时刻仍按小队错开 5s，但截止时刻全员统一为全局 stageDeadlineAt
        assertThat(assign5.path("rollOpenAt").asLong() - assign0.path("rollOpenAt").asLong()).isEqualTo(5_000L);
        assertThat(assign0.path("rollDeadlineAt").asLong()).isEqualTo(assign0.path("stageDeadlineAt").asLong());
        assertThat(assign5.path("rollDeadlineAt").asLong()).isEqualTo(assign5.path("stageDeadlineAt").asLong());

        // 双方都在全局窗口内 → 都能掷
        roll(squad0Session, token0);
        roll(squad5Session, token5);
    }

    @Test
    void blindBoxOpenWorksWithJustALoginSessionInsideTheWindow() throws Exception {
        // 大厅原地开盒链路：/api/lobby/player-action 只需登录会话，不依赖掷骰令牌与时钟校准
        MockHttpSession session = registerAssignedPlayer("lobby_box_opener", "t1");
        var user = userAccountRepository.findByUsername("lobby_box_opener").orElseThrow();
        ObjectNode root = rollStateRoot(List.of(user), List.of());
        root.put("stage", "BLIND_BOX");
        root.put("stageDeadlineAt", System.currentTimeMillis() + 15_000L);
        saveState(root);

        // 开盒不再锁 game_state：盲盒内存运行态在独立事务内把结果写入 player_blind_box 并随响应返回；
        // 三选一：selections 带盒子序号，响应返回 3 个盒子内容与选中序号
        JsonNode opened = objectMapper.readTree(mockMvc.perform(post("/api/lobby/player-action").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"blind-box-open\",\"selections\":[\"1\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.blindBox").isNumber())
                .andExpect(jsonPath("$.picked").value(1))
                .andReturn().getResponse().getContentAsString());
        int box = opened.path("blindBox").asInt();
        assertThat(box).isBetween(-2, 5);
        assertThat(opened.path("boxes").size()).isEqualTo(3);
        assertThat(opened.path("boxes").get(1).asInt()).isEqualTo(box);

        // game_state 行内不落盲盒字段；/api/game-state 读取层注入本轮已开结果
        JsonNode saved = objectMapper.readTree(gameStateRepository.findById(1L).orElseThrow().getContent());
        JsonNode meInRow = null;
        for (JsonNode player : saved.path("teams").get(0).path("players"))
            if (("u" + user.getId()).equals(player.path("id").asText())) meInRow = player;
        assertThat(meInRow).isNotNull();
        assertThat(meInRow.has("blindBox")).isFalse();
        JsonNode view = objectMapper.readTree(mockMvc.perform(get("/api/game-state").session(session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode me = null;
        for (JsonNode player : view.path("state").path("teams").get(0).path("players"))
            if (("u" + user.getId()).equals(player.path("id").asText())) me = player;
        assertThat(me).isNotNull();
        assertThat(me.path("blindBox").asInt()).isEqualTo(box);
        assertThat(me.path("blindBoxOpened").asBoolean()).isTrue();

        // 重复开盒幂等：条带锁内读取已有记录并返回同一结果；唯一键仅作完整性防线
        mockMvc.perform(post("/api/lobby/player-action").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"blind-box-open\",\"selections\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blindBox").value(box))
                .andExpect(jsonPath("$.boxes").doesNotExist());
    }

    @Test
    void rollAssignmentReportsIneligibleInsteadOfConflictOutsideTheRollWindow() throws Exception {
        MockHttpSession squadFormPlayer = registerAssignedPlayer("squad_form_player", "t1");
        var squadForm = rollStateRoot(List.of(), List.of());
        squadForm.put("stage", "SQUAD_FORM");
        squadForm.remove("rollGoAt");
        squadForm.remove("stageDeadlineAt");
        // 真实的 SQUAD_FORM 状态下各队尚未分队；带着 squads 会被 500ms 扫描立刻推进到 ROLL
        squadForm.path("teams").forEach(team -> ((ObjectNode) team).remove("squads"));
        saveState(squadForm);
        mockMvc.perform(get("/api/roll-assignment").session(squadFormPlayer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(false))
                .andExpect(jsonPath("$.stage").value("SQUAD_FORM"));

        MockHttpSession benched = registerAssignedPlayer("benched_player", "t3");
        saveState(rollStateRoot(List.of(), List.of()));
        mockMvc.perform(get("/api/roll-assignment").session(benched))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(false))
                .andExpect(jsonPath("$.stage").value("ROLL"))
                .andExpect(jsonPath("$.teamId").value("t3"));
        mockMvc.perform(post("/api/join").session(benched)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict());
    }

    @Test
    void fiveCalibratedPlayersCanCompleteAnOnlineRoll() throws Exception {
        MockHttpSession[] sessions = new MockHttpSession[5];
        List<UserAccount> rollers = new ArrayList<>();
        for (int slot = 1; slot <= 5; slot++) {
            sessions[slot - 1] = registerAssignedPlayer("online_roller" + slot, "t1");
            rollers.add(userAccountRepository.findByUsername("online_roller" + slot).orElseThrow());
        }
        saveRollState(rollers, List.of());

        for (MockHttpSession rollerSession : sessions) {
            String token = join(rollerSession);
            pingAndCalibrate(rollerSession, token);
            String rolled = roll(rollerSession, token);
            assertThat(objectMapper.readTree(rolled).path("die").asInt()).isBetween(1, 6);
            mockMvc.perform(post("/api/roll").session(rollerSession)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"token\":\"" + token + "\",\"clientTs\":" + System.currentTimeMillis() + "}"))
                    .andExpect(status().isConflict());
        }

        JsonNode state = objectMapper.readTree(gameStateRepository.findById(1L).orElseThrow().getContent());
        JsonNode team1 = null;
        for (JsonNode team : state.path("teams")) if ("t1".equals(team.path("id").asText())) team1 = team;
        assertThat(team1).isNotNull();
        for (var roller : rollers) {
            String playerId = "u" + roller.getId();
            JsonNode player = null;
            for (JsonNode candidate : team1.path("players"))
                if (playerId.equals(candidate.path("id").asText())) player = candidate;
            assertThat(player).as("队员 %s 的掷骰数据", playerId).isNotNull();
            assertThat(player.path("dice").asInt()).isBetween(1, 6);
            assertThat(player.path("rollTs").isNumber()).isTrue();
            assertThat(player.has("autoRolled")).isFalse();
        }

        // 旧的 OnlineGameService SSE 通道已退役
        mockMvc.perform(get("/api/events").session(sessions[0])).andExpect(status().isNotFound());
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

    /* ---------- ROLL 阶段夹具与 HTTP 工具 ---------- */

    private void saveRollState(List<UserAccount> t1Users, List<UserAccount> t2Users) {
        saveState(rollStateRoot(t1Users, t2Users));
    }

    /** ROLL 阶段：t1 vs t2 的 g1 进行中，go 已过、窗口未闭；小队错峰时刻表与真实状态一致。 */
    private ObjectNode rollStateRoot(List<UserAccount> t1Users, List<UserAccount> t2Users) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("mode", "parallel"); root.put("stage", "ROLL");
        long go = System.currentTimeMillis() - 1_000L;
        root.put("rollGoAt", go);
        var rollOpenAts = root.putArray("rollOpenAts");
        for (int k = 0; k < 6; k++) rollOpenAts.add(go + k * 1_000L);
        root.put("stageDeadlineAt", go + 20_000L);
        var teams = root.putArray("teams");
        addTeam(teams.addObject(), "t1", t1Users);
        addTeam(teams.addObject(), "t2", t2Users);
        var match = root.putObject("matches").putObject("g1");
        match.put("id", "g1"); match.put("a", "t1"); match.put("b", "t2");
        match.put("status", "active"); match.put("phase", "PENDING");
        return root;
    }

    private void addTeam(ObjectNode team, String teamId, List<UserAccount> members) {
        team.put("id", teamId); team.put("name", teamId);
        var players = team.putArray("players");
        List<String> ids = new ArrayList<>();
        for (UserAccount member : members) {
            players.addObject().put("id", "u" + member.getId()).put("name", member.getDisplayName());
            ids.add("u" + member.getId());
        }
        // 候补凑满 30 人：贴近真实状态，也避免少量真人掷齐就触发全员快速通道
        for (int i = ids.size(); i < 30; i++) {
            String filler = teamId + "-filler" + i;
            players.addObject().put("id", filler).put("name", filler);
            ids.add(filler);
        }
        var squads = team.putArray("squads");
        for (int s = 0; s < 6; s++) {
            var squad = squads.addArray();
            for (int i = s * 5; i < s * 5 + 5; i++) squad.add(ids.get(i));
        }
    }

    /** 把真人玩家塞进指定小队（替换该小队首位的候补 id），名册同步追加。 */
    private void placeInSquad(ObjectNode root, String teamId, int squadIndex, String playerId, String displayName) {
        ObjectNode team = null;
        for (JsonNode node : root.path("teams"))
            if (teamId.equals(node.path("id").asText())) team = (ObjectNode) node;
        assertThat(team).as("队伍 %s 存在", teamId).isNotNull();
        ((com.fasterxml.jackson.databind.node.ArrayNode) team.path("players"))
                .addObject().put("id", playerId).put("name", displayName);
        ((com.fasterxml.jackson.databind.node.ArrayNode) team.path("squads").path(squadIndex))
                .set(0, com.fasterxml.jackson.databind.node.TextNode.valueOf(playerId));
    }

    private void saveState(ObjectNode root) {
        GameStateRecord state = gameStateRepository.findById(1L).orElse(null);
        if (state == null) state = new GameStateRecord(1L, root.toString(), "test");
        else state.update(root.toString(), "test");
        gameStateRepository.saveAndFlush(state);
    }

    private String join(MockHttpSession session) throws Exception {
        String joined = mockMvc.perform(post("/api/join").session(session)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(joined).path("token").asText();
    }

    private void pingAndCalibrate(MockHttpSession session, String token) throws Exception {
        mockMvc.perform(post("/api/ping").session(session).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"c0\":" + System.currentTimeMillis() + "}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/calibrate").session(session).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"rtt\":20}"))
                .andExpect(status().isOk());
    }

    private String roll(MockHttpSession session, String token) throws Exception {
        return mockMvc.perform(post("/api/roll").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"clientTs\":" + System.currentTimeMillis() + "}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }
}
