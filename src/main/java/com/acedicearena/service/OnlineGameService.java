package com.acedicearena.service;

import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 联机掷骰的校准与令牌层：ROLL 阶段给在赛队员发令牌，维护时钟探测样本与偏移，
 * 掷骰本身转调 {@link ParallelTournamentService#recordLiveRoll} 落库。
 */
@Service
public class OnlineGameService {
    /**
     * 客户端上报的往返延迟只作为偏移的半程修正，且必须落在这个上限内，避免用超大 rtt 撬动偏移。
     */
    private static final long MAX_RTT_HINT_MS = 300L;

    private final Map<String, Device> devices = new LinkedHashMap<>();
    /**
     * 每个令牌的时钟探测记录，全部由服务端时间写入，客户端无法直接指定偏移。
     */
    private final Map<String, ClockProbe> probes = new LinkedHashMap<>();
    /**
     * 按用户名一令牌：重复 join 轮换令牌，旧令牌立即失效。
     */
    private final Map<String, String> tokensByUsername = new HashMap<>();

    private final ParallelTournamentService tournament;

    public OnlineGameService(ParallelTournamentService tournament) {
        this.tournament = tournament;
    }

    public synchronized Map<String, Object> stateView() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("devices", devices.values().stream()
                .map(d -> new DeviceView(d.username(), d.rtt(), d.calibrated())).toList());
        return result;
    }

    /**
     * ROLL 阶段在赛队伍成员领取掷骰令牌。资格判断在监视器外完成（只读事务），
     * 令牌轮换在监视器内完成，保证同一用户名并发 join 只会有一个生效令牌。
     */
    public JoinResult join(String username, String playerId) {
        if (!tournament.rollAssignment(username).eligible())
            throw new IllegalStateException("当前不在掷骰阶段或本队本轮没有比赛");
        synchronized (this) {
            String replaced = tokensByUsername.put(username, null);
            if (replaced != null) devices.remove(replaced);
            String token = UUID.randomUUID().toString();
            devices.put(token, new Device(username, playerId, 0, null, false));
            tokensByUsername.put(username, token);
            pruneProbes();
            return new JoinResult(token);
        }
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
        devices.put(token, new Device(old.username(), old.playerId(), offset, rtt, true));
        return new Calibration(offset, rtt);
    }

    /**
     * 掷骰：令牌校验与偏移归一化在监视器内完成，落库调用在监视器外执行——
     * recordLiveRoll 是 @Transactional 会取行锁，持监视器等行锁会形成 AB-BA 死锁。
     */
    public ParallelTournamentService.LiveRoll roll(String token, Double clientTs) {
        Device device;
        long normalized;
        synchronized (this) {
            device = requireDevice(token);
            if (clientTs == null || !Double.isFinite(clientTs)) throw new IllegalArgumentException("invalid clientTs");
            normalized = Math.round(clientTs + device.offset());
        }
        return tournament.recordLiveRoll(device.username(), normalized);
    }

    /**
     * 校准前的令牌归属校验：令牌一旦绑定了玩家，就只有该玩家本人可以继续使用它。
     */
    public synchronized boolean ownsDevice(String token, String playerId) {
        Device device = devices.get(token);
        return device != null && (device.playerId() == null || device.playerId().equals(playerId));
    }

    private Device requireDevice(String token) {
        Device device = token == null ? null : devices.get(token);
        if (device == null) throw new SecurityException("invalid token");
        return device;
    }

    private void pruneProbes() {
        probes.keySet().retainAll(devices.keySet());
    }

    private record Device(String username, String playerId, double offset, Double rtt, boolean calibrated) {
    }

    /**
     * 服务端侧时钟探测：只保留 (收包时刻 - c0) 的最小值，即真实时钟偏移的上界。
     */
    private static final class ClockProbe {
        private Double minDelta;

        private void record(double delta) {
            if (minDelta == null || delta < minDelta) minDelta = delta;
        }

        private boolean hasSample() {
            return minDelta != null;
        }

        private double minDelta() {
            return minDelta;
        }
    }

    public record Calibration(double offset, double rtt) {
    }

    public record DeviceView(String username, Double rtt, boolean calibrated) {
    }

    public record JoinResult(String token) {
    }
}
