package com.acedicearena.web;

import com.acedicearena.domain.BattleReport;
import com.acedicearena.domain.GameStateRecord;
import com.acedicearena.domain.UserAccount;
import com.acedicearena.repository.BattleReportRepository;
import com.acedicearena.repository.GameStateRepository;
import com.acedicearena.repository.MatchReportRepository;
import com.acedicearena.repository.UserAccountRepository;
import com.acedicearena.service.AdminTestModeService;
import com.acedicearena.service.LobbyEventService;
import com.acedicearena.service.ParallelTournamentService;
import com.acedicearena.service.StateVersionClock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api")
public class GameDataController {
    private static final long STATE_ID = 1L;
    private static final String STATE_VERSION_HEADER = "X-State-Version";
    private final GameStateRepository gameStateRepository;
    private final BattleReportRepository battleReportRepository;
    private final ObjectMapper objectMapper;
    private final LobbyEventService lobbyEvents;
    private final UserAccountRepository users;
    private final ParallelTournamentService tournament;
    private final MatchReportRepository matchReports;
    private final StateVersionClock stateVersions;
    private final long stateCacheTtlMs;
    private final Object stateCacheLock = new Object();
    private volatile StateSnapshot stateCache;
    private volatile TestUserSnapshot testUserCache;

    public GameDataController(GameStateRepository gameStateRepository,
                              BattleReportRepository battleReportRepository,
                              ObjectMapper objectMapper,
                              LobbyEventService lobbyEvents,
                              UserAccountRepository users,
                              ParallelTournamentService tournament,
                              MatchReportRepository matchReports,
                              StateVersionClock stateVersions,
                              @Value("${app.cache.game-state-ttl-ms:250}") long stateCacheTtlMs) {
        this.gameStateRepository = gameStateRepository;
        this.battleReportRepository = battleReportRepository;
        this.objectMapper = objectMapper;
        this.lobbyEvents = lobbyEvents;
        this.users = users;
        this.tournament = tournament;
        this.matchReports = matchReports;
        this.stateVersions = stateVersions;
        this.stateCacheTtlMs = Math.max(0, stateCacheTtlMs);
    }

    @GetMapping("/game-state")
    public ResponseEntity<String> getGameState(@RequestHeader(value = "If-State-Version", required = false) String ifStateVersion,
                                               HttpSession session) {
        StateSnapshot snapshot = gameState();
        boolean ordinaryUser = "USER".equals(session.getAttribute("role"));
        String username = (String) session.getAttribute(AuthController.SESSION_USER);
        if (ordinaryUser && sandboxHidden(username, snapshot)) return ResponseEntity.noContent().build();
        if (!snapshot.present()) return ResponseEntity.noContent().build();
        String epoch = String.valueOf(snapshot.epoch());
        // epoch 在读取前采样；提交后的版本变化会立即淘汰旧快照，不必等待 TTL。
        if (ifStateVersion != null && epoch.equals(ifStateVersion.trim())) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).header(STATE_VERSION_HEADER, epoch).build();
        }
        String teamId = null;
        String playerId = null;
        if (ordinaryUser) {
            UserAccount account = users.findByUsername(username).orElse(null);
            teamId = account == null ? null : account.getTeamId();
            playerId = account == null ? null : "u" + account.getId();
        }
        // 脱敏视图只取决于 (state, teamId, playerId)：序列化响应体按队缓存在快照上，随快照替换整体失效，
        // 同一快照周期内全部客户端共享同一份字节；teamId 为空时视图按 playerId 兜底定位本队。
        String viewKey = !ordinaryUser ? "raw"
                : teamId != null ? "t:" + teamId
                : playerId != null ? "p:" + playerId : "outsider";
        final String viewTeamId = teamId;
        final String viewPlayerId = playerId;
        String body = snapshot.viewBodies().computeIfAbsent(viewKey,
                key -> serializeStateBody(snapshot, ordinaryUser, viewTeamId, viewPlayerId));
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header(STATE_VERSION_HEADER, epoch)
                .body(body);
    }

    private String serializeStateBody(StateSnapshot snapshot, boolean ordinaryUser, String teamId, String playerId) {
        JsonNode view = ordinaryUser
                ? tournament.publicStateView(snapshot.state(), teamId, playerId)
                : snapshot.state();
        return serialize(Map.of("state", view, "version", snapshot.version()));
    }

    private String serialize(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("serialize game state view failed", e); }
    }

    /**
     * 单场对局详情（含 rounds 完整战力明细）：/api/game-state 只下发摘要，
     * 前端在用户点开某场对局时才调用本接口。完赛场次的明细已从 GameState
     * 行内移出（只留 {round, winner} 摘要），按 (day, matchId) 从归档表读取。
     */
    @GetMapping("/game-state/matches/{matchId}")
    public ResponseEntity<String> getMatchDetail(@PathVariable String matchId, HttpSession session) {
        StateSnapshot snapshot = gameState();
        boolean ordinaryUser = "USER".equals(session.getAttribute("role"));
        String username = (String) session.getAttribute(AuthController.SESSION_USER);
        if (ordinaryUser && sandboxHidden(username, snapshot)) return ResponseEntity.noContent().build();
        if (!snapshot.present()) return ResponseEntity.noContent().build();
        if (snapshot.state().path("matches").path(matchId).isMissingNode()) return ResponseEntity.notFound().build();
        // 单场详情同样按快照缓存序列化体：普通用户脱敏视图只取决于 match 本身
        String body = snapshot.viewBodies().computeIfAbsent("m:" + matchId + (ordinaryUser ? ":u" : ":a"),
                key -> serializeMatchDetail(snapshot, matchId, ordinaryUser));
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header(STATE_VERSION_HEADER, String.valueOf(snapshot.epoch()))
                .body(body);
    }

    private String serializeMatchDetail(StateSnapshot snapshot, String matchId, boolean ordinaryUser) {
        JsonNode match = snapshot.state().path("matches").path(matchId);
        if (match instanceof ObjectNode stateMatch && !hasDetailedRounds(stateMatch)) {
            int day = snapshot.state().path("day").asInt(1);
            JsonNode archived = matchReports.findTopByDayAndMatchIdOrderByIdDesc(day, matchId)
                    .map(report -> parse(report.getContent()))
                    .filter(node -> node instanceof ObjectNode && node.has("rounds"))
                    .orElse(null);
            if (archived != null) {
                // 归档是结算时刻快照，status/phase 之后还会推进（RESULT → done），以当前状态为准
                ((ObjectNode) archived).put("status", stateMatch.path("status").asText());
                ((ObjectNode) archived).put("phase", stateMatch.path("phase").asText());
                match = archived;
            }
        }
        if (ordinaryUser) match = tournament.publicMatchView(match);
        return serialize(match);
    }

    /** state 里的 rounds 是否还带战力明细；完赛场次行内只留摘要，首条无 powerA 即摘要。 */
    private static boolean hasDetailedRounds(JsonNode match) {
        JsonNode rounds = match.path("rounds");
        return rounds.isArray() && !rounds.isEmpty() && rounds.get(0).has("powerA");
    }

    /**
     * 私密沙盘拦截：测试账号（__arena_test_ 前缀）本身就是沙盘参赛者，放行走正常脱敏视图，
     * 与正式比赛真实用户路径一致；只有真实用户才被私密沙盘拦截。
     */
    private boolean sandboxHidden(String username, StateSnapshot snapshot) {
        if (!hasTestUsers()) return false;
        boolean sandboxPlayer = username != null && username.startsWith(AdminTestModeService.USERNAME_PREFIX);
        if (!sandboxPlayer && snapshot.present()) {
            for (JsonNode player : snapshot.state().path("sandboxPlayers"))
                if (username.equals(player.path("username").asText())) sandboxPlayer = true;
            if (username.equals(snapshot.state().at("/sandboxSolo/username").asText(null))) sandboxPlayer = true;
        }
        return !sandboxPlayer;
    }

    @PutMapping("/game-state")
    @Transactional
    public Map<String, Object> saveGameState(@RequestBody JsonNode state, HttpSession session) {
        String username = (String) session.getAttribute(AuthController.SESSION_USER);
        String content = state.toString();
        GameStateRecord record = gameStateRepository.findById(STATE_ID).orElse(null);
        if (record == null) record = new GameStateRecord(STATE_ID, content, username);
        else record.update(content, username);
        gameStateRepository.save(record);
        stateCache = null;
        lobbyEvents.gameChanged();
        return Map.of("ok", true, "version", record.getVersion());
    }

    @PostMapping("/battle-reports")
    public ResponseEntity<?> addBattleReport(@RequestBody ReportBody body, HttpSession session) {
        if (body.content() == null || body.content().isBlank()) return ResponseEntity.badRequest().body(Map.of("error", "empty content"));
        String username = (String) session.getAttribute(AuthController.SESSION_USER);
        BattleReport saved = battleReportRepository.save(new BattleReport(body.content(), username));
        return ResponseEntity.ok(Map.of("id", saved.getId()));
    }

    @GetMapping("/battle-reports")
    public List<BattleReport> battleReports() { return battleReportRepository.findTop300ByOrderByIdDesc(); }

    private JsonNode parse(String json) {
        try { return objectMapper.readTree(json); }
        catch (Exception e) { return objectMapper.createObjectNode(); }
    }

    private StateSnapshot gameState() {
        long now = System.currentTimeMillis();
        StateSnapshot cached = stateCache;
        if (stateCacheTtlMs > 0 && cached != null && cached.epoch() == stateVersions.current()
                && now - cached.loadedAt() < stateCacheTtlMs) return cached;
        synchronized (stateCacheLock) {
            cached = stateCache;
            now = System.currentTimeMillis();
            if (stateCacheTtlMs > 0 && cached != null && cached.epoch() == stateVersions.current()
                    && now - cached.loadedAt() < stateCacheTtlMs) return cached;
            long loadedAt = now;
            // 在读取前取版本：并发提交期间读取的旧数据绝不能标为提交后的新版本。
            long epoch = stateVersions.current();
            StateSnapshot loaded = gameStateRepository.findById(STATE_ID)
                    .map(record -> {
                        JsonNode state = parse(record.getContent());
                        // ROLL 掷骰在 player_roll 表、BLIND_BOX 开盒在 player_blind_box 表、
                        // BATTLE 猜阵在 match_guess 表（只注入密封状态布尔），注入后再入缓存
                        tournament.injectRollResults(state);
                        tournament.injectBlindBoxResults(state);
                        tournament.injectGuessStatus(state);
                        return new StateSnapshot(true, state, record.getVersion(), epoch,
                                record.getUpdatedAt(), record.getUpdatedBy() == null ? "" : record.getUpdatedBy(),
                                loadedAt, new ConcurrentHashMap<>());
                    })
                    .orElseGet(() -> new StateSnapshot(false, objectMapper.createObjectNode(), 0,
                            epoch, Instant.EPOCH, "", loadedAt, new ConcurrentHashMap<>()));
            stateCache = loaded;
            return loaded;
        }
    }

    private boolean hasTestUsers() {
        long now = System.currentTimeMillis();
        TestUserSnapshot cached = testUserCache;
        if (stateCacheTtlMs > 0 && cached != null && now - cached.loadedAt() < 1000) return cached.present();
        synchronized (stateCacheLock) {
            cached = testUserCache;
            now = System.currentTimeMillis();
            if (stateCacheTtlMs > 0 && cached != null && now - cached.loadedAt() < 1000) return cached.present();
            boolean present = users.existsByUsernameStartingWith(AdminTestModeService.USERNAME_PREFIX);
            testUserCache = new TestUserSnapshot(present, now);
            return present;
        }
    }

    public record ReportBody(String content) {}
    /** viewBodies：key 为 t:{teamId} / p:{playerId} / outsider / raw / m:{matchId}:{u|a}，value 为序列化后的响应体 */
    private record StateSnapshot(boolean present, JsonNode state, long version, long epoch, Instant updatedAt,
                                 String updatedBy, long loadedAt, ConcurrentHashMap<String, String> viewBodies) {}
    private record TestUserSnapshot(boolean present, long loadedAt) {}
}
