package com.acedicearena.service;

import com.acedicearena.domain.BattleReport;
import com.acedicearena.domain.GameStateRecord;
import com.acedicearena.domain.UserAccount;
import com.acedicearena.repository.BattleReportRepository;
import com.acedicearena.repository.GameStateRepository;
import com.acedicearena.repository.MatchReportRepository;
import com.acedicearena.repository.UserAccountRepository;
import com.acedicearena.service.GameStateSnapshotStore.Snapshot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 比赛数据业务层：整局状态读取（含 ETag 条件请求与按队脱敏视图缓存）、
 * 单场对局明细归档回填、整局状态写库、战报的增查。
 */
@Service
public class GameDataService {
    private static final long STATE_ID = 1L;
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

    public GameDataService(GameStateRepository gameStateRepository,
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

    /** 读取结果语义：无内容 / 未命中 / 条件未变更 / 正常返回。 */
    public enum FetchStatus { NO_CONTENT, NOT_FOUND, NOT_MODIFIED, OK }

    /** 整局状态读取结果：etag 仅在 NOT_MODIFIED/OK 时有值，state/version 仅在 OK 时有值。 */
    public record GameStateResult(FetchStatus status, String etag, JsonNode state, long version) {
    }

    /** 单场对局读取结果：match 仅在 OK 时有值。 */
    public record MatchDetailResult(FetchStatus status, JsonNode match) {
    }

    /**
     * 读取整局状态：普通用户按 (scope, teamId, playerId) 得到脱敏视图并计算 ETag；
     * If-None-Match 命中返回 NOT_MODIFIED，私密沙盘对非参赛真实用户返回 NO_CONTENT。
     */
    public GameStateResult gameState(String username, boolean ordinaryUser, String scope, String ifNoneMatch) {
        Snapshot snapshot = snapshotStore.current();
        if (ordinaryUser && sandboxHidden(username, snapshot)) return new GameStateResult(FetchStatus.NO_CONTENT, null, null, 0);
        if (!snapshot.present()) return new GameStateResult(FetchStatus.NO_CONTENT, null, null, 0);
        UserAccount account = ordinaryUser ? users.findByUsername(username).orElse(null) : null;
        String teamId = account == null ? null : account.getTeamId();
        String playerId = account == null ? null : "u" + account.getId();
        boolean playerScope = ordinaryUser && "player".equals(scope);
        String viewKey = ordinaryUser
                ? (playerScope ? "player:" : "public:") + (teamId != null ? "t:" + teamId
                        : playerId != null ? "p:" + playerId : "outsider")
                : "admin";
        String etag = stateEtag(snapshot.version(), snapshot.blindRevision(), viewKey);
        if (etagMatches(ifNoneMatch, etag)) return new GameStateResult(FetchStatus.NOT_MODIFIED, etag, null, 0);
        JsonNode state = snapshot.state();
        if (ordinaryUser) {
            // 脱敏视图只取决于 (state, teamId, playerId)：按队缓存在快照上，随快照替换整体失效，
            // 避免每个回源请求都对全树深拷贝；teamId 为空时视图按 playerId 兜底定位本队。
            state = snapshot.teamViews().computeIfAbsent(viewKey,
                    key -> playerScope
                            ? tournament.playerStateView(snapshot.state(), teamId, playerId)
                            : tournament.publicStateView(snapshot.state(), teamId, playerId));
        }
        return new GameStateResult(FetchStatus.OK, etag, state, snapshot.version());
    }

    /**
     * 单场对局详情（含 rounds 完整战力明细）：/api/game-state 只下发摘要，
     * 前端在用户点开某场对局时才调用。完赛场次的明细已从 GameState
     * 行内移出（只留 {round, winner} 摘要），按 (day, matchId) 从归档表读取。
     */
    public MatchDetailResult matchDetail(String matchId, String username, boolean ordinaryUser) {
        Snapshot snapshot = snapshotStore.current();
        if (ordinaryUser && sandboxHidden(username, snapshot)) return new MatchDetailResult(FetchStatus.NO_CONTENT, null);
        if (!snapshot.present()) return new MatchDetailResult(FetchStatus.NO_CONTENT, null);
        JsonNode match = snapshot.state().path("matches").path(matchId);
        if (match.isMissingNode()) return new MatchDetailResult(FetchStatus.NOT_FOUND, null);
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
        return new MatchDetailResult(FetchStatus.OK, match);
    }

    /**
     * 保存整局状态：upsert game_state 单行，提交后失效统一快照（回滚不失效）并广播大厅变更。
     */
    @Transactional
    public Map<String, Object> saveGameState(JsonNode state, String username) {
        String content = state.toString();
        GameStateRecord record = gameStateRepository.findById(STATE_ID).orElse(null);
        if (record == null) record = new GameStateRecord(STATE_ID, content, username);
        else record.update(content, username);
        gameStateRepository.save(record);
        snapshotStore.invalidateAfterCommit();
        lobbyEvents.gameChanged();
        return Map.of("ok", true, "version", record.getVersion());
    }

    /** 新增战报：内容为空时返回 empty，由控制器映射为 400。 */
    public Optional<Long> addBattleReport(String content, String username) {
        if (content == null || content.isBlank()) return Optional.empty();
        BattleReport saved = battleReportRepository.save(new BattleReport(content, username));
        return Optional.of(saved.getId());
    }

    /** 最近 300 条战报，按 id 倒序。 */
    public List<BattleReport> battleReports() {
        return battleReportRepository.findTop300ByOrderByIdDesc();
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

    private JsonNode parse(String json) {
        try { return objectMapper.readTree(json); }
        catch (Exception e) { return objectMapper.createObjectNode(); }
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
        if (stateCacheTtlMs > 0 && cached != null && now - cached.loadedAt() < stateCacheTtlMs) return cached.present();
        synchronized (stateCacheLock) {
            cached = testUserCache;
            now = System.currentTimeMillis();
            if (stateCacheTtlMs > 0 && cached != null && now - cached.loadedAt() < stateCacheTtlMs) return cached.present();
            boolean present = users.existsByUsernameStartingWith(AdminTestModeService.USERNAME_PREFIX);
            testUserCache = new TestUserSnapshot(present, now);
            return present;
        }
    }

    private record TestUserSnapshot(boolean present, long loadedAt) {
    }
}
