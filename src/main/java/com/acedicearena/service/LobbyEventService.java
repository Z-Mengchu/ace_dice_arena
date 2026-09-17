package com.acedicearena.service;

import com.acedicearena.repository.GameStateRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class LobbyEventService {
    private static final Logger log = LoggerFactory.getLogger(LobbyEventService.class);
    private static final long STATE_ID = 1L;
    private static final long CHANGE_COALESCE_MS = 600L;
    private static final long RECONNECT_TIME_MS = 3_000L;
    private static final long HEARTBEAT_INTERVAL_SECONDS = 15L;
    /** 连接最大寿命：半开连接（对端无 FIN 消失）写心跳也可能"成功"，TTL 到期强制收尾，前端按 reconnectTime 自动重连。 */
    private static final long SSE_TTL_MS = Duration.ofHours(2).toMillis();
    /** tabId 缺失/超长时退化为随机 key：不做替换，避免无标识客户端互相顶替。 */
    private static final int MAX_TAB_ID_LENGTH = 64;
    /** 即时事件（chat/feed）在途任务上限：超限丢弃并计数，防止慢客户端拖住广播线程时无界积压。 */
    private static final int MAX_IMMEDIATE_IN_FLIGHT = 1024;
    private final ConcurrentHashMap<String, Client> clients = new ConcurrentHashMap<>();
    private final GameStateRepository gameStates;
    private final ObjectMapper objectMapper;
    /** 懒解析：快照存储依赖 BlindBoxRoundService 而后者依赖本类，Provider 打破构造环。 */
    private final ObjectProvider<GameStateSnapshotStore> snapshotStore;
    private final ScheduledExecutorService broadcaster = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "lobby-event-broadcaster");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean lobbyChangePending = new AtomicBoolean();
    private final AtomicBoolean gameChangePending = new AtomicBoolean();
    private final AtomicBoolean blindGameChangePending = new AtomicBoolean();
    private final AtomicBoolean adminGameChangePending = new AtomicBoolean();
    private final ConcurrentHashMap<String, AtomicBoolean> teamGameChangePending = new ConcurrentHashMap<>();
    private final AtomicInteger immediateInFlight = new AtomicInteger();
    private final AtomicLong immediateDropped = new AtomicLong();

    public LobbyEventService(GameStateRepository gameStates, ObjectMapper objectMapper,
                             ObjectProvider<GameStateSnapshotStore> snapshotStore) {
        this.gameStates = gameStates;
        this.objectMapper = objectMapper;
        this.snapshotStore = snapshotStore;
        broadcaster.scheduleWithFixedDelay(this::heartbeat, HEARTBEAT_INTERVAL_SECONDS,
                HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    public SseEmitter subscribe(String username, String teamId, String role, String tabId) {
        SseEmitter emitter = new SseEmitter(SSE_TTL_MS);
        String key = connectionKey(username, tabId);
        Client client = new Client(key, username, teamId, role, emitter);
        Client[] replaced = new Client[1];
        clients.compute(key, (k, old) -> {
            replaced[0] = old;
            return client;
        });
        // complete 会同步触发旧连接的 onCompletion 回调，回调再动同一个 map 属于递归修改，必须在 compute 之外执行
        if (replaced[0] != null) replaced[0].emitter().complete();
        emitter.onCompletion(() -> clients.remove(key, client));
        emitter.onTimeout(() -> clients.remove(key, client));
        emitter.onError(e -> clients.remove(key, client));
        send(client, SseEmitter.event().reconnectTime(RECONNECT_TIME_MS).comment("connected"));
        send(client, new Event("sync", currentVersion(), null, null, null));
        return emitter;
    }

    /** 连接键 = 用户名 + 标签页标识：同一标签页刷新（sessionStorage 保持不变）时新订阅原子顶掉旧连接。 */
    private static String connectionKey(String username, String tabId) {
        String tab = (tabId == null || tabId.isBlank() || tabId.length() > MAX_TAB_ID_LENGTH)
                ? UUID.randomUUID().toString() : tabId;
        return username + '\u0001' + tab;
    }

    /**
     * 大厅资料变化：分组、准备状态等，需要客户端重新读取大厅和比赛状态。
     */
    public void stateChanged() {
        afterCommit(() -> scheduleChange("lobby", lobbyChangePending));
    }

    /**
     * 比赛状态变化：投票、骰子、比分等，只需要客户端重新读取比赛状态。
     */
    public void gameChanged() {
        afterCommit(() -> scheduleChange("game", gameChangePending));
    }

    /**
     * 盲盒结果变化：复用 game 的 600ms 合并广播，但事件不带版本号（version=null），
     * 利用前端「event version 为 null 时强制条件回源」的既有逻辑；
     * 不为每次开盒查询 game_state 版本。
     */
    public void blindBoxChanged() {
        afterCommit(this::scheduleBlindBoxChange);
    }

    private void scheduleBlindBoxChange() {
        if (!blindGameChangePending.compareAndSet(false, true)) return;
        broadcaster.schedule(() -> {
            blindGameChangePending.set(false);
            broadcastAll(new Event("game", null, null, null, null));
        }, CHANGE_COALESCE_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * 阶段/截止时间推进：时效敏感，跳过合并立即广播。
     */
    public void gameChangedNow() {
        afterCommit(() -> broadcaster.execute(() -> broadcastGame(clients.values())));
    }

    /**
     * 单张角色选票变化只刷新管理员监控，避免普通玩家的投票表单被反复重绘。
     */
    public void adminGameChanged() {
        afterCommit(this::scheduleAdminGameChanged);
    }

    private void scheduleAdminGameChanged() {
        if (!adminGameChangePending.compareAndSet(false, true)) return;
        broadcaster.schedule(() -> {
            adminGameChangePending.set(false);
            broadcastGame(clients.values().stream().filter(Client::admin).toList());
        }, CHANGE_COALESCE_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * 角色投票切换到下一角色时，只刷新本队和管理员。
     */
    public void teamGameChanged(String teamId) {
        afterCommit(() -> scheduleTeamGameChanged(teamId));
    }

    private void scheduleTeamGameChanged(String teamId) {
        AtomicBoolean pending = teamGameChangePending.computeIfAbsent(teamId, ignored -> new AtomicBoolean());
        if (!pending.compareAndSet(false, true)) return;
        broadcaster.schedule(() -> {
            pending.set(false);
            broadcastGame(clients.values().stream()
                    .filter(client -> client.admin() || teamId.equals(client.teamId())).toList());
        }, CHANGE_COALESCE_MS, TimeUnit.MILLISECONDS);
    }

    public void chat(String teamId, String sender, String content) {
        Event event = new Event("chat", null, sender, content, Instant.now().toString());
        dispatchImmediate(() -> sendToTeam(teamId, event));
    }

    /**
     * 赛况播报：掷骰/盲盒/重掷事件作为 feed 消息推进队内频道。
     * 与聊天同为即时转发：不落库、不存历史，后进入的成员看不到过往播报。
     * 扇出统一在广播线程执行：请求线程（掷骰/开盒等热路径，开盒播报原先还持玩家条带锁）只负责入队，不做 SSE 写。
     * kind 取值为 roll-big / roll-small / box-buff / box-debuff / reroll-up / reroll-down。
     */
    public void feed(String teamId, String kind, String sender, String content) {
        Event event = new Event("feed:" + kind, null, sender, content, Instant.now().toString());
        dispatchImmediate(() -> sendToTeam(teamId, event));
    }

    /** 事务内产生的播报：提交成功后才推送，回滚不广播；不触碰快照失效（纯转发，无状态变化）。 */
    public void feedAfterCommit(String teamId, String kind, String sender, String content) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            feed(teamId, kind, sender, content);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                feed(teamId, kind, sender, content);
            }
        });
    }

    private void send(Client client, Object value) {
        try {
            client.emitter().send(SseEmitter.event().data(value));
        } catch (IOException | IllegalStateException e) {
            clients.remove(client.key(), client);
            client.emitter().complete();
        }
    }

    private void send(Client client, SseEmitter.SseEventBuilder event) {
        try {
            client.emitter().send(event);
        } catch (IOException | IllegalStateException e) {
            clients.remove(client.key(), client);
            client.emitter().complete();
        }
    }

    /** 心跳只做 keepalive（维持代理连接）与关标签页死连接的兜底清扫；刷新产生的旧连接在订阅时已被原子替换。 */
    private void heartbeat() {
        SseEmitter.SseEventBuilder keepalive = SseEmitter.event().comment("keepalive");
        clients.values().forEach(client -> send(client, keepalive));
    }

    private void scheduleChange(String type, AtomicBoolean pending) {
        if (!pending.compareAndSet(false, true)) return;
        broadcaster.schedule(() -> {
            pending.set(false);
            if ("game".equals(type)) broadcastGame(clients.values());
            else broadcastAll(new Event(type, currentVersion(), null, null, null));
        }, CHANGE_COALESCE_MS, TimeUnit.MILLISECONDS);
    }

    /** 全体广播：负载序列化一次，所有连接复用同一事件帧。 */
    private void broadcastAll(Event event) {
        SseEmitter.SseEventBuilder built = jsonEvent(event);
        if (built == null) return;
        clients.values().forEach(client -> send(client, built));
    }

    private void broadcastGame(Iterable<Client> recipients) {
        SseEmitter.SseEventBuilder built = jsonEvent(new Event("game", currentVersion(), null, null, null));
        if (built == null) return;
        recipients.forEach(client -> send(client, built));
    }

    /** 队内频道（chat/feed）：序列化一次后只推本队连接；在广播线程上执行。 */
    private void sendToTeam(String teamId, Event event) {
        SseEmitter.SseEventBuilder built = jsonEvent(event);
        if (built == null) return;
        clients.values().stream().filter(c -> teamId.equals(c.teamId())).forEach(c -> send(c, built));
    }

    /**
     * 即时事件入队：单线程广播器保证同队消息按提交顺序发出；
     * 在途任务超限（慢客户端堆积）时丢弃并计数，chat/feed 为纯转发，丢失属于可接受降级。
     */
    private void dispatchImmediate(Runnable task) {
        if (immediateInFlight.incrementAndGet() > MAX_IMMEDIATE_IN_FLIGHT) {
            immediateInFlight.decrementAndGet();
            long dropped = immediateDropped.incrementAndGet();
            if (dropped == 1 || dropped % 256 == 0)
                log.warn("SSE 即时事件在途超限，丢弃（累计 {} 条）", dropped);
            return;
        }
        broadcaster.execute(() -> {
            try {
                task.run();
            } finally {
                immediateInFlight.decrementAndGet();
            }
        });
    }

    /** 广播负载统一序列化：同一事件对 N 条连接只序列化一次；失败时跳过本次广播。 */
    private SseEmitter.SseEventBuilder jsonEvent(Object payload) {
        try {
            return SseEmitter.event().data(objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            log.warn("SSE 事件序列化失败，跳过本次广播", e);
            return null;
        }
    }

    private Long currentVersion() {
        try {
            GameStateSnapshotStore store = snapshotStore.getIfAvailable();
            if (store == null) return gameStates.findVersionById(STATE_ID).orElse(null);
            // 广播版本来自共享快照：提交后首个广播触发一次重载，后续读者共享
            GameStateSnapshotStore.Snapshot current = store.current();
            return current.present() ? current.version() : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private void afterCommit(Runnable action) {
        // 所有 game/state/lobby 事件：提交后先失效统一快照，再广播
        Runnable invalidateThenRun = () -> {
            GameStateSnapshotStore store = snapshotStore.getIfAvailable();
            if (store != null) store.invalidate();
            action.run();
        };
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            invalidateThenRun.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                invalidateThenRun.run();
            }
        });
    }

    @PreDestroy
    public void close() {
        broadcaster.shutdownNow();
    }

    private record Client(String key, String username, String teamId, String role, SseEmitter emitter) {
        private boolean admin() {
            return "ADMIN".equals(role);
        }
    }

    public record Event(String type, Long version, String sender, String content, String time) {
    }
}
