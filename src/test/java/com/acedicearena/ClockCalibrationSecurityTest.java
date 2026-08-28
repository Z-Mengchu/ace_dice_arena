package com.acedicearena;

import com.acedicearena.service.OnlineGameService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** 时钟校准与同步判定的防伪造约束：偏移由服务端计算，点击时刻必须落在服务端可验证的窗口内。 */
class ClockCalibrationSecurityTest {

    @Test
    void fiveClientsReportingOneColludedTimestampDoNotEarnTheSyncBonus() throws Exception {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        OnlineGameService service = new OnlineGameService(publisher);
        String[] tokens = joinAndCalibrate(service, "t1");
        service.arm("t1");
        service.go("t1");

        // 五人实际点击时间散得很开（跨度约 800ms），但客户端串通后统一上报同一个伪造时刻。
        double forged = System.currentTimeMillis() - 5_000d;
        for (int i = 0; i < 5; i++) {
            service.roll(tokens[i], forged);
            if (i < 4) Thread.sleep(200);
        }

        OnlineGameService.DiceTimingReadyEvent timing = timingReady(publisher);
        assertThat(timing.syncOk()).isFalse();
        assertThat(timing.spreadMs()).isGreaterThan(500d);
    }

    @Test
    void honestClicksInsideTheWindowStillEarnTheSyncBonus() throws Exception {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        OnlineGameService service = new OnlineGameService(publisher);
        String[] tokens = joinAndCalibrate(service, "t1");
        service.arm("t1");
        service.go("t1");
        Thread.sleep(40); // 口令下达到五人真正按下之间的反应时间，正常局都不会是 0
        for (int i = 0; i < 5; i++) service.roll(tokens[i], (double) System.currentTimeMillis());

        OnlineGameService.DiceTimingReadyEvent timing = timingReady(publisher);
        assertThat(timing.syncOk()).isTrue();
        assertThat(timing.spreadMs()).isLessThanOrEqualTo(500d);
    }

    @Test
    void calibrationRequiresServerRecordedProbes() {
        OnlineGameService service = new OnlineGameService(mock(ApplicationEventPublisher.class));
        String token = service.join("t1", 1, "队员1", "u1").token();
        assertThatThrownBy(() -> service.calibrate(token, 20d))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("时钟探测样本不足，请重新校准");
    }

    @Test
    void oversizedRoundTripHintIsCappedSoItCannotDragTheOffset() {
        OnlineGameService service = new OnlineGameService(mock(ApplicationEventPublisher.class));
        String token = service.join("t1", 1, "队员1", "u1").token();
        long c0 = System.currentTimeMillis();
        service.ping(token, (double) c0);
        var honest = service.calibrate(token, 0d);
        var forged = service.calibrate(token, 60_000d);

        assertThat(forged.rtt()).isEqualTo(300d);
        assertThat(honest.offset() - forged.offset()).isEqualTo(150d);
    }

    @Test
    void aDeviceBoundToAPlayerCannotBeCalibratedByAnotherAccount() {
        OnlineGameService service = new OnlineGameService(mock(ApplicationEventPublisher.class));
        String token = service.join("t1", 1, "队员1", "u1").token();
        assertThat(service.ownsDevice(token, "u1")).isTrue();
        assertThat(service.ownsDevice(token, "u2")).isFalse();
    }

    /** publishEvent(Object) 会收到进度和判定两类事件，这里只取同步判定结果。 */
    private OnlineGameService.DiceTimingReadyEvent timingReady(ApplicationEventPublisher publisher) {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(publisher, atLeastOnce()).publishEvent(captor.capture());
        return captor.getAllValues().stream()
                .filter(OnlineGameService.DiceTimingReadyEvent.class::isInstance)
                .map(OnlineGameService.DiceTimingReadyEvent.class::cast)
                .findFirst().orElseThrow();
    }

    private String[] joinAndCalibrate(OnlineGameService service, String team) {
        String[] tokens = new String[5];
        for (int i = 0; i < 5; i++) {
            tokens[i] = service.join(team, i + 1, team + "-" + (i + 1), "u" + (i + 1)).token();
            service.ping(tokens[i], (double) System.currentTimeMillis());
            service.calibrate(tokens[i], 20d);
        }
        return tokens;
    }
}
