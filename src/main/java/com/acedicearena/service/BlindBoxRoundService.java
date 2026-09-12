package com.acedicearena.service;

import com.acedicearena.domain.GameStateRecord;
import com.acedicearena.domain.PlayerBlindBox;
import com.acedicearena.domain.UserAccount;
import com.acedicearena.repository.GameStateRepository;
import com.acedicearena.repository.PlayerBlindBoxRepository;
import com.acedicearena.repository.UserAccountRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 盲盒阶段的单实例内存运行态：当前轮的名单、进度、结果与截止协调都集中在本模块，
 * 开盒热路径不再锁定 game_state 行、也不解析整份比赛 JSON。
 *
 * 并发约定（锁顺序固定，禁止反向获取）：
 *   阶段读写锁 → 玩家条带锁 → 数据库事务。
 * - 普通开盒持阶段读锁，所有玩家可并发进入各自事务；
 * - 截止、强制推进、激活、清理持阶段写锁，写锁会等待已取得读锁的开盒事务提交；
 * - 同一玩家的重复提交由固定 256 把条带锁串行，条带锁覆盖“查已有行 → 插入 → 提交”；
 * - 任何数据库调用都发生在取得阶段锁之后，避免占满连接池后再等写锁。
 *
 * 内存一致性约定：
 * - 先提交数据库事务，再更新内存；事务失败时内存结果、计数、revision 一律不变；
 * - 提交成功后内存更新异常不撤销已提交事实，context 置为 DIRTY，下一次读取/协调时在写锁内单飞重建；
 * - context 只保存从 JSON 提取的标量与不可变集合，不持有可变 JsonNode 引用。
 */
@Service
public class BlindBoxRoundService {
    private static final Logger log = LoggerFactory.getLogger(BlindBoxRoundService.class);

    static final int[] BLIND_BOX_VALUES = {5, 4, 3, 2, 1, -1, -2};
    static final int[] BLIND_BOX_WEIGHTS = {1, 4, 10, 25, 35, 17, 8};
    /** 三选一盲盒：摆出供玩家选择的盒子数量。 */
    static final int BLIND_BOX_COUNT = 3;
    /** 全部开完/截止后进入战术阶段的窗口时长，与赛事模块的战术窗口保持一致。 */
    private static final long TACTICS_DURATION_MS = 90_000L;
    /** 同一玩家幂等提交的条带锁数量，固定数量避免无界 Map。 */
    private static final int STRIPE_COUNT = 256;

    private final GameStateRepository states;
    private final UserAccountRepository users;
    private final PlayerBlindBoxRepository blindBoxes;
    private final ObjectMapper mapper;
    private final LobbyEventService events;
    private final TransactionTemplate transactions;

    /** 阶段公平读写锁：公平模式防止持续开盒流量让截止/推进的写锁饥饿。 */
    private final ReentrantReadWriteLock stageLock = new ReentrantReadWriteLock(true);
    private final ReentrantLock[] stripes = new ReentrantLock[STRIPE_COUNT];
    /** 快速推进的单线程协调 executor：推进投递发生在释放阶段读锁之后。 */
    private final ExecutorService coordinator = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "blind-box-coordinator");
        thread.setDaemon(true);
        return thread;
    });

    private volatile RoundContext context;

    public BlindBoxRoundService(GameStateRepository states, UserAccountRepository users,
                                PlayerBlindBoxRepository blindBoxes, ObjectMapper mapper,
                                LobbyEventService events, PlatformTransactionManager transactionManager) {
        this.states = states;
        this.users = users;
        this.blindBoxes = blindBoxes;
        this.mapper = mapper;
        this.events = events;
        this.transactions = new TransactionTemplate(transactionManager);
        for (int i = 0; i < STRIPE_COUNT; i++) stripes[i] = new ReentrantLock();
    }

    /* ---------- 内存模型（发布后除结果/计数/revision/状态外一律不可变） ---------- */

    /** 一轮盲盒的标识；deadlineAt 同时作为同一天同一 bracket 重赛时的运行代标识。 */
    public record RoundKey(int gameDay, int bracketRound, long deadlineAt) {
    }

    /** 玩家在一轮中的位置：所属队伍与场次；发布后不可变。 */
    public record PlayerSlot(String playerId, String teamId, String matchId) {
    }

    /** 从进入盲盒阶段的比赛状态提取的不可变定义：名单、场次映射、截止时间。 */
    public record RoundDefinition(RoundKey key, Map<String, PlayerSlot> players,
                                  Map<String, Set<String>> matchPlayers, Set<String> activeTeamIds) {
        public RoundDefinition {
            players = Collections.unmodifiableMap(new LinkedHashMap<>(players));
            Map<String, Set<String>> matches = new LinkedHashMap<>();
            matchPlayers.forEach((id, ids) -> matches.put(id, Set.copyOf(ids)));
            matchPlayers = Collections.unmodifiableMap(matches);
            activeTeamIds = Set.copyOf(activeTeamIds);
        }
    }

    /** 单场比赛进度：eligible 集合不可变，只有已开人数会增长。 */
    public static final class MatchProgress {
        private final String matchId;
        private final Set<String> eligible;
        private final AtomicInteger opened = new AtomicInteger();

        MatchProgress(String matchId, Set<String> eligible) {
            this.matchId = matchId;
            this.eligible = eligible;
        }

        public String matchId() { return matchId; }
        public int eligibleCount() { return eligible.size(); }
        public int openedCount() { return opened.get(); }
        public boolean complete() { return opened.get() >= eligible.size(); }
    }

    /** 运行态状态：OPEN 接受开盒；CLOSED 拒绝且不再变化；DIRTY 等待从数据库重建。 */
    enum State { OPEN, CLOSED, DIRTY }

    static final class RoundContext {
        final RoundDefinition definition;
        /** 已提交的玩家结果（playerId → 落库点数档）。 */
        final ConcurrentHashMap<String, Integer> results = new ConcurrentHashMap<>();
        final Map<String, MatchProgress> matches;
        /** 仅首次提交或恢复/修补缺失结果时递增；幂等、失败、非法请求不变。 */
        final AtomicLong revision = new AtomicLong();
        /** 快速推进信号合并：全轮完成后只投递一次。 */
        final AtomicBoolean advanceSignal = new AtomicBoolean();
        /** 管理员强制推进后的提前截止时刻；非空时覆盖定义中的 deadline。 */
        volatile Long forceDueAt;
        volatile State state = State.OPEN;

        RoundContext(RoundDefinition definition) {
            this.definition = definition;
            Map<String, MatchProgress> progress = new LinkedHashMap<>();
            definition.matchPlayers().forEach((id, ids) -> progress.put(id, new MatchProgress(id, ids)));
            this.matches = Collections.unmodifiableMap(progress);
        }

        /** 生效截止时刻：被强制推进过的轮次以提前后的时刻为准。 */
        long effectiveDeadline() {
            Long forced = forceDueAt;
            return forced != null ? forced : definition.key().deadlineAt();
        }

        /** 首次提交后更新内存；重复玩家不计数、不增 revision。 */
        void recordResult(String playerId, int value) {
            if (results.putIfAbsent(playerId, value) != null) return;
            PlayerSlot slot = definition.players().get(playerId);
            if (slot == null) return;
            MatchProgress progress = matches.get(slot.matchId());
            if (progress != null) progress.opened.incrementAndGet();
            revision.incrementAndGet();
        }

        /** 数据库已有而内存缺失的修补：计入进度与 revision，并标记 DIRTY 等待完整重建。 */
        void repairResult(String playerId, int value) {
            if (results.putIfAbsent(playerId, value) != null) return;
            PlayerSlot slot = definition.players().get(playerId);
            if (slot == null) return;
            MatchProgress progress = matches.get(slot.matchId());
            if (progress != null) progress.opened.incrementAndGet();
            revision.incrementAndGet();
            state = State.DIRTY;
        }

        int eligibleOpened() {
            int total = 0;
            for (MatchProgress progress : matches.values()) total += progress.opened.get();
            return total;
        }

        boolean allEligibleOpened() {
            return eligibleOpened() >= definition.players().size();
        }
    }

    /** 开盒结果：value 为落库点数档；boxes/picked 仅首次开盒的响应携带，幂等重放时 boxes 为 null、picked 为 -1。 */
    public record BlindBoxResult(int value, int[] boxes, int picked) {
    }

    /** 事务内开盒的内部结果：inserted 标记事务是否写入了新行，repair 标记需要事后修补内存。 */
    private record OpenOutcome(BlindBoxResult result, boolean inserted, String playerId, Integer repairValue,
                               String teamId, String playerName) {
    }

    /* ---------- 开盒写穿 ---------- */

    /**
     * 开盒完整流程：纯参数校验 → DIRTY 时先经写锁单飞重建 → 阶段读锁 → 玩家条带锁 →
     * 事务内校验/幂等/插入，事务提交后才更新内存与 revision，释放读锁后再投递快速推进信号。
     */
    public BlindBoxResult open(String username, Integer boxIndex) {
        if (boxIndex != null && (boxIndex < 0 || boxIndex >= BLIND_BOX_COUNT))
            throw new IllegalArgumentException("盲盒序号超出范围");
        ensureInitialized();
        ReentrantLock stripe = stripeFor(username);
        boolean signalAdvance = false;
        BlindBoxResult result = null;
        boolean opened = false;
        int rebuildAttempts = 0;
        while (!opened) {
            RoundContext ctx = context;
            if (ctx == null) throw new IllegalStateException("当前不在开盲盒阶段");
            if (ctx.state == State.DIRTY) {
                if (++rebuildAttempts > 2) throw new IllegalStateException("比赛状态暂不可用");
                ctx = rebuildDirtyContext(ctx);
                if (ctx == null) throw new IllegalStateException("当前不在开盲盒阶段");
            } else if (ctx.state != State.OPEN) {
                throw new IllegalStateException("当前不在开盲盒阶段");
            }
            RoundContext openCtx = ctx;
            stageLock.readLock().lock();
            try {
                // 取读锁期间 context 可能被替换/关闭/再次置 DIRTY：有变化则放锁后走下一轮重新判定
                if (context != openCtx || openCtx.state != State.OPEN) continue;
                if (openCtx.effectiveDeadline() <= System.currentTimeMillis())
                    throw new IllegalStateException("开盲盒时间已经结束");
                stripe.lock();
                try {
                    OpenOutcome outcome = transactions.execute(status -> doOpen(openCtx, username, boxIndex));
                    if (outcome == null) throw new IllegalStateException("比赛状态暂不可用");
                    // TransactionTemplate 返回即已提交：此后才允许改内存
                    if (outcome.inserted()) {
                        try {
                            openCtx.recordResult(outcome.playerId(), outcome.result().value());
                        } catch (RuntimeException e) {
                            // 已提交事实不撤销；标记 DIRTY 由后续读取/协调重建
                            log.error("盲盒结果已提交但内存更新失败，标记等待重建", e);
                            openCtx.state = State.DIRTY;
                        }
                        // 首开提交成功：无版本合并广播，触发前端强制条件回源
                        events.blindBoxChanged();
                        int boxValue = outcome.result().value();
                        if (boxValue != 0)
                            events.feed(outcome.teamId(), boxValue > 0 ? "box-buff" : "box-debuff",
                                    outcome.playerName(),
                                    boxValue > 0 ? "盲盒开出正向 buff · 欧气直接砸脸上！" : "盲盒踩中负面 debuff · 非酋 buff 已签收");
                        if (openCtx.allEligibleOpened() && openCtx.advanceSignal.compareAndSet(false, true))
                            signalAdvance = true;
                    } else if (outcome.repairValue() != null) {
                        openCtx.repairResult(outcome.playerId(), outcome.repairValue());
                    }
                    result = outcome.result();
                    opened = true;
                } finally {
                    stripe.unlock();
                }
            } finally {
                stageLock.readLock().unlock();
            }
        }
        // 推进投递必须在释放阶段读锁之后，避免读锁线程同步申请写锁
        if (signalAdvance) submitAdvance(System.currentTimeMillis());
        return result;
    }

    private OpenOutcome doOpen(RoundContext ctx, String username, Integer boxIndex) {
        UserAccount user = users.findByUsername(username).orElseThrow();
        if (!"USER".equals(user.getRole()) || user.getTeamId() == null)
            throw new IllegalStateException("只有本轮已分组玩家可以提交比赛操作");
        if (user.isAfk()) throw new IllegalStateException("你当前处于挂机状态，请先取消挂机再操作");
        RoundDefinition definition = ctx.definition;
        if (!definition.activeTeamIds().contains(user.getTeamId()))
            throw new IllegalStateException("本队本轮没有比赛");
        String playerId = "u" + user.getId();
        PlayerSlot slot = definition.players().get(playerId);
        if (slot == null) throw new IllegalStateException("当前账号不在本队参赛名单中");
        Integer inMemory = ctx.results.get(playerId);
        if (inMemory != null)
            return new OpenOutcome(new BlindBoxResult(inMemory, null, -1), false, playerId, null,
                    slot.teamId(), user.getDisplayName());
        int day = definition.key().gameDay();
        int round = definition.key().bracketRound();
        var existing = blindBoxes.findByGameDayAndBracketRoundAndPlayerId(day, round, playerId);
        if (existing.isPresent()) {
            // 数据库已有而内存缺失：幂等返回落库值，事务提交后修补内存并标记 DIRTY
            return new OpenOutcome(new BlindBoxResult(existing.get().getBoxValue(), null, -1),
                    false, playerId, existing.get().getBoxValue(), slot.teamId(), user.getDisplayName());
        }
        int picked = boxIndex == null ? ThreadLocalRandom.current().nextInt(BLIND_BOX_COUNT) : boxIndex;
        int[] boxes = drawBlindBoxes();
        int value = boxes[picked];
        blindBoxes.saveAndFlush(new PlayerBlindBox(day, round, playerId, slot.teamId(), value));
        return new OpenOutcome(new BlindBoxResult(value, boxes, picked), true, playerId, null,
                slot.teamId(), user.getDisplayName());
    }

    /* ---------- 阶段生命周期（供赛事模块在事务提交后调用） ---------- */

    /**
     * 进入盲盒阶段的事务提交后激活运行态。从提交的 JSON 提取不可变定义并恢复数据库中
     * 本轮已有结果；同一天同一 bracket 重赛（deadlineAt 不同）时整体替换为新 context。
     */
    public void activateAfterCommit(ObjectNode state) {
        if (state == null || !"BLIND_BOX".equals(state.path("stage").asText())) return;
        RoundDefinition definition = extractDefinition(state);
        boolean signalAdvance = false;
        stageLock.writeLock().lock();
        try {
            RoundContext existing = context;
            if (existing != null && existing.state == State.OPEN
                    && existing.definition.key().equals(definition.key())) return;
            RoundContext fresh = new RoundContext(definition);
            restoreResults(fresh);
            context = fresh;
            if (fresh.allEligibleOpened() || definition.key().deadlineAt() <= System.currentTimeMillis())
                signalAdvance = fresh.advanceSignal.compareAndSet(false, true);
        } finally {
            stageLock.writeLock().unlock();
        }
        if (signalAdvance) submitAdvance(System.currentTimeMillis());
    }

    /**
     * 全员完成、截止到点或强制推进时的统一关闭入口：持阶段写锁，先把 context 置 CLOSED
     * 阻止后续开盒，再在同一持锁范围内完成状态推进事务。失败时置 DIRTY，交由下一次扫描重试。
     */
    public boolean advanceIfReady(long now, boolean force) {
        stageLock.writeLock().lock();
        try {
            RoundContext ctx = context;
            if (ctx == null || ctx.state != State.OPEN) return false;
            if (!force && !ctx.allEligibleOpened() && ctx.effectiveDeadline() > now) return false;
            ctx.state = State.CLOSED;
            try {
                closeRoundInDatabase(ctx, now);
            } catch (RuntimeException e) {
                log.error("盲盒阶段推进失败，等待重试", e);
                ctx.state = State.DIRTY;
                return false;
            }
            if (context == ctx) context = null;
        } finally {
            stageLock.writeLock().unlock();
        }
        events.gameChangedNow();
        return true;
    }

    /** 清理、重置、新赛事覆盖旧状态后清空运行态；只在数据库事务提交后调用。 */
    public void clearAfterCommit() {
        stageLock.writeLock().lock();
        try {
            context = null;
        } finally {
            stageLock.writeLock().unlock();
        }
    }

    /**
     * 阶段写锁内执行事务动作的组合 seam：写锁先于事务开始（锁顺序：阶段锁 → 数据库事务），
     * TransactionTemplate 返回即已提交，随后 afterCommitInLock 在仍持写锁时同步内存；
     * 事务回滚时 afterCommitInLock 不执行，context 保持不变。
     * 供强制推进、沙盘中途换人等需要同时改 game_state 与运行态的管理路径使用。
     */
    public <T> T runExclusiveInTransaction(java.util.function.Supplier<T> body,
                                           java.util.function.Consumer<T> afterCommitInLock) {
        stageLock.writeLock().lock();
        try {
            T value = transactions.execute(status -> body.get());
            afterCommitInLock.accept(value);
            return value;
        } finally {
            stageLock.writeLock().unlock();
        }
    }

    /** 强制推进：提交后把运行态截止提前到指定时刻；后续开盒按提前后的截止拒绝。 */
    public void markForceDue(long deadlineAt) {
        stageLock.writeLock().lock();
        try {
            RoundContext ctx = context;
            if (ctx != null && ctx.state == State.OPEN) ctx.forceDueAt = deadlineAt;
        } finally {
            stageLock.writeLock().unlock();
        }
    }

    /**
     * 沙盘中途换人提交后整体替换运行态定义：名单、场次映射与结果都从最新状态/数据库重建，
     * 即使运行代（day/round/deadline）不变也强制替换；非盲盒阶段不动。
     * 已开盒玩家的结果随数据库行改挂到新玩家名下，恢复后进度与 revision 不变。
     */
    public void replaceDefinitionAfterCommit(ObjectNode state) {
        if (state == null || !"BLIND_BOX".equals(state.path("stage").asText())) return;
        RoundDefinition definition = extractDefinition(state);
        stageLock.writeLock().lock();
        try {
            RoundContext fresh = new RoundContext(definition);
            restoreResults(fresh);
            context = fresh;
        } finally {
            stageLock.writeLock().unlock();
        }
    }

    /** 当前轮的运行态 revision；盲盒阶段之外恒为 0。 */
    public long revision() {
        RoundContext ctx = context;
        return ctx == null ? 0 : ctx.revision.get();
    }

    /**
     * 读取层注入：把内存中已提交的结果写入传入的比赛状态副本，
     * 供玩家/大厅/大屏视图在盲盒阶段看到队友进度；只注入仍在名单内的玩家。
     */
    public void injectCommittedResults(ObjectNode state) {
        if (state == null || !"BLIND_BOX".equals(state.path("stage").asText())) return;
        stageLock.readLock().lock();
        try {
            RoundContext ctx = context;
            if (ctx == null) return;
            ctx.results.forEach((playerId, value) -> {
                PlayerSlot slot = ctx.definition.players().get(playerId);
                if (slot == null) return;
                ObjectNode player = findPlayerNode(state, slot.teamId(), playerId);
                if (player == null || player.has("blindBox")) return;
                player.put("blindBox", value);
                player.put("blindBoxOpened", true);
            });
        } finally {
            stageLock.readLock().unlock();
        }
    }

    /* ---------- 启动恢复与失败恢复 ---------- */

    /** 启动完成后恢复一次盲盒运行态。 */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        try {
            ensureInitialized();
        } catch (RuntimeException e) {
            log.error("启动恢复盲盒运行态失败，等待后续重试", e);
        }
    }

    /**
     * 恢复入口：启动钩子与首个请求共用；context 缺失（尚未激活、被清理或丢失）时从数据库重建。
     * 恢复失败采用 fail-closed：调用方得到“比赛状态暂不可用”，不发布缺少名单的开放 context，
     * 下一次调用重试。
     */
    public void ensureInitialized() {
        if (context != null) return;
        boolean signalAdvance = false;
        stageLock.writeLock().lock();
        try {
            if (context != null) return;
            RoundContext restored;
            try {
                restored = restoreFromDatabase();
            } catch (RuntimeException e) {
                log.error("盲盒运行态恢复失败", e);
                throw new IllegalStateException("比赛状态暂不可用");
            }
            context = restored;
            if (restored != null && (restored.allEligibleOpened()
                    || restored.definition.key().deadlineAt() <= System.currentTimeMillis()))
                signalAdvance = restored.advanceSignal.compareAndSet(false, true);
        } finally {
            stageLock.writeLock().unlock();
        }
        if (signalAdvance) submitAdvance(System.currentTimeMillis());
    }

    /** 从 game_state 与 player_blind_box 重建当前轮运行态；非盲盒阶段返回 null。 */
    private RoundContext restoreFromDatabase() {
        GameStateRecord record = states.findById(1L).orElse(null);
        ObjectNode root = record == null ? null : parse(record.getContent());
        if (root == null || !"BLIND_BOX".equals(root.path("stage").asText())) return null;
        RoundContext ctx = new RoundContext(extractDefinition(root));
        restoreResults(ctx);
        return ctx;
    }

    /** 只把当前 eligible 玩家的已提交行计入结果与进度；历史/离队/异常行仅记录告警。 */
    private void restoreResults(RoundContext ctx) {
        int day = ctx.definition.key().gameDay();
        int round = ctx.definition.key().bracketRound();
        for (PlayerBlindBox row : blindBoxes.findByGameDayAndBracketRound(day, round)) {
            PlayerSlot slot = ctx.definition.players().get(row.getPlayerId());
            if (slot == null) {
                log.warn("忽略不在当前名单内的盲盒结果行：player={} day={} round={}",
                        row.getPlayerId(), day, round);
                continue;
            }
            if (ctx.results.putIfAbsent(row.getPlayerId(), row.getBoxValue()) != null) {
                log.warn("忽略重复的盲盒结果行：player={} day={} round={}", row.getPlayerId(), day, round);
                continue;
            }
            MatchProgress progress = ctx.matches.get(slot.matchId());
            if (progress != null) progress.opened.incrementAndGet();
            ctx.revision.incrementAndGet();
        }
    }

    /* ---------- 内部实现 ---------- */

    /**
     * DIRTY context 的单飞重建：写锁内复核目标仍是同一个待重建 context 才访问数据库，
     * 并发调用者在写锁上排队后直接看到重建结果（或已关闭/替换后的状态），不重复查库、不竞态发布。
     * 阶段已离开盲盒或运行代失配时关闭旧 context 并返回 null。
     */
    private RoundContext rebuildDirtyContext(RoundContext dirty) {
        stageLock.writeLock().lock();
        try {
            RoundContext ctx = context;
            if (ctx != dirty || ctx.state != State.DIRTY)
                return ctx != null && ctx.state == State.OPEN ? ctx : null;
            RoundContext rebuilt;
            try {
                rebuilt = restoreFromDatabase();
            } catch (RuntimeException e) {
                log.error("盲盒运行态重建失败", e);
                throw new IllegalStateException("比赛状态暂不可用");
            }
            RoundKey key = ctx.definition.key();
            if (rebuilt == null || rebuilt.definition.key().gameDay() != key.gameDay()
                    || rebuilt.definition.key().bracketRound() != key.bracketRound()
                    || rebuilt.definition.key().deadlineAt() != ctx.effectiveDeadline()) {
                ctx.state = State.CLOSED;
                return null;
            }
            context = rebuilt;
            return rebuilt;
        } finally {
            stageLock.writeLock().unlock();
        }
    }

    /** 统一关闭事务：锁定 game_state、复核运行代、合并本轮 eligible 结果并推进战术阶段。 */
    private void closeRoundInDatabase(RoundContext ctx, long now) {
        transactions.executeWithoutResult(status -> {
            GameStateRecord record = states.findLockedById(1L)
                    .orElseThrow(() -> new IllegalStateException("主持人尚未创建比赛"));
            ObjectNode root = parse(record.getContent());
            if (root == null) throw new IllegalStateException("比赛状态无法读取");
            RoundKey key = ctx.definition.key();
            if (!"BLIND_BOX".equals(root.path("stage").asText())
                    || root.path("day").asInt(1) != key.gameDay()
                    || bracketRoundOf(root) != key.bracketRound()
                    // 被强制推进过的轮次，数据库截止已被提前到 forceDueAt
                    || root.path("stageDeadlineAt").asLong() != ctx.effectiveDeadline())
                throw new IllegalStateException("比赛阶段已变化");
            // 只合并当前 eligible 玩家的结果；超时未开者不写字段（按放弃计 0 分）
            for (PlayerBlindBox row : blindBoxes.findByGameDayAndBracketRound(key.gameDay(), key.bracketRound())) {
                PlayerSlot slot = ctx.definition.players().get(row.getPlayerId());
                if (slot == null) continue;
                ObjectNode player = findPlayerNode(root, slot.teamId(), row.getPlayerId());
                if (player == null || player.has("blindBox")) continue;
                player.put("blindBox", row.getBoxValue());
                player.put("blindBoxOpened", true);
            }
            root.put("stage", "TACTICS");
            root.put("stageDeadlineAt", System.currentTimeMillis() + TACTICS_DURATION_MS);
            record.update(root.toString(), "system");
            states.save(record);
        });
    }

    /**
     * 从进入盲盒阶段的比赛状态提取不可变轮次定义（纯函数，不保存任何 JsonNode 引用）：
     * 当天第几轮按进行中场次 id 前缀推导（g=1/4 决赛、s=半决赛、f=决赛/加赛）。
     */
    static RoundDefinition extractDefinition(ObjectNode root) {
        int day = root.path("day").asInt(1);
        int round = bracketRoundOf(root);
        long deadlineAt = root.path("stageDeadlineAt").asLong();
        Map<String, List<String>> matchTeams = new LinkedHashMap<>();
        Set<String> activeTeams = new HashSet<>();
        for (JsonNode match : root.path("matches")) {
            if (!"active".equals(match.path("status").asText())) continue;
            String matchId = match.path("id").asText();
            List<String> teams = new ArrayList<>();
            teams.add(match.path("a").asText());
            teams.add(match.path("b").asText());
            matchTeams.put(matchId, teams);
            activeTeams.addAll(teams);
        }
        Map<String, PlayerSlot> players = new LinkedHashMap<>();
        Map<String, Set<String>> matchPlayers = new LinkedHashMap<>();
        for (JsonNode teamNode : root.path("teams")) {
            String teamId = teamNode.path("id").asText();
            if (!activeTeams.contains(teamId)) continue;
            String matchId = null;
            for (Map.Entry<String, List<String>> entry : matchTeams.entrySet()) {
                if (entry.getValue().contains(teamId)) { matchId = entry.getKey(); break; }
            }
            for (JsonNode player : teamNode.path("players")) {
                String playerId = player.path("id").asText();
                players.put(playerId, new PlayerSlot(playerId, teamId, matchId));
                if (matchId != null)
                    matchPlayers.computeIfAbsent(matchId, k -> new HashSet<>()).add(playerId);
            }
        }
        return new RoundDefinition(new RoundKey(day, round, deadlineAt), players, matchPlayers, activeTeams);
    }

    private static int bracketRoundOf(ObjectNode root) {
        for (JsonNode match : root.path("matches")) {
            if (!"active".equals(match.path("status").asText())) continue;
            String id = match.path("id").asText();
            return id.startsWith("g") ? 1 : id.startsWith("s") ? 2 : 3;
        }
        return 1;
    }

    private static ObjectNode findPlayerNode(ObjectNode root, String teamId, String playerId) {
        for (JsonNode team : root.path("teams")) {
            if (!teamId.equals(team.path("id").asText())) continue;
            for (JsonNode player : team.path("players"))
                if (playerId.equals(player.path("id").asText())) return (ObjectNode) player;
            return null;
        }
        return null;
    }

    /** 单盒抽奖：按权重表抽出点数档。 */
    static int drawBlindBox() {
        int roll = ThreadLocalRandom.current().nextInt(100);
        int cumulative = 0;
        for (int i = 0; i < BLIND_BOX_VALUES.length; i++) {
            cumulative += BLIND_BOX_WEIGHTS[i];
            if (roll < cumulative) return BLIND_BOX_VALUES[i];
        }
        return BLIND_BOX_VALUES[BLIND_BOX_VALUES.length - 1];
    }

    /** 三选一：一次抽出全部盒子的内容，玩家选中的那个落库，其余仅用于展示对比。 */
    static int[] drawBlindBoxes() {
        int[] boxes = new int[BLIND_BOX_COUNT];
        for (int i = 0; i < boxes.length; i++) boxes[i] = drawBlindBox();
        return boxes;
    }

    private ReentrantLock stripeFor(String username) {
        return stripes[(username.hashCode() & 0x7fffffff) % STRIPE_COUNT];
    }

    private void submitAdvance(long now) {
        try {
            coordinator.execute(() -> {
                try {
                    advanceIfReady(now, false);
                } catch (RuntimeException e) {
                    log.warn("快速推进失败，等待定时扫描兜底", e);
                }
            });
        } catch (RuntimeException e) {
            // 协调线程已关闭等情况不阻断开盒；定时扫描保留为可靠兜底
            log.warn("快速推进投递失败，等待定时扫描兜底", e);
        }
    }

    private ObjectNode parse(String content) {
        try {
            return (ObjectNode) mapper.readTree(content);
        } catch (Exception e) {
            throw new IllegalStateException("比赛状态无法读取");
        }
    }

    /** 关闭协调线程：等待当前任务一个短的有界时间，业务结果无 write-behind 缓冲无需补写。 */
    @PreDestroy
    void shutdownCoordinator() {
        coordinator.shutdown();
        try {
            if (!coordinator.awaitTermination(2, TimeUnit.SECONDS)) coordinator.shutdownNow();
        } catch (InterruptedException e) {
            coordinator.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
