package com.acedicearena.service;

import com.acedicearena.repository.GameStateRepository;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class LobbyEventService {
    private static final long STATE_ID = 1L;
    private static final long CHANGE_COALESCE_MS = 600L;
    private static final long RECONNECT_TIME_MS = 3_000L;
    private static final long HEARTBEAT_INTERVAL_SECONDS = 25L;
    private final Set<Client> clients = ConcurrentHashMap.newKeySet();
    private final GameStateRepository gameStates;
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

    public LobbyEventService(GameStateRepository gameStates,
                             ObjectProvider<GameStateSnapshotStore> snapshotStore) {
        this.gameStates = gameStates;
        this.snapshotStore = snapshotStore;
        broadcaster.scheduleWithFixedDelay(this::heartbeat, HEARTBEAT_INTERVAL_SECONDS,
                HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    public SseEmitter subscribe(String username, String teamId, String role) {
        SseEmitter emitter = new SseEmitter(0L);
        Client client = new Client(username, teamId, role, emitter);
        clients.add(client);
        emitter.onCompletion(() -> clients.remove(client));
        emitter.onTimeout(() -> clients.remove(client));
        emitter.onError(e -> clients.remove(client));
        send(client, SseEmitter.event().reconnectTime(RECONNECT_TIME_MS).comment("connected"));
        send(client, new Event("sync", currentVersion(), null, null, null));
        return emitter;
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
            Event event = new Event("game", null, null, null, null);
            clients.forEach(client -> send(client, event));
        }, CHANGE_COALESCE_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * 阶段/截止时间推进：时效敏感，跳过合并立即广播。
     */
    public void gameChangedNow() {
        afterCommit(() -> broadcaster.execute(() -> broadcastGame(clients)));
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
            broadcastGame(clients.stream().filter(Client::admin).toList());
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
            broadcastGame(clients.stream()
                    .filter(client -> client.admin() || teamId.equals(client.teamId())).toList());
        }, CHANGE_COALESCE_MS, TimeUnit.MILLISECONDS);
    }

    public void chat(String teamId, String sender, String content) {
        Event event = new Event("chat", null, sender, content, Instant.now().toString());
        clients.stream().filter(c -> teamId.equals(c.teamId())).forEach(c -> send(c, event));
    }

    /**
     * 赛况播报：掷骰/盲盒/重掷/猜阵事件作为 feed 消息推进队内频道。
     * 与聊天同为即时转发：不落库、不存历史，后进入的成员看不到过往播报。
     * kind 取值为 roll-big / roll-small / box-buff / box-debuff / reroll-up / reroll-down / guess-many / guess-few。
     */
    public void feed(String teamId, String kind, String sender, String content) {
        Event event = new Event("feed:" + kind, null, sender, content, Instant.now().toString());
        clients.stream().filter(c -> teamId.equals(c.teamId())).forEach(c -> send(c, event));
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
            clients.remove(client);
            client.emitter().complete();
        }
    }

    private void send(Client client, SseEmitter.SseEventBuilder event) {
        try {
            client.emitter().send(event);
        } catch (IOException | IllegalStateException e) {
            clients.remove(client);
            client.emitter().complete();
        }
    }

    private void heartbeat() {
        clients.forEach(client -> send(client, SseEmitter.event().comment("keepalive")));
    }

    private void scheduleChange(String type, AtomicBoolean pending) {
        if (!pending.compareAndSet(false, true)) return;
        broadcaster.schedule(() -> {
            pending.set(false);
            if ("game".equals(type)) broadcastGame(clients);
            else {
                Event event = new Event(type, currentVersion(), null, null, null);
                clients.forEach(client -> send(client, event));
            }
        }, CHANGE_COALESCE_MS, TimeUnit.MILLISECONDS);
    }

    private void broadcastGame(Iterable<Client> recipients) {
        Event event = new Event("game", currentVersion(), null, null, null);
        recipients.forEach(client -> send(client, event));
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

    private record Client(String username, String teamId, String role, SseEmitter emitter) {
        private boolean admin() {
            return "ADMIN".equals(role);
        }
    }

    public record Event(String type, Long version, String sender, String content, String time) {
    }
}
