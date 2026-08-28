package com.acedicearena.service;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

@Service
public class OnlineGameService {
    private static final Set<String> TEAM_IDS = Set.of("t1", "t2", "t3", "t4", "t5", "t6", "t7", "t8");
    private static final int SLOT_COUNT = 5;
    private static final long SYNC_WINDOW_MS = 500;
    private static final long RECONNECT_TIME_MS = 3_000L;
    private static final long HEARTBEAT_INTERVAL_SECONDS = 25L;

    private final Map<String, Device> devices = new LinkedHashMap<>();
    private final Map<String, TeamRollSession> sessions = new LinkedHashMap<>();
    private final Set<SseEmitter> emitters = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "dice-reveal-timer");
        thread.setDaemon(true);
        return thread;
    });

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
        return result;
    }

    public synchronized JoinResult join(String teamId, Integer slot, String name) {
        return join(teamId, slot, name, null);
    }

    public synchronized JoinResult join(String teamId, Integer slot, String name, String playerId) {
        if (!TEAM_IDS.contains(teamId) || slot == null || slot < 1 || slot > SLOT_COUNT) {
            throw new IllegalArgumentException("invalid teamId or slot");
        }
        devices.entrySet().removeIf(e -> e.getValue().teamId().equals(teamId) && e.getValue().slot() == slot);
        String token = UUID.randomUUID().toString();
        devices.put(token, new Device(teamId, slot, limit(name, 32), playerId, 0, null, false, false));
        broadcast(Map.of("type", "roster", "devices", deviceViews()));
        return new JoinResult(token, teamId, slot);
    }

    public synchronized void calibrate(String token, Double offset, Double rtt) {
        Device old = requireDevice(token);
        if (offset == null || rtt == null || !Double.isFinite(offset) || !Double.isFinite(rtt)) {
            throw new IllegalArgumentException("invalid offset or rtt");
        }
        devices.put(token, new Device(old.teamId(), old.slot(), old.name(), old.playerId(), offset, rtt, true, old.ready()));
        broadcast(Map.of("type", "roster", "devices", deviceViews()));
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

    public synchronized void prepare(String teamId, List<String> lineup) {
        if (!TEAM_IDS.contains(teamId) || lineup == null || lineup.size() != SLOT_COUNT)
            throw new IllegalArgumentException("invalid lineup");
        devices.entrySet().removeIf(entry -> {
            Device device = entry.getValue();
            return teamId.equals(device.teamId())
                    && (device.playerId() == null || !device.playerId().equals(lineup.get(device.slot() - 1)));
        });
        devices.replaceAll((token, device) -> teamId.equals(device.teamId())
                ? new Device(device.teamId(), device.slot(), device.name(), device.playerId(), device.offset(), device.rtt(), device.calibrated(), false)
                : device);
        sessions.put(teamId, new TeamRollSession(List.copyOf(lineup)));
        broadcast(Map.of("type", "prepare", "teamId", teamId));
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
        sessions.put(teamId, new TeamRollSession(List.of()));
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
        devices.entrySet().removeIf(entry -> {
            Device device = entry.getValue();
            return teamId.equals(device.teamId())
                    && (device.playerId() == null || !device.playerId().equals(lineup.get(device.slot() - 1)));
        });
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
        Device device = requireDevice(token);
        TeamRollSession session = sessions.get(device.teamId());
        if (session == null || session.timingReady || session.goTs == null) throw new IllegalStateException("not armed");
        if (clientTs == null || !Double.isFinite(clientTs)) throw new IllegalArgumentException("invalid clientTs");
        boolean recorded = session.rolls.putIfAbsent(device.slot(), new Roll(device.slot(), clientTs)) == null;
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
            Device device = findDevice(teamId, slot);
            Double normalized = roll == null ? null : roll.clientTs() + (device == null ? 0 : device.offset());
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

    public synchronized boolean matchesAssignment(String token, String teamId, int slot) {
        Device device = devices.get(token);
        return device != null && device.teamId().equals(teamId) && device.slot() == slot;
    }

    public synchronized void reset() {
        sessions.clear(); lastArmedTeam = null;
        broadcast(Map.of("type", "reset"));
    }

    public synchronized SseEmitter subscribe(String token) {
        if (!"host".equals(token) && !devices.containsKey(token)) throw new SecurityException("unknown token");
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        send(emitter, SseEmitter.event().reconnectTime(RECONNECT_TIME_MS).comment("connected"));
        send(emitter, Map.of("type", "roster", "devices", deviceViews()));
        sessions.forEach((team, session) -> {
            if (session.countdownAt == null) send(emitter, Map.of("type", "prepare", "teamId", team));
            else if (session.goTs == null) send(emitter, Map.of("type", "countdown", "teamId", team, "goTs", session.countdownAt));
            else send(emitter, Map.of("type", "arm", "teamId", team));
        });
        sessions.forEach((team, session) -> {
            if (session.timingReady) send(emitter, Map.of("type", "timing-ready", "teamId", team,
                    "spreadMs", session.spreadMs, "syncOk", session.syncOk));
        });
        return emitter;
    }

    private synchronized void completeTiming(String teamId) {
        TeamRollSession session = sessions.get(teamId);
        if (session == null || session.timingReady) return;
        List<Double> timestamps = new ArrayList<>();
        int earlyCount = 0;
        for (int slot = 1; slot <= SLOT_COUNT; slot++) {
            Roll roll = session.rolls.get(slot);
            if (roll != null) {
                Device device = findDevice(teamId, slot);
                double normalized = roll.clientTs() + (device == null ? 0 : device.offset());
                if (session.goTs != null && normalized < session.goTs) earlyCount++;
                timestamps.add(normalized);
            }
        }
        Double spread = timestamps.isEmpty() ? null : Collections.max(timestamps) - Collections.min(timestamps);
        boolean syncOk = timestamps.size() >= SLOT_COUNT && spread != null && spread <= SYNC_WINDOW_MS && earlyCount == 0;
        session.timingReady = true; session.spreadMs = spread; session.syncOk = syncOk;
        broadcast(Map.of("type", "timing-ready", "teamId", teamId, "spreadMs", spread, "syncOk", syncOk));
        publisher.publishEvent(new DiceTimingReadyEvent(teamId, syncOk, spread));
    }

    private Device requireDevice(String token) {
        Device device = devices.get(token);
        if (device == null) throw new SecurityException("invalid token");
        return device;
    }

    private Device findDevice(String teamId, int slot) {
        return devices.values().stream().filter(d -> d.teamId().equals(teamId) && d.slot() == slot).findFirst().orElse(null);
    }

    private List<DeviceView> deviceViews() {
        return devices.values().stream().map(d -> new DeviceView(d.teamId(), d.slot(), d.name(), d.playerId(), d.rtt(), d.calibrated(), d.ready())).toList();
    }

    private void broadcast(Object event) {
        for (SseEmitter emitter : List.copyOf(emitters)) send(emitter, event);
    }

    private void send(SseEmitter emitter, Object event) {
        try { emitter.send(SseEmitter.event().data(event)); }
        catch (IOException | IllegalStateException e) { emitters.remove(emitter); emitter.complete(); }
    }

    private void send(SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try { emitter.send(event); }
        catch (IOException | IllegalStateException e) { emitters.remove(emitter); emitter.complete(); }
    }

    private void heartbeat() {
        for (SseEmitter emitter : List.copyOf(emitters)) {
            send(emitter, SseEmitter.event().comment("keepalive"));
        }
    }

    private String limit(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }

    @PreDestroy
    void close() { scheduler.shutdownNow(); }

    private record Device(String teamId, int slot, String name, String playerId, double offset, Double rtt, boolean calibrated, boolean ready) {}
    private record Roll(int slot, double clientTs) {}
    private static class TeamRollSession {
        private final List<String> lineup;
        private final Map<Integer, Roll> rolls = new HashMap<>();
        private boolean timingReady;
        private boolean syncOk;
        private Double spreadMs;
        private Long goTs;
        private Long countdownAt;
        private TeamRollSession(List<String> lineup) { this.lineup = lineup; }
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
