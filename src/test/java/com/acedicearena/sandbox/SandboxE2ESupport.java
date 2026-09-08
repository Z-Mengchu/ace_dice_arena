package com.acedicearena.sandbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 沙盘 E2E 测试的 HTTP 驱动工具：所有操作都经 MockMvc 走真实 Controller 与会话层，
 * 供 SandboxFullBracketE2ETest 及后续新规则各阶段（P1-P3）的回归测试复用。
 */
public class SandboxE2ESupport {
    public static final String ADMIN = "admin";
    public static final String ADMIN_PASSWORD = "admin123";
    public static final String PLAYER_PASSWORD = "123456";
    public static final String TEST_USER_PREFIX = "__arena_test_";

    private final MockMvc mockMvc;
    private final ObjectMapper mapper;

    public SandboxE2ESupport(MockMvc mockMvc, ObjectMapper mapper) {
        this.mockMvc = mockMvc;
        this.mapper = mapper;
    }

    /** 登录并返回可复用的会话；后续请求带同一 session 即视为已登录。 */
    public MockHttpSession login(String username, String password) throws Exception {
        MockHttpSession session = new MockHttpSession();
        MvcResult result = mockMvc.perform(post("/api/auth/login").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("username", username, "password", password))))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as("登录 %s", username).isEqualTo(200);
        return session;
    }

    public MockHttpSession adminLogin() throws Exception {
        return login(ADMIN, ADMIN_PASSWORD);
    }

    /** 沙盘清理；没有旧沙盘时接口返回 409，属预期。 */
    public void cleanup(MockHttpSession admin) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/admin/test-mode/cleanup").session(admin)).andReturn();
        assertThat(result.getResponse().getStatus()).isIn(200, 409);
    }

    public JsonNode prepare(MockHttpSession admin) throws Exception {
        return postOk(admin, "/api/admin/test-mode/prepare");
    }

    public JsonNode advance(MockHttpSession admin) throws Exception {
        return postOk(admin, "/api/admin/test-mode/advance");
    }

    /** 完整比赛状态；204（比赛未建立）时返回 null。 */
    public JsonNode fetchState(MockHttpSession session) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/game-state").session(session)).andReturn();
        if (result.getResponse().getStatus() == 204) return null;
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return mapper.readTree(result.getResponse().getContentAsString()).path("state");
    }

    public JsonNode battleReports(MockHttpSession session) throws Exception {
        return getOk(session, "/api/battle-reports");
    }

    /** 单场对局详情（含逐局战力明细）；完赛场次由后端从归档表读取，契约与进行中一致。 */
    public JsonNode matchDetail(MockHttpSession session, String matchId) throws Exception {
        return getOk(session, "/api/game-state/matches/" + matchId);
    }

    public void playerAction(MockHttpSession session, String type, List<String> selections) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/lobby/player-action").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("type", type, "selections", selections))))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as("player-action %s -> %s", type, result.getResponse().getContentAsString()).isEqualTo(200);
    }

    private JsonNode postOk(MockHttpSession session, String path) throws Exception {
        MvcResult result = mockMvc.perform(post(path).session(session)).andReturn();
        assertThat(result.getResponse().getStatus()).as("POST %s -> %s", path, result.getResponse().getContentAsString()).isEqualTo(200);
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode getOk(MockHttpSession session, String path) throws Exception {
        MvcResult result = mockMvc.perform(get(path).session(session)).andReturn();
        assertThat(result.getResponse().getStatus()).as("GET %s", path).isEqualTo(200);
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    /** 第 index 个沙盘账号（1 起）：__arena_test_001 .. __arena_test_240。 */
    public static String testUsername(int index) {
        return TEST_USER_PREFIX + String.format("%03d", index);
    }

    public static JsonNode teamOf(JsonNode state, String teamId) {
        for (JsonNode team : state.path("teams"))
            if (teamId.equals(team.path("id").asText())) return team;
        throw new AssertionError("队伍不存在: " + teamId);
    }

    public static JsonNode matchOf(JsonNode state, String matchId) {
        JsonNode match = state.path("matches").path(matchId);
        if (match.isMissingNode()) throw new AssertionError("场次不存在: " + matchId);
        return match;
    }

    /** 沙盘队员的玩家 id（u<dbId>）反查登录用户名：显示名 沙盘队员NNN 与 __arena_test_NNN 一一对应。 */
    public static String usernameOfPlayer(JsonNode team, String playerId) {
        for (JsonNode player : team.path("players")) {
            if (!playerId.equals(player.path("id").asText())) continue;
            String name = player.path("name").asText();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{3})").matcher(name);
            if (m.find()) return TEST_USER_PREFIX + m.group(1);
            throw new AssertionError("无法从显示名反查用户名: " + name);
        }
        throw new AssertionError("玩家不存在: " + playerId);
    }

    /** 本队全部 30 名队员 id，按名单顺序。 */
    public static List<String> allIds(JsonNode team) {
        java.util.List<String> ids = new java.util.ArrayList<>();
        team.path("players").forEach(player -> ids.add(player.path("id").asText()));
        return ids;
    }
}
