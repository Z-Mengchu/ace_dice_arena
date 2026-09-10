package com.acedicearena.service;

import com.acedicearena.repository.GameStateRepository;
import jakarta.annotation.PreDestroy;
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
    private final ScheduledExecutorService broadcaster = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "lobby-event-broadcaster");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean lobbyChangePending = new AtomicBoolean();
    private final AtomicBoolean gameChangePending = new AtomicBoolean();
    private final AtomicBoolean adminGameChangePending = new AtomicBoolean();
    private final ConcurrentHashMap<String, AtomicBoolean> teamGameChangePending = new ConcurrentHashMap<>();

    public LobbyEventService(GameStateRepository gameStates) {
        this.gameStates = gameStates;
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
            return gameStates.findVersionById(STATE_ID).orElse(null);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
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
