package com.acedicearena;

import com.acedicearena.service.OnlineGameService;
import com.acedicearena.service.ParallelTournamentService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 时钟校准的防伪造约束：偏移由服务端根据自己的探测样本计算，客户端报多大的 rtt
 * 也只能在被限幅的半程范围内移动偏移；伪造的点击时刻由状态机侧夹取窗口兜底
 * （见 ParallelTournamentServiceTest 的 recordLiveRoll 用例）。
 */
class ClockCalibrationSecurityTest {

    @Test
    void calibrationRequiresServerRecordedProbes() {
        OnlineGameService service = new OnlineGameService(eligibleTournament());
        String token = service.join("alice", "u1").token();
        assertThatThrownBy(() -> service.calibrate(token, 20d))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("时钟探测样本不足，请重新校准");
    }

    @Test
    void oversizedRoundTripHintIsCappedSoItCannotDragTheOffset() {
        OnlineGameService service = new OnlineGameService(eligibleTournament());
        String token = service.join("alice", "u1").token();
        long c0 = System.currentTimeMillis();
        service.ping(token, (double) c0);
        var honest = service.calibrate(token, 0d);
        var forged = service.calibrate(token, 60_000d);

        assertThat(forged.rtt()).isEqualTo(300d);
        assertThat(honest.offset() - forged.offset()).isEqualTo(150d);
    }

    @Test
    void aTokenBoundToAPlayerCannotBeCalibratedByAnotherAccount() {
        OnlineGameService service = new OnlineGameService(eligibleTournament());
        String token = service.join("alice", "u1").token();
        assertThat(service.ownsDevice(token, "u1")).isTrue();
        assertThat(service.ownsDevice(token, "u2")).isFalse();
    }

    @Test
    void colludedClientTimestampsAreNotClampedLocallyButDelegatedForServerSideClamping() {
        ParallelTournamentService tournament = eligibleTournament();
        when(tournament.recordLiveRoll(eq("alice"), anyLong()))
                .thenReturn(new ParallelTournamentService.LiveRoll(3, 1L));
        OnlineGameService service = new OnlineGameService(tournament);
        String token = service.join("alice", "u1").token();
        service.ping(token, (double) System.currentTimeMillis());
        var calibration = service.calibrate(token, 20d);

        // 客户端上报一个远古的伪造时刻：令牌层只加偏移，夹取交给状态机窗口守卫
        double forged = System.currentTimeMillis() - 5_000d;
        service.roll(token, forged);

        ArgumentCaptor<Long> captor = ArgumentCaptor.forClass(Long.class);
        verify(tournament).recordLiveRoll(eq("alice"), captor.capture());
        assertThat(captor.getValue()).isEqualTo(Math.round(forged + calibration.offset()));
    }

    private ParallelTournamentService eligibleTournament() {
        ParallelTournamentService tournament = mock(ParallelTournamentService.class);
        when(tournament.rollAssignment(anyString())).thenReturn(
                new ParallelTournamentService.RollAssignmentView(true, "ROLL", 1_000L, 31_000L, false, "t1",
                        0, 1_000L, 16_000L));
        return tournament;
    }
}
