package com.acedicearena.web;

import com.acedicearena.domain.BattleReport;
import com.acedicearena.domain.GameStateRecord;
import com.acedicearena.domain.UserAccount;
import com.acedicearena.repository.BattleReportRepository;
import com.acedicearena.repository.GameStateRepository;
import com.acedicearena.repository.MatchReportRepository;
import com.acedicearena.repository.UserAccountRepository;
import com.acedicearena.service.AdminTestModeService;
import com.acedicearena.service.GameStateSnapshotStore;
import com.acedicearena.service.GameStateSnapshotStore.Snapshot;
import com.acedicearena.service.LobbyEventService;
import com.acedicearena.service.ParallelTournamentService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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
    private static final String STATE_CACHE_CONTROL = "private, no-cache";
    private final GameStateRepository gameStateRepository;
    private final BattleReportRepository battleReportRepository;
    private final ObjectMapper objectMapper;
    private final LobbyEventService lobbyEvents;
    private final UserAccountRepository users;
    private final ParallelTournamentService tournament;
    private final MatchReportRepository matchReports;
    private final GameStateSnapshotStore snapshotStore;
    private final long stateCacheTtlMs;
    private final Object stateCacheLock = new Object();
    private volatile TestUserSnapshot testUserCache;

    public GameDataController(GameStateRepository gameStateRepository,
                              BattleReportRepository battleReportRepository,
                              ObjectMapper objectMapper,
                              LobbyEventService lobbyEvents,
                              UserAccountRepository users,
                              ParallelTournamentService tournament,
                              MatchReportRepository matchReports,
                              GameStateSnapshotStore snapshotStore,
                              @Value("${app.cache.game-state-ttl-ms:250}") long stateCacheTtlMs) {
        this.gameStateRepository = gameStateRepository;
        this.battleReportRepository = battleReportRepository;
        this.objectMapper = objectMapper;
        this.lobbyEvents = lobbyEvents;
        this.users = users;
        this.tournament = tournament;
        this.matchReports = matchReports;
        this.snapshotStore = snapshotStore;
        this.stateCacheTtlMs = Math.max(0, stateCacheTtlMs);
    }

    @GetMapping("/game-state")
    public ResponseEntity<?> getGameState(
            @RequestParam(required = false) String scope,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch,
            HttpSession session) {
        Snapshot snapshot = gameState();
        boolean ordinaryUser = "USER".equals(session.getAttribute("role"));
        String username = (String) session.getAttribute(AuthController.SESSION_USER);
        if (ordinaryUser && sandboxHidden(username, snapshot)) return ResponseEntity.noContent().build();
        if (!snapshot.present()) return ResponseEntity.noContent().build();
        UserAccount account = ordinaryUser ? users.findByUsername(username).orElse(null) : null;
        String teamId = account == null ? null : account.getTeamId();
        String playerId = account == null ? null : "u" + account.getId();
        boolean playerScope = ordinaryUser && "player".equals(scope);
        String viewKey = ordinaryUser
                ? (playerScope ? "player:" : "public:") + (teamId != null ? "t:" + teamId
                        : playerId != null ? "p:" + playerId : "outsider")
                : "admin";
        String etag = stateEtag(snapshot.version(), snapshot.blindRevision(), viewKey);
        if (etagMatches(ifNoneMatch, etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag)
                    .header(HttpHeaders.CACHE_CONTROL, STATE_CACHE_CONTROL).build();
        }
        JsonNode state = snapshot.state();
        if (ordinaryUser) {
            // 脱敏视图只取决于 (state, teamId, playerId)：按队缓存在快照上，随快照替换整体失效，
            // 避免每个回源请求都对全树深拷贝；teamId 为空时视图按 playerId 兜底定位本队。
            state = snapshot.teamViews().computeIfAbsent(viewKey,
                    key -> playerScope
                            ? tournament.playerStateView(snapshot.state(), teamId, playerId)
                            : tournament.publicStateView(snapshot.state(), teamId, playerId));
        }
        return ResponseEntity.ok().eTag(etag).header(HttpHeaders.CACHE_CONTROL, STATE_CACHE_CONTROL)
                .body(Map.of("state", state, "version", snapshot.version()));
    }

    /** 供轻量单元测试与内部调用保留的无条件读取入口。 */
    public ResponseEntity<?> getGameState(HttpSession session) {
        return getGameState(null, null, session);
    }

    /**
     * 单场对局详情（含 rounds 完整战力明细）：/api/game-state 只下发摘要，
     * 前端在用户点开某场对局时才调用本接口。完赛场次的明细已从 GameState
     * 行内移出（只留 {round, winner} 摘要），按 (day, matchId) 从归档表读取。
     */
    @GetMapping("/game-state/matches/{matchId}")
    public ResponseEntity<?> getMatchDetail(@PathVariable String matchId, HttpSession session) {
        Snapshot snapshot = gameState();
        boolean ordinaryUser = "USER".equals(session.getAttribute("role"));
        String username = (String) session.getAttribute(AuthController.SESSION_USER);
        if (ordinaryUser && sandboxHidden(username, snapshot)) return ResponseEntity.noContent().build();
        if (!snapshot.present()) return ResponseEntity.noContent().build();
        JsonNode match = snapshot.state().path("matches").path(matchId);
        if (match.isMissingNode()) return ResponseEntity.notFound().build();
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
        return ResponseEntity.ok(match);
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
    private boolean sandboxHidden(String username, Snapshot snapshot) {
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
        // 提交后失效统一快照（本方法在事务内，回滚不失效）
        snapshotStore.invalidateAfterCommit();
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

    /** 比赛状态统一快照：失效/盲盒 revision 变化由存储负责，命中路径不查库。 */
    private Snapshot gameState() {
        return snapshotStore.current();
    }

    private static String stateEtag(long version, long blindRevision, String viewKey) {
        return "\"game-state-" + version + "-" + blindRevision + "-" + viewKey + "\"";
    }

    private static boolean etagMatches(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank()) return false;
        for (String candidate : ifNoneMatch.split(",")) {
            String value = candidate.trim();
            if ("*".equals(value) || etag.equals(value) || ("W/" + etag).equals(value)) return true;
        }
        return false;
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
    private record TestUserSnapshot(boolean present, long loadedAt) {}
}
