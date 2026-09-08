package com.acedicearena.sandbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 沙盘自动对局端到端测试：全部操作经 HTTP 层（MockMvc + 真实会话）驱动。
 * 新规则下所有玩家动作都走系统兜底，零人工跑完 day1 完整 bracket。
 */
// 后台定时扫描（advanceDueResults）与 advance() 驱动同一状态机，会把阶段推进夹在两次采样之间；
// 把扫描间隔拉到 1 小时，让 advance() 成为唯一驱动，阶段序列断言才确定。
// 同理关掉 game-state 的 250ms 读缓存，保证 advance() 后的采样一定读到最新状态。
// 测试库默认是固定名的共享 H2，其他存活的测试上下文的 500ms 定时扫描会消费本测试 resultReadyAt=+1ms
// 的 RESULT 状态并推进阶段；给本测试类独立的内存库，彻底隔离其他上下文的调度器。
@SpringBootTest(properties = {"app.test-mode.enabled=true", "app.game.result-display-ms=1",
        "app.game.result-scan-ms=3600000", "app.cache.game-state-ttl-ms=0",
        "spring.datasource.url=jdbc:h2:mem:sandbox-e2e;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
class SandboxFullBracketE2ETest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper mapper;
    @Autowired com.acedicearena.service.ParallelTournamentService tournament;
    @Autowired com.acedicearena.repository.UserAccountRepository users;

    private SandboxE2ESupport sandbox;
    private MockHttpSession admin;

    @BeforeEach
    void setUp() throws Exception {
        sandbox = new SandboxE2ESupport(mockMvc, mapper);
        admin = sandbox.adminLogin();
        sandbox.cleanup(admin); // H2 与 Spring 上下文跨测试类共享，进出场都必须清理
    }

    @AfterEach
    void tearDown() throws Exception {
        sandbox.cleanup(admin);
    }

    /** 自动主干：零人工干预跑完 day1 完整 bracket（7 场），每场 6 局、战报落库。 */
    @Test
    void sandboxAutoPlaysFullDayBracket() throws Exception {
        JsonNode status = sandbox.prepare(admin);
        assertThat(status.path("testUsers").asInt()).isEqualTo(240);
        assertThat(status.path("phase").asText()).isEqualTo("PLAYING");

        JsonNode state = sandbox.fetchState(admin);
        assertThat(state.path("stage").asText()).isEqualTo("CAPTAIN_VOTE");
        assertThat(state.path("teams").size()).isEqualTo(8);
        state.path("teams").forEach(team -> assertThat(team.path("players").size()).isEqualTo(30));

        List<String> stages = new ArrayList<>(List.of("CAPTAIN_VOTE"));
        for (int step = 0; step < 200 && !state.hasNonNull("champion"); step++) {
            sandbox.advance(admin);
            state = sandbox.fetchState(admin);
            String stage = state.path("stage").asText();
            if (!stage.equals(stages.get(stages.size() - 1))) stages.add(stage);
        }
        assertThat(state.hasNonNull("champion")).as("200 步内必须产生日冠军").isTrue();
        // 分队每天一次，掷骰/盲盒/战术/对局每个 bracket 轮次重来
        assertThat(stages).containsSubsequence(
                "CAPTAIN_VOTE", "SQUAD_FORM",
                "ROLL", "BLIND_BOX", "TACTICS", "BATTLE",
                "ROLL", "BLIND_BOX", "TACTICS", "BATTLE",
                "ROLL", "BLIND_BOX", "TACTICS", "BATTLE");

        Map<String, String[]> expectedPairing = Map.of(
                "g1", new String[]{"t1", "t2"}, "g2", new String[]{"t3", "t4"},
                "g3", new String[]{"t5", "t6"}, "g4", new String[]{"t7", "t8"});
        final JsonNode bracketState = state;
        expectedPairing.forEach((id, pair) -> {
            JsonNode match = SandboxE2ESupport.matchOf(bracketState, id);
            assertThat(match.path("a").asText()).isEqualTo(pair[0]);
            assertThat(match.path("b").asText()).isEqualTo(pair[1]);
        });
        for (String id : List.of("g1", "g2", "g3", "g4", "s1", "s2", "f1")) {
            JsonNode match = SandboxE2ESupport.matchOf(state, id);
            assertThat(match.path("status").asText()).as("%s 应已完结", id).isEqualTo("done");
            assertThat(match.path("winner").asText()).as("%s 应有胜者", id).isIn(match.path("a").asText(), match.path("b").asText());
            // game-state 行内只保留逐局摘要（局号 + 胜负），战力明细由详情接口从归档读取
            assertThat(match.path("rounds")).as("%s 应有完整 6 局", id).hasSize(6);
            match.path("rounds").forEach(round -> assertThat(round.has("powerA")).isFalse());
            JsonNode detail = sandbox.matchDetail(admin, id);
            assertThat(detail.path("status").asText()).as("%s 详情应回读已完结状态", id).isEqualTo("done");
            assertThat(detail.path("rounds")).as("%s 详情应含完整 6 局", id).hasSize(6);
            detail.path("rounds").forEach(round -> {
                assertThat(round.path("powerA").isNumber()).isTrue();
                assertThat(round.path("powerB").isNumber()).isTrue();
                assertThat(round.path("baseA").isNumber()).isTrue();
                assertThat(round.path("critA").isBoolean()).isTrue();
                assertThat(round.path("guessHitsA").isInt()).isTrue();
                assertThat(round.path("guessBonusA").isNumber()).isTrue();
            });
            assertThat(match.path("winsA").asInt() + match.path("winsB").asInt())
                    .as("%s 胜场不超过 6（平局不得分）", id).isLessThanOrEqualTo(6);
        }
        JsonNode finalMatch = SandboxE2ESupport.matchOf(state, "f1");
        String champion = state.path("champion").asText();
        assertThat(champion).isIn(finalMatch.path("a").asText(), finalMatch.path("b").asText());
        assertThat(state.at("/dayResults/day1").isObject()).isTrue();

        JsonNode reports = sandbox.battleReports(admin);
        long matchReports = countReportsContaining(reports, "第6局");
        long championReports = countReportsContaining(reports, "【冠军】");
        assertThat(matchReports).as("7 场对局战报，每条含第 6 局明细").isGreaterThanOrEqualTo(7);
        assertThat(championReports).as("冠军战报").isGreaterThanOrEqualTo(1);
    }

    /** 动作级抽查：真实 player-action 驱动队长投票与分队，为 P2/P3 测试铺路。 */
    @Test
    void playerActionsDriveMatchSlices() throws Exception {
        sandbox.prepare(admin);
        JsonNode state = sandbox.fetchState(admin);
        JsonNode team1 = SandboxE2ESupport.teamOf(state, "t1");

        // 队长投票：t1 一名玩家投本队一名队员（新规则不限前后端）
        String voteTarget = team1.path("players").get(0).path("id").asText();
        MockHttpSession voter = sandbox.login(SandboxE2ESupport.testUsername(1), SandboxE2ESupport.PLAYER_PASSWORD);
        sandbox.playerAction(voter, "role-vote", List.of(voteTarget));
        state = sandbox.fetchState(admin);
        JsonNode captainVotes = SandboxE2ESupport.teamOf(state, "t1").path("roleVotes");
        assertThat(captainVotes.size()).isEqualTo(1);
        final String expectedTarget = voteTarget;
        captainVotes.fields().forEachRemaining(vote -> assertThat(vote.getValue().asText()).isEqualTo(expectedTarget));

        // 推进：队长全部产生 → SQUAD_FORM
        sandbox.advance(admin);
        state = sandbox.fetchState(admin);
        assertThat(state.path("stage").asText()).isEqualTo("SQUAD_FORM");
        team1 = SandboxE2ESupport.teamOf(state, "t1");
        String captainId = team1.at("/roles/captain").asText();
        assertThat(captainId).isNotBlank();

        // 队长提交分队：本队 30 人按 6×5 顺序扁平排列
        List<String> roster = SandboxE2ESupport.allIds(team1);
        MockHttpSession captain = sandbox.login(
                SandboxE2ESupport.usernameOfPlayer(team1, captainId), SandboxE2ESupport.PLAYER_PASSWORD);
        sandbox.playerAction(captain, "squad-form", roster);
        state = sandbox.fetchState(admin);
        JsonNode squads = SandboxE2ESupport.teamOf(state, "t1").path("squads");
        assertThat(squads).hasSize(6);
        squads.forEach(squad -> assertThat(squad).hasSize(5));
        assertThat(squads.get(0).get(0).asText()).isEqualTo(roster.get(0));
        assertThat(squads.get(5).get(4).asText()).isEqualTo(roster.get(29));

        // 其余队伍由系统兜底均分，随后全员代掷进入盲盒阶段
        sandbox.advance(admin);
        sandbox.advance(admin);
        state = sandbox.fetchState(admin);
        assertThat(state.path("stage").asText()).isEqualTo("BLIND_BOX");
        SandboxE2ESupport.teamOf(state, "t1").path("players").forEach(player -> {
            assertThat(player.path("dice").asInt()).isBetween(1, 6);
            assertThat(player.path("autoRolled").asBoolean()).isTrue();
        });
    }

    /** P2 真人掷骰时序：出战小队 5 人快速连掷拿同步暴击；对手全员缺席由系统代掷，必无暴击。 */    @Test
    void liveSquadRollsEarnSyncCritAndAbsenteesAreAutoRolled() throws Exception {
        sandbox.prepare(admin);
        sandbox.advance(admin);   // CAPTAIN_VOTE → SQUAD_FORM
        sandbox.advance(admin);   // SQUAD_FORM → ROLL
        JsonNode state = sandbox.fetchState(admin);
        assertThat(state.path("stage").asText()).isEqualTo("ROLL");
        long rollGoAt = state.path("rollGoAt").asLong();
        JsonNode team1 = SandboxE2ESupport.teamOf(state, "t1");
        List<String> squad = new ArrayList<>();
        team1.path("squads").get(0).forEach(id -> squad.add(id.asText()));

        // 5 名出战队员登录、领令牌并完成时钟校准（go 之前的准备不计入掷骰时刻）
        MockHttpSession[] sessions = new MockHttpSession[5];
        String[] tokens = new String[5];
        for (int i = 0; i < 5; i++) {
            sessions[i] = sandbox.login(SandboxE2ESupport.usernameOfPlayer(team1, squad.get(i)),
                    SandboxE2ESupport.PLAYER_PASSWORD);
            JsonNode assignment = getOk(sessions[i], "/api/roll-assignment");
            assertThat(assignment.path("eligible").asBoolean()).isTrue();
            assertThat(assignment.path("rollGoAt").asLong()).isEqualTo(rollGoAt);
            tokens[i] = postOk(sessions[i], "/api/join").path("token").asText();
            pingAndCalibrate(sessions[i], tokens[i]);
        }

        Thread.sleep(Math.max(0, rollGoAt - System.currentTimeMillis()) + 100);

        long firstRollTs = Long.MAX_VALUE, lastRollTs = Long.MIN_VALUE;
        for (int i = 0; i < 5; i++) {
            JsonNode rolled = roll(sessions[i], tokens[i], 200);
            assertThat(rolled.path("die").asInt()).isBetween(1, 6);
            firstRollTs = Math.min(firstRollTs, rolled.path("rollTs").asLong());
            lastRollTs = Math.max(lastRollTs, rolled.path("rollTs").asLong());
        }
        assertThat(lastRollTs - firstRollTs).as("串发连掷的归一化时刻应落在同步窗口内").isLessThanOrEqualTo(500);
        // 重复掷骰被状态机拒绝
        roll(sessions[0], tokens[0], 409);

        sandbox.advance(admin);   // ROLL → BLIND_BOX：缺席者由系统代掷
        state = sandbox.fetchState(admin);
        team1 = SandboxE2ESupport.teamOf(state, "t1");
        for (String id : squad) {
            JsonNode player = playerOf(team1, id);
            assertThat(player.has("autoRolled")).as("真人已掷者不应被代掷覆盖").isFalse();
            assertThat(player.path("rollTs").asLong()).isGreaterThanOrEqualTo(rollGoAt);
        }
        JsonNode team2 = SandboxE2ESupport.teamOf(state, "t2");
        for (int k = 0; k < 6; k++) {
            long expectedRollTs = rollGoAt + k * 1_000L + 1_000L;
            team2.path("squads").get(k).forEach(id -> {
                JsonNode player = playerOf(team2, id.asText());
                assertThat(player.path("autoRolled").asBoolean()).isTrue();
                assertThat(player.path("rollTs").asLong()).isEqualTo(expectedRollTs);
            });
        }

        sandbox.advance(admin);   // BLIND_BOX → TACTICS
        sandbox.advance(admin);   // TACTICS → BATTLE
        sandbox.advance(admin);   // 第 1 局 GUESS → REVEAL
        state = sandbox.fetchState(admin);
        JsonNode round = SandboxE2ESupport.matchOf(state, "g1").path("rounds").get(0);
        assertThat(round.path("critA").asBoolean()).as("5 真人 ≤500ms 连掷应触发同步暴击").isTrue();
        assertThat(round.path("critB").asBoolean()).as("含代掷的小队必无暴击").isFalse();
    }

    /** P3 动作链：真人驱动 开盲盒 → 重掷 → 排阵锁定 → 猜阵，结果正确进入战力与战报。 */
    @Test
    void playerActionsDriveTacticsAndGuesses() throws Exception {
        sandbox.prepare(admin);
        sandbox.advance(admin);   // CAPTAIN_VOTE → SQUAD_FORM
        sandbox.advance(admin);   // SQUAD_FORM → ROLL
        sandbox.advance(admin);   // ROLL → BLIND_BOX（全员代掷）
        JsonNode state = sandbox.fetchState(admin);
        assertThat(state.path("stage").asText()).isEqualTo("BLIND_BOX");

        // 开盲盒：t1 一名队员自己开，档位入库且非代开
        JsonNode team1 = SandboxE2ESupport.teamOf(state, "t1");
        String openerId = team1.path("players").get(0).path("id").asText();
        MockHttpSession opener = sandbox.login(SandboxE2ESupport.usernameOfPlayer(team1, openerId),
                SandboxE2ESupport.PLAYER_PASSWORD);
        sandbox.playerAction(opener, "blind-box-open", List.of());
        state = sandbox.fetchState(admin);
        JsonNode opened = playerOf(SandboxE2ESupport.teamOf(state, "t1"), openerId);
        assertThat(opened.path("blindBox").asInt()).isBetween(-2, 3);
        assertThat(opened.path("blindBoxOpened").asBoolean()).isTrue();
        assertThat(opened.has("autoOpened")).isFalse();

        sandbox.advance(admin);   // BLIND_BOX → TACTICS（其余人未开，按放弃计 0 分）
        state = sandbox.fetchState(admin);
        assertThat(state.path("stage").asText()).isEqualTo("TACTICS");
        team1 = SandboxE2ESupport.teamOf(state, "t1");
        String captainId = team1.at("/roles/captain").asText();
        MockHttpSession captain = sandbox.login(SandboxE2ESupport.usernameOfPlayer(team1, captainId),
                SandboxE2ESupport.PLAYER_PASSWORD);

        // 重掷：保留原掷骰时刻（暴击判定不受影响）与盲盒，只改点数并记日志
        JsonNode targetBefore = team1.path("players").get(1);
        String targetId = targetBefore.path("id").asText();
        long rollTsBefore = targetBefore.path("rollTs").asLong();
        int blindBoxBefore = targetBefore.path("blindBox").asInt();
        sandbox.playerAction(captain, "reroll", List.of(targetId));
        state = sandbox.fetchState(admin);
        team1 = SandboxE2ESupport.teamOf(state, "t1");
        JsonNode targetAfter = playerOf(team1, targetId);
        assertThat(targetAfter.path("rerolled").asBoolean()).isTrue();
        assertThat(targetAfter.path("rollTs").asLong()).isEqualTo(rollTsBefore);
        assertThat(targetAfter.path("blindBox").asInt()).isEqualTo(blindBoxBefore);
        assertThat(team1.path("rerollUsed").asInt()).isEqualTo(1);
        assertThat(team1.path("rerollLog").get(0).path("playerId").asText()).isEqualTo(targetId);
        // 非队长重掷被拒（opener 可能就是兜底选出的队长，另找一个非队长队员）
        String nonCaptainId = null;
        for (JsonNode p : team1.path("players"))
            if (!captainId.equals(p.path("id").asText())) { nonCaptainId = p.path("id").asText(); break; }
        MockHttpSession nonCaptain = sandbox.login(SandboxE2ESupport.usernameOfPlayer(team1, nonCaptainId),
                SandboxE2ESupport.PLAYER_PASSWORD);
        playerActionRaw(nonCaptain, "reroll", List.of(targetId), 409);

        // 排阵：3 号小队提为 1 号位，锁定后不可重排
        String originalThirdSquadLead = team1.path("squads").get(2).get(0).asText();
        sandbox.playerAction(captain, "squad-order", List.of("3", "1", "2", "4", "5", "6"));
        state = sandbox.fetchState(admin);
        team1 = SandboxE2ESupport.teamOf(state, "t1");
        assertThat(team1.path("squads").get(0).get(0).asText()).isEqualTo(originalThirdSquadLead);
        assertThat(team1.path("squadOrderLocked").asBoolean()).isTrue();
        playerActionRaw(captain, "squad-order", List.of("1", "2", "3", "4", "5", "6"), 409);

        sandbox.advance(admin);   // TACTICS → BATTLE
        state = sandbox.fetchState(admin);
        team1 = SandboxE2ESupport.teamOf(state, "t1");
        JsonNode team2 = SandboxE2ESupport.teamOf(state, "t2");
        List<String> squadA = new ArrayList<>();
        team1.path("squads").get(0).forEach(id -> squadA.add(id.asText()));
        List<String> enemySquadB = new ArrayList<>();
        team2.path("squads").get(0).forEach(id -> enemySquadB.add(id.asText()));

        // 猜阵：非出战成员被拒；出战小队 5 人各猜中敌方出战 5 人 → 25 人次 ×0.4 触发单局上限 10
        JsonNode outsider = null;
        for (JsonNode p : team1.path("players")) {
            if (!squadA.contains(p.path("id").asText())) { outsider = p; break; }
        }
        MockHttpSession notPlaying = sandbox.login(
                SandboxE2ESupport.usernameOfPlayer(team1, outsider.path("id").asText()),
                SandboxE2ESupport.PLAYER_PASSWORD);
        playerActionRaw(notPlaying, "round-guess", enemySquadB, 409);
        for (String memberId : squadA) {
            MockHttpSession member = sandbox.login(SandboxE2ESupport.usernameOfPlayer(team1, memberId),
                    SandboxE2ESupport.PLAYER_PASSWORD);
            sandbox.playerAction(member, "round-guess", enemySquadB);
        }
        state = sandbox.fetchState(admin);
        JsonNode match = SandboxE2ESupport.matchOf(state, "g1");
        assertThat(match.path("roundPhase").asText()).as("B 方未交齐，不应提前揭晓").isEqualTo("GUESS");
        assertThat(match.at("/guesses/A").size()).isEqualTo(5);

        sandbox.advance(admin);   // 第 1 局 GUESS → REVEAL
        state = sandbox.fetchState(admin);
        JsonNode round = SandboxE2ESupport.matchOf(state, "g1").path("rounds").get(0);
        assertThat(round.path("guessHitsA").asInt()).isEqualTo(25);
        assertThat(round.path("guessBonusA").asDouble()).isEqualTo(10d);
        assertThat(round.path("guessHitsB").asInt()).isEqualTo(0);
        assertThat(round.path("powerA").asDouble())
                .isEqualTo(round.path("baseA").asDouble() + 10d);  // 代掷无暴击，战力 = 基础 + 猜阵加成
    }

    /** P3 密封与脱敏：指定真人沙盘玩家经 HTTP 拿到的视图，猜阵内容密封、敌方编排与点数不可见。 */
    @Test
    void sandboxPlayerViewSealsGuessesAndEnemyLineup() throws Exception {
        sandbox.prepare(admin);
        registerRealUser("e2e_view_a");
        registerRealUser("e2e_view_b");
        try {
            postJson(admin, "/api/admin/test-mode/sandbox-players",
                    "{\"firstUsername\":\"e2e_view_a\",\"firstTeamId\":\"t1\",\"firstIdentity\":\"front\","
                            + "\"secondUsername\":\"e2e_view_b\",\"secondTeamId\":\"t2\",\"secondIdentity\":\"front\"}", 200);

            // 双人沙盘禁用 advance()，直接驱动状态机到第 1 局 GUESS（指定玩家后阶段位置不确定，按状态循环推进）
            for (int step = 0; step < 8; step++) {
                JsonNode current = sandbox.fetchState(admin);
                if ("BATTLE".equals(current.path("stage").asText())) break;
                tournament.simulateStep("e2e");
            }
            JsonNode state = sandbox.fetchState(admin);
            assertThat(state.path("stage").asText()).isEqualTo("BATTLE");
            JsonNode team1 = SandboxE2ESupport.teamOf(state, "t1");
            JsonNode team2 = SandboxE2ESupport.teamOf(state, "t2");
            String submitterId = null;
            for (JsonNode id : team1.path("squads").get(0)) {
                JsonNode candidate = playerOf(team1, id.asText());
                if (candidate.path("name").asText().startsWith("沙盘队员")) { submitterId = id.asText(); break; }
            }
            assertThat(submitterId).as("出战小队中应至少有一名沙盘队员可提交猜阵").isNotNull();
            List<String> enemySquadB = new ArrayList<>();
            team2.path("squads").get(0).forEach(id -> enemySquadB.add(id.asText()));
            MockHttpSession submitter = sandbox.login(SandboxE2ESupport.usernameOfPlayer(team1, submitterId),
                    SandboxE2ESupport.PLAYER_PASSWORD);
            sandbox.playerAction(submitter, "round-guess", enemySquadB);

            // t1 真人玩家视角：本队完整；敌方 t2 剥离编排与点数；猜阵只见提交状态
            MockHttpSession viewerA = sandbox.login("e2e_view_a", SandboxE2ESupport.PLAYER_PASSWORD);
            JsonNode viewA = sandbox.fetchState(viewerA);
            assertThat(viewA).as("指定沙盘玩家应能读取比赛状态").isNotNull();
            JsonNode viewMatch = SandboxE2ESupport.matchOf(viewA, "g1");
            assertThat(viewMatch.has("guesses")).isFalse();
            assertThat(viewMatch.at("/guessStatus/A/" + submitterId).asBoolean()).isTrue();
            JsonNode viewOwn = SandboxE2ESupport.teamOf(viewA, "t1");
            assertThat(viewOwn.path("squads").size()).isEqualTo(6);
            assertThat(viewOwn.path("players").get(0).has("dice")).isTrue();
            JsonNode viewEnemy = SandboxE2ESupport.teamOf(viewA, "t2");
            assertThat(viewEnemy.has("squads")).isFalse();
            assertThat(viewEnemy.has("rerollLog")).isFalse();
            assertThat(viewEnemy.path("players").get(0).has("dice")).isFalse();
            assertThat(viewEnemy.path("players").get(0).has("blindBox")).isFalse();
            assertThat(viewEnemy.path("players").get(0).path("name").asText()).isNotBlank();

            // t2 真人玩家视角对称：t1 被剥离，本队完整
            MockHttpSession viewerB = sandbox.login("e2e_view_b", SandboxE2ESupport.PLAYER_PASSWORD);
            JsonNode viewB = sandbox.fetchState(viewerB);
            assertThat(SandboxE2ESupport.teamOf(viewB, "t1").has("squads")).isFalse();
            assertThat(SandboxE2ESupport.teamOf(viewB, "t2").path("squads").size()).isEqualTo(6);
        } finally {
            users.findByUsername("e2e_view_a").ifPresent(users::delete);
            users.findByUsername("e2e_view_b").ifPresent(users::delete);
        }
    }

    private void registerRealUser(String username) throws Exception {
        postJson(new MockHttpSession(), "/api/auth/register",
                "{\"username\":\"" + username + "\",\"displayName\":\"E2E观察员\",\"department\":\"销售部\",\"password\":\"123456\"}",
                200);
    }

    private void playerActionRaw(MockHttpSession session, String type, List<String> selections,
                                 int expectedStatus) throws Exception {
        var result = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/lobby/player-action").session(session)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("type", type, "selections", selections))))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as("player-action %s -> %s", type, result.getResponse().getContentAsString())
                .isEqualTo(expectedStatus);
    }

    private JsonNode getOk(MockHttpSession session, String path) throws Exception {
        var result = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
                        .session(session)).andReturn();
        assertThat(result.getResponse().getStatus()).as("GET %s -> %s", path, result.getResponse().getContentAsString()).isEqualTo(200);
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode postOk(MockHttpSession session, String path) throws Exception {
        var result = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                        .session(session).contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as("POST %s -> %s", path, result.getResponse().getContentAsString()).isEqualTo(200);
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    private void pingAndCalibrate(MockHttpSession session, String token) throws Exception {
        postJson(session, "/api/ping", "{\"token\":\"" + token + "\",\"c0\":" + System.currentTimeMillis() + "}", 200);
        postJson(session, "/api/calibrate", "{\"token\":\"" + token + "\",\"rtt\":20}", 200);
    }

    private JsonNode roll(MockHttpSession session, String token, int expectedStatus) throws Exception {
        String body = postJson(session, "/api/roll",
                "{\"token\":\"" + token + "\",\"clientTs\":" + System.currentTimeMillis() + "}", expectedStatus);
        return mapper.readTree(body);
    }

    private String postJson(MockHttpSession session, String path, String body, int expectedStatus) throws Exception {
        var result = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                        .session(session).contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as("POST %s -> %s", path, result.getResponse().getContentAsString())
                .isEqualTo(expectedStatus);
        return result.getResponse().getContentAsString();
    }

    private static JsonNode playerOf(JsonNode team, String playerId) {
        for (JsonNode player : team.path("players"))
            if (playerId.equals(player.path("id").asText())) return player;
        throw new AssertionError("玩家不存在: " + playerId);
    }

    private long countReportsContaining(JsonNode reports, String keyword) {
        long count = 0;
        for (JsonNode report : reports)
            if (report.path("content").asText().contains(keyword)) count++;
        return count;
    }
}
