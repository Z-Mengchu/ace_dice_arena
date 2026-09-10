package com.acedicearena.service;

import com.acedicearena.domain.GameStateRecord;
import com.acedicearena.repository.GameStateRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 统一比赛快照：game_state 的进程内唯一读模型，替代原先三处重复读取
 * （控制器逐请求查版本、大厅独立解析整份状态、广播每次查版本）。
 *
 * 工作方式：
 * - 首个读者加载一次（双检锁），其余读者共享同一实例；
 * - 所有比赛/大厅事件的 afterCommit 先失效快照再广播（见 LobbyEventService）；
 * - 盲盒首开提交只推进运行态 revision：current() 逐请求做内存比较（不查库），
 *   revision 变化即视为失效并重载注入；
 * - 每 5 秒低频 reconcile 查一次版本，作为人工改库/漏失效的兜底，不逐请求查库；
 * - 派生玩家/队伍视图（teamViews）挂在快照上，随主快照一起替换。
 */
@Service
public class GameStateSnapshotStore {
    private static final Logger log = LoggerFactory.getLogger(GameStateSnapshotStore.class);
    private static final long STATE_ID = 1L;

    private final GameStateRepository gameStates;
    private final ObjectMapper mapper;
    private final BlindBoxRoundService blindBoxRounds;
    private final Object lock = new Object();
    private volatile Snapshot snapshot;

    public GameStateSnapshotStore(GameStateRepository gameStates, ObjectMapper mapper,
                                  BlindBoxRoundService blindBoxRounds) {
        this.gameStates = gameStates;
        this.mapper = mapper;
        this.blindBoxRounds = blindBoxRounds;
    }

    /** 比赛状态快照：state 为行内 JSON 注入盲盒结果后的只读视图；teamViews 随快照整体替换。 */
    public record Snapshot(boolean present, JsonNode state, long version, long blindRevision,
                           Instant updatedAt, String updatedBy, long loadedAt,
                           ConcurrentHashMap<String, JsonNode> teamViews) {
    }

    /**
     * 读取当前快照：命中直接共享；失效（含盲盒 revision 变化）时双检锁内只重载一次。
     * 命中路径只做内存比较，不访问数据库。
     */
    public Snapshot current() {
        Snapshot cached = snapshot;
        if (cached != null && cached.blindRevision() == blindBoxRounds.revision()) return cached;
        synchronized (lock) {
            cached = snapshot;
            if (cached != null && cached.blindRevision() == blindBoxRounds.revision()) return cached;
            Snapshot loaded = load();
            snapshot = loaded;
            return loaded;
        }
    }

    /**
     * 失效快照：事务内调用时注册到提交后执行（回滚不失效），事务外立即失效。
     * 所有 game_state 写路径的广播都应先经过这里。
     */
    public void invalidateAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            snapshot = null;
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                snapshot = null;
            }
        });
    }

    /** 立即失效（广播链路已在提交后执行时使用）。 */
    void invalidate() {
        snapshot = null;
    }

    /**
     * 低频兜底：每 5 秒查一次 game_state 版本，与快照不一致（人工改库/漏失效）则失效，
     * 下一次读取重载；快照为空时不主动加载，保持读者驱动。
     */
    @Scheduled(fixedDelayString = "${app.game.snapshot-reconcile-ms:5000}")
    public void reconcile() {
        Snapshot cached = snapshot;
        if (cached == null) return;
        try {
            long currentVersion = gameStates.findVersionById(STATE_ID).orElse(0L);
            if (currentVersion != cached.version()) snapshot = null;
        } catch (RuntimeException e) {
            log.warn("比赛快照 reconcile 失败，下一周期重试", e);
        }
    }

    private Snapshot load() {
        long loadedAt = System.currentTimeMillis();
        long blindRevision = blindBoxRounds.revision();
        return gameStates.findById(STATE_ID)
                .map(record -> {
                    JsonNode state = parse(record.getContent());
                    // BLIND_BOX 阶段开盒结果在 player_blind_box 表/运行态，注入后再发布
                    if (state instanceof ObjectNode root) blindBoxRounds.injectCommittedResults(root);
                    return new Snapshot(true, state, record.getVersion(), blindRevision,
                            record.getUpdatedAt(), record.getUpdatedBy() == null ? "" : record.getUpdatedBy(),
                            loadedAt, new ConcurrentHashMap<>());
                })
                .orElseGet(() -> new Snapshot(false, mapper.createObjectNode(), 0, blindRevision,
                        Instant.EPOCH, "", loadedAt, new ConcurrentHashMap<>()));
    }

    private JsonNode parse(String content) {
        try {
            return mapper.readTree(content);
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }
}
