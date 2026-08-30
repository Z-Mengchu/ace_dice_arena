package com.acedicearena.service;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class OnlineGameService {
    private static final Set<String> TEAM_IDS = Set.of("t1", "t2", "t3", "t4", "t5", "t6", "t7", "t8");
    private static final int SLOT_COUNT = 5;
    private static final long SYNC_WINDOW_MS = 500;
    private static final long RECONNECT_TIME_MS = 3_000L;
    private static final long HEARTBEAT_INTERVAL_SECONDS = 25L;
    /** 客户端上报的往返延迟只作为偏移的半程修正，且必须落在这个上限内，避免用超大 rtt 撬动偏移。 */
    private static final long MAX_RTT_HINT_MS = 300L;
    /** 归一化点击时刻允许早于服务端收包时刻的最大值：覆盖真实网络单程延迟，同时限制伪造空间。 */
    private static final long ROLL_TOLERANCE_MS = 250L;
    private static final int SSE_QUEUE_CAPACITY = 20_000;

    private final Map<String, Device> devices = new LinkedHashMap<>();
    /** 每个设备的时钟探测记录，全部由服务端时间写入，客户端无法直接指定偏移。 */
    private final Map<String, ClockProbe> probes = new LinkedHashMap<>();
    private final Map<String, TeamRollSession> sessions = new LinkedHashMap<>();
    /** SSE 订阅保留设备 token；同席位被顶替时主动断开旧连接。 */
    private final Map<SseEmitter, String> emitters = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "dice-reveal-timer");
        thread.setDaemon(true);
        return thread;
    });
    /** SSE 写出线程：与游戏状态锁解耦，队列打满时丢事件而不是拖垮比赛推进。 */
    private final ThreadPoolExecutor dispatcher = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(SSE_QUEUE_CAPACITY), r -> {
        Thread thread = new Thread(r, "dice-sse-dispatcher");
        thread.setDaemon(true);
        return thread;
    }, new ThreadPoolExecutor.DiscardPolicy());
    private final AtomicLong droppedEvents = new AtomicLong();

    private String lastArmedTeam;
    private final ApplicationEventPublisher publisher;

    public OnlineGameService(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
        scheduler.scheduleWithFixedDelay(this::heartbeat, HEARTBEAT_INTERVAL_SECONDS,
                HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    public synchronized Map<String, Object> stateView() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("devices", deviceViews());
        result.put("armedTeam", lastArmedTeam);
        result.put("preparedTeams", sessions.keySet());
        result.put("armedTeams", sessions.entrySet().stream().filter(e -> e.getValue().goTs != null).map(Map.Entry::getKey).toList());
        Map<String, Long> countdowns = new LinkedHashMap<>();
        sessions.forEach((team, session) -> { if (session.countdownAt != null) countdowns.put(team, session.countdownAt); });
        result.put("countdowns", countdowns);
        result.put("rolling", sessions.values().stream().anyMatch(s -> !s.timingReady));
        result.put("timingReadyTeams", sessions.entrySet().stream().filter(e -> e.getValue().timingReady).map(Map.Entry::getKey).toList());
        result.put("droppedEvents", droppedEvents.get());
        return result;
    }

    public synchronized JoinResult join(String teamId, Integer slot, String name) {
        return join(teamId, slot, name, null);
    }

    public synchronized JoinResult join(String teamId, Integer slot, String name, String playerId) {
        if (!TEAM_IDS.contains(teamId) || slot == null || slot < 1 || slot > SLOT_COUNT) {
            throw new IllegalArgumentException("invalid teamId or slot");
        }
        List<String> replacedTokens = devices.entrySet().stream()
                .filter(e -> e.getValue().teamId().equals(teamId) && e.getValue().slot() == slot)
                .map(Map.Entry::getKey)
                .toList();
        replacedTokens.forEach(devices::remove);
        replacedTokens.forEach(this::closeSubscribers);
        pruneProbes();
        String token = UUID.randomUUID().toString();
        devices.put(token, new Device(teamId, slot, limit(name, 32), playerId, 0, null, false, false));
        broadcast(Map.of("type", "roster", "devices", deviceViews()));
        return new JoinResult(token, teamId, slot);
    }

    /**
     * 时钟探测：服务端记录本次请求到达时的服务器时刻与客户端声称的发送时刻之差。
     * 单程延迟非负，因此真实偏移 <= min(收包时刻 - c0)，取多次探测的最小值作为上界。
     * 返回服务器时刻供客户端估算往返延迟。
     */
    public synchronized long ping(String token, Double clientSendTs) {
        long serverTs = System.currentTimeMillis();
        if (token != null && clientSendTs != null && Double.isFinite(clientSendTs) && devices.containsKey(token)) {
            probes.computeIfAbsent(token, ignored -> new ClockProbe()).record(serverTs - clientSendTs);
        }
        return serverTs;
    }

    /**
     * 完成校准：偏移由服务端根据自己记录的探测样本算出，客户端只能提供一个被限幅的往返延迟，
     * 用于补偿单程延迟带来的固定偏差（offset = min(收包时刻 - c0) - rtt/2）。
     */
    public synchronized Calibration calibrate(String token, Double rttHint) {
        Device old = requireDevice(token);
        ClockProbe probe = probes.get(token);
        if (probe == null || !probe.hasSample()) throw new IllegalStateException("时钟探测样本不足，请重新校准");
        double rtt = rttHint == null || !Double.isFinite(rttHint) ? 0d : Math.min(Math.max(rttHint, 0d), MAX_RTT_HINT_MS);
        double offset = probe.minDelta() - rtt / 2;
        devices.put(token, new Device(old.teamId(), old.slot(), old.name(), old.playerId(), offset, rtt, true, old.ready()));
        broadcast(Map.of("type", "roster", "devices", deviceViews()));
        return new Calibration(offset, rtt);
    }

    public synchronized void ready(String token, boolean ready) {
        Device old = requireDevice(token);
        if (ready && !old.calibrated()) throw new IllegalStateException("设备校准完成后才能准备");
        TeamRollSession session = sessions.get(old.teamId());
        if (session == null || session.goTs != null) throw new IllegalStateException("当前不在备战准备阶段");
        devices.put(token, new Device(old.teamId(), old.slot(), old.name(), old.playerId(), old.offset(), old.rtt(), old.calibrated(), ready));
        broadcast(Map.of("type", "roster", "devices", deviceViews()));
        publisher.publishEvent(new DiceReadinessChangedEvent(old.teamId()));
    }

    public synchronized void prepare(String teamId, String matchId, int round, List<String> lineup) {
        if (!TEAM_IDS.contains(teamId) || lineup == null || lineup.size() != SLOT_COUNT)
            throw new IllegalArgumentException("invalid lineup");
        if (matchId == null || matchId.isBlank() || round < 1)
            throw new IllegalArgumentException("invalid match identity");
        removeDevices(device -> teamId.equals(device.teamId())
                && (device.playerId() == null || !device.playerId().equals(lineup.get(device.slot() - 1))));
        devices.replaceAll((token, device) -> teamId.equals(device.teamId())
                ? new Device(device.teamId(), device.slot(), device.name(), device.playerId(), device.offset(), device.rtt(), device.calibrated(), false)
                : device);
        pruneProbes();
        sessions.put(teamId, new TeamRollSession(matchId, round, List.copyOf(lineup)));
        broadcast(Map.of("type", "prepare", "teamId", teamId));
    }

    /** Compatibility path for the legacy host-controlled flow. */
    public synchronized void prepare(String teamId, List<String> lineup) {
        prepare(teamId, "legacy-" + teamId, 1, lineup);
    }

    /**
     * 恢复数据库中仍处于备战阶段、但因应用重启而丢失的内存会话。
     * 仅场次、轮次和阵容完全相同时复用；身份变化必须重建，避免跨局串用准备状态。
     */
    public synchronized void ensurePrepared(String teamId, String matchId, int round, List<String> lineup) {
        TeamRollSession current = sessions.get(teamId);
        if (current == null || !current.matches(matchId, round, lineup)) {
            prepare(teamId, matchId, round, lineup);
        }
    }

    public synchronized void ensurePrepared(String teamId, List<String> lineup) {
        ensurePrepared(teamId, "legacy-" + teamId, 1, lineup);
    }

    public synchronized boolean isTeamReady(String teamId) {
        TeamRollSession session = sessions.get(teamId);
        if (session == null || session.lineup.size() != SLOT_COUNT) return false;
        for (int slot = 1; slot <= SLOT_COUNT; slot++) {
            Device device = findDevice(teamId, slot);
            if (device == null || !device.calibrated() || !device.ready()
                    || !session.lineup.get(slot - 1).equals(device.playerId())) return false;
        }
        return true;
    }

    public synchronized long startCountdown(String teamId) {
        TeamRollSession session = sessions.get(teamId);
        if (session == null || session.goTs != null || session.countdownAt != null)
            throw new IllegalStateException("当前不能发号施令");
        if (!isTeamReady(teamId)) throw new IllegalStateException("必须等待五名出战队员全部准备");
        session.countdownAt = System.currentTimeMillis() + 3_000L;
        long target = session.countdownAt;
        broadcast(Map.of("type", "countdown", "teamId", teamId, "goTs", target));
        scheduler.schedule(() -> startAfterCountdown(teamId, target), 3_000L, TimeUnit.MILLISECONDS);
        return target;
    }

    private synchronized void startAfterCountdown(String teamId, long target) {
        TeamRollSession session = sessions.get(teamId);
        if (session == null || !Objects.equals(session.countdownAt, target) || session.goTs != null) return;
        session.goTs = target;
        publisher.publishEvent(new DiceAttackStartedEvent(teamId));
        broadcast(Map.of("type", "go", "teamId", teamId, "goTs", target));
    }

    public synchronized void arm(String teamId) {
        if (!TEAM_IDS.contains(teamId)) throw new IllegalArgumentException("invalid teamId");
        sessions.put(teamId, new TeamRollSession("legacy-" + teamId, 1, List.of()));
        lastArmedTeam = teamId;
        broadcast(Map.of("type", "arm", "teamId", teamId));
    }

    public synchronized long go() {
        if (lastArmedTeam == null) throw new IllegalStateException("not armed");
        return go(lastArmedTeam);
    }

    public synchronized long go(String teamId) {
        TeamRollSession session = sessions.get(teamId);
        if (session == null || session.timingReady) throw new IllegalStateException("not armed");
        session.goTs = System.currentTimeMillis();
        session.countdownAt = session.goTs;
        broadcast(Map.of("type", "go", "teamId", teamId, "goTs", session.goTs));
        return session.goTs;
    }

    public synchronized void armAndGo(String teamId) {
        arm(teamId);
        scheduler.schedule(() -> tryAutomaticGo(teamId), 500, TimeUnit.MILLISECONDS);
    }

    public synchronized void armAndGo(String teamId, List<String> lineup) {
        if (lineup == null || lineup.size() != SLOT_COUNT) throw new IllegalArgumentException("invalid lineup");
        removeDevices(device -> teamId.equals(device.teamId())
                && (device.playerId() == null || !device.playerId().equals(lineup.get(device.slot() - 1))));
        pruneProbes();
        armAndGo(teamId);
    }

    private synchronized void tryAutomaticGo(String teamId) {
        TeamRollSession session = sessions.get(teamId);
        if (session == null || session.goTs != null) return;
        long ready = devices.values().stream().filter(d -> teamId.equals(d.teamId()) && d.calibrated()).count();
        if (ready >= SLOT_COUNT) go(teamId);
        else scheduler.schedule(() -> tryAutomaticGo(teamId), 1000, TimeUnit.MILLISECONDS);
    }

    public synchronized void roll(String token, Double clientTs) {
        long serverTs = System.currentTimeMillis();
        Device device = requireDevice(token);
        TeamRollSession session = sessions.get(device.teamId());
        if (session == null || session.timingReady || session.goTs == null) throw new IllegalStateException("not armed");
        if (clientTs == null || !Double.isFinite(clientTs)) throw new IllegalArgumentException("invalid clientTs");
        boolean recorded = session.rolls.putIfAbsent(device.slot(),
                new Roll(device.slot(), clientTs, normalizedClick(clientTs, device, serverTs))) == null;
        if (recorded) {
            List<Integer> rolledSlots = session.rolls.keySet().stream().sorted().toList();
            broadcast(Map.of("type", "timing-progress", "teamId", device.teamId(), "rolledSlots", rolledSlots));
            publisher.publishEvent(new DiceTimingProgressEvent(device.teamId(), rolledSlots));
        }
        if (session.rolls.size() >= SLOT_COUNT) completeTiming(device.teamId());
    }

    public synchronized boolean isTimingReady(String teamId) {
        TeamRollSession session = sessions.get(teamId);
        return session != null && session.timingReady;
    }

    public synchronized void finalRoll(String teamId) {
        TeamRollSession session = sessions.get(teamId);
        if (session == null || !session.timingReady) throw new IllegalStateException("five player timings not ready");
        List<DiceResult> dice = new ArrayList<>();
        for (int slot = 1; slot <= SLOT_COUNT; slot++) {
            Roll roll = session.rolls.get(slot);
            Double normalized = roll == null ? null : roll.normalizedTs();
            boolean early = normalized != null && session.goTs != null && normalized < session.goTs;
            dice.add(new DiceResult(slot, ThreadLocalRandom.current().nextInt(1, 7), normalized, early));
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "reveal"); event.put("teamId", teamId); event.put("dice", dice);
        event.put("spreadMs", session.spreadMs); event.put("syncOk", session.syncOk);
        broadcast(event);
        publisher.publishEvent(new DiceRevealEvent(teamId, dice, session.syncOk, session.spreadMs));
        sessions.remove(teamId);
    }

    /** 校准前的席位归属校验：设备一旦绑定了玩家，就只有该玩家本人可以继续校准它。 */
    public synchronized boolean ownsDevice(String token, String playerId) {
        Device device = devices.get(token);
        return device != null && (device.playerId() == null || device.playerId().equals(playerId));
    }

    public synchronized boolean matchesAssignment(String token, String teamId, int slot, String playerId) {
        Device device = devices.get(token);
        return device != null && device.teamId().equals(teamId) && device.slot() == slot
                && Objects.equals(device.playerId(), playerId);
    }

    public synchronized void reset() {
        sessions.clear(); lastArmedTeam = null;
        broadcast(Map.of("type", "reset"));
    }

    public synchronized SseEmitter subscribe(String token) {
        if (!"host".equals(token) && !devices.containsKey(token)) throw new SecurityException("unknown token");
        SseEmitter emitter = new SseEmitter(0L);
        emitters.put(emitter, token);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        List<Object> snapshot = new ArrayList<>();
        snapshot.add(Map.of("type", "roster", "devices", deviceViews()));
        sessions.forEach((team, session) -> {
            if (session.countdownAt == null) snapshot.add(Map.of("type", "prepare", "teamId", team));
            else if (session.goTs == null) snapshot.add(Map.of("type", "countdown", "teamId", team, "goTs", session.countdownAt));
            else snapshot.add(Map.of("type", "go", "teamId", team, "goTs", session.goTs));
        });
        sessions.forEach((team, session) -> {
            if (session.timingReady) snapshot.add(Map.of("type", "timing-ready", "teamId", team,
                    "spreadMs", session.spreadMs, "syncOk", session.syncOk));
        });
        dispatch(() -> {
            write(emitter, SseEmitter.event().reconnectTime(RECONNECT_TIME_MS).comment("connected"));
            for (Object event : snapshot) write(emitter, event);
        });
        return emitter;
    }

    /**
     * 超时兜底：不再等五人准备齐，直接开放点击。已到场的队员照常抢同步，
     * 缺席位置最终由 finalRoll 自动补掷。
     */
    public synchronized void forceStart(String teamId) {
        TeamRollSession session = sessions.computeIfAbsent(teamId,
                ignored -> new TeamRollSession("forced-" + teamId, 1, List.of()));
        if (session.timingReady || session.goTs != null) return;
        session.goTs = System.currentTimeMillis();
        session.countdownAt = session.goTs;
        broadcast(Map.of("type", "go", "teamId", teamId, "goTs", session.goTs));
    }

    /** 超时兜底：用已经到位的点击完成计时判定，人不齐一律判为未同步（同步增益失效）。 */
    public synchronized void forceTiming(String teamId) {
        TeamRollSession session = sessions.get(teamId);
        if (session == null || session.timingReady) return;
        if (session.goTs == null) session.goTs = System.currentTimeMillis();
        completeTiming(teamId, true);
    }

    private synchronized void completeTiming(String teamId) { completeTiming(teamId, false); }

    private synchronized void completeTiming(String teamId, boolean forced) {
        TeamRollSession session = sessions.get(teamId);
        if (session == null || session.timingReady) return;
        List<Double> timestamps = new ArrayList<>();
        int earlyCount = 0;
        for (int slot = 1; slot <= SLOT_COUNT; slot++) {
            Roll roll = session.rolls.get(slot);
            if (roll != null) {
                if (session.goTs != null && roll.normalizedTs() < session.goTs) earlyCount++;
                timestamps.add(roll.normalizedTs());
            }
        }
        Double spread = timestamps.isEmpty() ? null : Collections.max(timestamps) - Collections.min(timestamps);
        boolean syncOk = !forced && timestamps.size() >= SLOT_COUNT && spread != null
                && spread <= SYNC_WINDOW_MS && earlyCount == 0;
        session.timingReady = true; session.spreadMs = spread; session.syncOk = syncOk;
        broadcast(Map.of("type", "timing-ready", "teamId", teamId, "spreadMs", spread, "syncOk", syncOk));
        publisher.publishEvent(new DiceTimingReadyEvent(teamId, syncOk, spread));
    }

    /**
     * 归一化点击时刻并夹到服务端可验证的区间内。
     * 点击不可能发生在服务端收包之后，也不可能早于一个网络单程延迟之前，
     * 因此任何伪造的 clientTs / 时钟偏移最多只能在本机真实网络延迟范围内移动，无法凭空制造同步。
     */
    private double normalizedClick(double clientTs, Device device, long serverTs) {
        double normalized = clientTs + device.offset();
        double earliest = serverTs - ROLL_TOLERANCE_MS;
        if (normalized > serverTs) return serverTs;
        return Math.max(normalized, earliest);
    }

    private Device requireDevice(String token) {
        Device device = devices.get(token);
        if (device == null) throw new SecurityException("invalid token");
        return device;
    }

    private void pruneProbes() { probes.keySet().retainAll(devices.keySet()); }

    private void removeDevices(java.util.function.Predicate<Device> predicate) {
        List<String> removedTokens = devices.entrySet().stream()
                .filter(entry -> predicate.test(entry.getValue()))
                .map(Map.Entry::getKey)
                .toList();
        removedTokens.forEach(devices::remove);
        removedTokens.forEach(this::closeSubscribers);
    }

    private Device findDevice(String teamId, int slot) {
        return devices.values().stream().filter(d -> d.teamId().equals(teamId) && d.slot() == slot).findFirst().orElse(null);
    }

    private List<DeviceView> deviceViews() {
        return devices.values().stream().map(d -> new DeviceView(d.teamId(), d.slot(), d.name(), d.playerId(), d.rtt(), d.calibrated(), d.ready())).toList();
    }

    /**
     * 广播只在调用方的锁内拍一张订阅者快照，真正的 SSE 写出交给单线程分发器。
     * 这样一个写不动的慢客户端只会拖慢事件分发，不会再握着本服务的全局锁把整场比赛卡住。
     * 单线程 + FIFO 队列同时保证事件顺序，以及同一个 emitter 不会被两个线程并发写入。
     */
    private void broadcast(Object event) {
        List<SseEmitter> targets = List.copyOf(emitters.keySet());
        if (targets.isEmpty()) return;
        dispatch(() -> { for (SseEmitter emitter : targets) write(emitter, event); });
    }

    private void dispatch(Runnable task) {
        try { dispatcher.execute(task); }
        catch (RejectedExecutionException dropped) { droppedEvents.incrementAndGet(); }
    }

    /** 被丢弃的 SSE 事件数：队列打满或服务停止时累加，客户端会通过下一次全量状态拉取自愈。 */
    public long droppedEventCount() { return droppedEvents.get(); }

    private void write(SseEmitter emitter, Object event) {
        try { emitter.send(SseEmitter.event().data(event)); }
        catch (IOException | IllegalStateException e) { emitters.remove(emitter); emitter.complete(); }
    }

    private void write(SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try { emitter.send(event); }
        catch (IOException | IllegalStateException e) { emitters.remove(emitter); emitter.complete(); }
    }

    private void closeSubscribers(String token) {
        emitters.entrySet().stream()
                .filter(entry -> Objects.equals(entry.getValue(), token))
                .map(Map.Entry::getKey)
                .toList()
                .forEach(emitter -> {
                    emitters.remove(emitter);
                    emitter.complete();
                });
    }

    private void heartbeat() {
        List<SseEmitter> targets = List.copyOf(emitters.keySet());
        if (targets.isEmpty()) return;
        dispatch(() -> {
            for (SseEmitter emitter : targets) write(emitter, SseEmitter.event().comment("keepalive"));
        });
    }

    private String limit(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }

    @PreDestroy
    void close() { scheduler.shutdownNow(); dispatcher.shutdownNow(); }

    private record Device(String teamId, int slot, String name, String playerId, double offset, Double rtt, boolean calibrated, boolean ready) {}
    private record Roll(int slot, double clientTs, double normalizedTs) {}
    /** 服务端侧时钟探测：只保留 (收包时刻 - c0) 的最小值，即真实时钟偏移的上界。 */
    private static final class ClockProbe {
        private Double minDelta;
        private void record(double delta) { if (minDelta == null || delta < minDelta) minDelta = delta; }
        private boolean hasSample() { return minDelta != null; }
        private double minDelta() { return minDelta; }
    }
    public record Calibration(double offset, double rtt) {}
    private static class TeamRollSession {
        private final String matchId;
        private final int round;
        private final List<String> lineup;
        private final Map<Integer, Roll> rolls = new HashMap<>();
        private boolean timingReady;
        private boolean syncOk;
        private Double spreadMs;
        private Long goTs;
        private Long countdownAt;
        private TeamRollSession(String matchId, int round, List<String> lineup) {
            this.matchId = matchId;
            this.round = round;
            this.lineup = lineup;
        }

        private boolean matches(String matchId, int round, List<String> lineup) {
            return Objects.equals(this.matchId, matchId) && this.round == round && this.lineup.equals(lineup);
        }
    }
    public record DeviceView(String teamId, int slot, String name, String playerId, Double rtt, boolean calibrated, boolean ready) {}
    public record JoinResult(String token, String teamId, int slot) {}
    public record DiceResult(int slot, int die, Double ts, boolean early) {}
    public record DiceTimingProgressEvent(String teamId, List<Integer> rolledSlots) {}
    public record DiceTimingReadyEvent(String teamId, boolean syncOk, Double spreadMs) {}
    public record DiceRevealEvent(String teamId, List<DiceResult> dice, boolean syncOk, Double spreadMs) {}
    public record DiceReadinessChangedEvent(String teamId) {}
    public record DiceAttackStartedEvent(String teamId) {}
}
