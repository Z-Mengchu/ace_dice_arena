package com.acedicearena;

import com.acedicearena.service.OnlineGameService;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OnlineGameServiceConcurrentTest {
    @Test
    void missingPreparationSessionCanBeRecoveredWithoutResettingAnExistingSession() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        OnlineGameService service = new OnlineGameService(publisher);
        var lineup = java.util.List.of("u1", "u2", "u3", "u4", "u5");
        String token = service.join("t1", 1, "队员1", "u1").token();
        calibrate(service, token);

        assertThatThrownBy(() -> service.ready(token, true)).hasMessage("当前不在备战准备阶段");

        service.ensurePrepared("t1", lineup);
        service.ready(token, true);
        service.ensurePrepared("t1", lineup);

        assertThat(service.stateView().get("devices").toString()).contains("ready=true");
    }

    @Test
    void fivePlayersMustPrepareBeforeCaptainStartsThreeSecondCountdown() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        OnlineGameService service = new OnlineGameService(publisher);
        var lineup = java.util.List.of("u1", "u2", "u3", "u4", "u5");
        service.prepare("t1", lineup);
        String[] tokens = new String[5];
        for (int i = 0; i < 5; i++) {
            tokens[i] = service.join("t1", i + 1, "队员" + (i + 1), lineup.get(i)).token();
            calibrate(service, tokens[i]);
            if (i < 4) service.ready(tokens[i], true);
        }

        assertThat(service.isTeamReady("t1")).isFalse();
        assertThatThrownBy(() -> service.startCountdown("t1")).hasMessage("必须等待五名出战队员全部准备");
        service.ready(tokens[4], true);
        assertThat(service.isTeamReady("t1")).isTrue();

        long before = System.currentTimeMillis();
        long goAt = service.startCountdown("t1");
        assertThat(goAt - before).isBetween(2_900L, 3_100L);
        verify(publisher, timeout(4_000)).publishEvent(any(OnlineGameService.DiceAttackStartedEvent.class));
    }

    @Test
    void twoTeamsCanRollAtTheSameTime() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        OnlineGameService service = new OnlineGameService(publisher);
        String[] t1 = join(service, "t1");
        String[] t3 = join(service, "t3");

        service.arm("t1"); service.arm("t3");
        long go1 = service.go("t1"), go3 = service.go("t3");
        for (int i = 0; i < 5; i++) {
            service.roll(t1[i], (double) (go1 + i * 30));
            service.roll(t3[i], (double) (go3 + i * 35));
        }

        assertThat(service.stateView().get("rolling")).isEqualTo(false);
        verify(publisher, times(10)).publishEvent(any(OnlineGameService.DiceTimingProgressEvent.class));
        verify(publisher, times(2)).publishEvent(any(OnlineGameService.DiceTimingReadyEvent.class));
        verify(publisher, never()).publishEvent(any(OnlineGameService.DiceRevealEvent.class));

        service.finalRoll("t1"); service.finalRoll("t3");
        verify(publisher, times(2)).publishEvent(any(OnlineGameService.DiceRevealEvent.class));
    }

    private String[] join(OnlineGameService service, String team) {
        String[] tokens = new String[5];
        for (int i = 0; i < 5; i++) {
            tokens[i] = service.join(team, i + 1, team + "-" + (i + 1)).token();
            calibrate(service, tokens[i]);
        }
        return tokens;
    }

    /** 走真实流程：先探测再校准，偏移由服务端根据自己的收包时刻算出。 */
    private void calibrate(OnlineGameService service, String token) {
        service.ping(token, (double) System.currentTimeMillis());
        service.calibrate(token, 20d);
    }
}
