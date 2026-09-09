package com.acedicearena;

import com.acedicearena.service.OnlineGameService;
import com.acedicearena.service.ParallelTournamentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** 新令牌模型的语义测试：ROLL 资格门槛、重复 join 轮换令牌、令牌归属与校准前置。 */
class OnlineGameServiceConcurrentTest {
    private ParallelTournamentService tournament;
    private OnlineGameService service;

    @BeforeEach
    void setUp() {
        tournament = mock(ParallelTournamentService.class);
        when(tournament.rollAssignment(anyString())).thenReturn(
                new ParallelTournamentService.RollAssignmentView(true, "ROLL", 1_000L, 31_000L, false, "t1",
                        0, 1_000L, 16_000L));
        service = new OnlineGameService(tournament);
    }

    @Test
    void joinRequiresAnEligibleRollWindow() {
        when(tournament.rollAssignment("bob")).thenReturn(
                new ParallelTournamentService.RollAssignmentView(false, "TACTICS", null, null, false, "t1",
                        null, null, null));
        assertThatThrownBy(() -> service.join("bob", "u2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("当前不在掷骰阶段或本队本轮没有比赛");
    }

    @Test
    void rejoiningRotatesTheTokenAndTheOldOneStopsWorking() {
        String oldToken = service.join("alice", "u1").token();
        service.ping(oldToken, (double) System.currentTimeMillis());
        service.calibrate(oldToken, 20d);
        String newToken = service.join("alice", "u1").token();

        assertThat(newToken).isNotEqualTo(oldToken);
        assertThatThrownBy(() -> service.calibrate(oldToken, 20d)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> service.roll(oldToken, (double) System.currentTimeMillis()))
                .isInstanceOf(SecurityException.class);

        service.ping(newToken, (double) System.currentTimeMillis());
        service.calibrate(newToken, 20d);
        when(tournament.recordLiveRoll(eq("alice"), anyLong()))
                .thenReturn(new ParallelTournamentService.LiveRoll(2, 1L));
        assertThat(service.roll(newToken, (double) System.currentTimeMillis()).die()).isEqualTo(2);
    }

    @Test
    void aTokenBoundToAPlayerCannotBeUsedByAnotherAccount() {
        String token = service.join("alice", "u1").token();
        assertThat(service.ownsDevice(token, "u1")).isTrue();
        assertThat(service.ownsDevice(token, "u2")).isFalse();
        assertThat(service.ownsDevice("nonexistent", "u1")).isFalse();
    }

    @Test
    void calibrationRequiresServerRecordedProbes() {
        String token = service.join("alice", "u1").token();
        assertThatThrownBy(() -> service.calibrate(token, 20d))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("时钟探测样本不足，请重新校准");
    }

    @Test
    void aUniqueKeyConflictOnRollFallsBackToTheRecordedRow() {
        String token = service.join("alice", "u1").token();
        when(tournament.recordLiveRoll(eq("alice"), anyLong()))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate"));
        when(tournament.recordedLiveRoll("alice"))
                .thenReturn(new ParallelTournamentService.LiveRoll(3, 42L));

        ParallelTournamentService.LiveRoll roll = service.roll(token, (double) System.currentTimeMillis());

        assertThat(roll.die()).isEqualTo(3);
        assertThat(roll.rollTs()).isEqualTo(42L);
    }

    @Test
    void aUniqueKeyConflictWithoutARecordedRowIsRethrown() {
        String token = service.join("alice", "u1").token();
        when(tournament.recordLiveRoll(eq("alice"), anyLong()))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate"));

        assertThatThrownBy(() -> service.roll(token, (double) System.currentTimeMillis()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void rollRejectsInvalidClientTimestamps() {
        String token = service.join("alice", "u1").token();
        assertThatThrownBy(() -> service.roll(token, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.roll(token, Double.NaN)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theCalibratedOffsetIsAppliedBeforeDelegatingToTheTournament() {
        String token = service.join("alice", "u1").token();
        long c0 = System.currentTimeMillis();
        service.ping(token, (double) c0);
        var calibration = service.calibrate(token, 20d);
        when(tournament.recordLiveRoll(eq("alice"), anyLong()))
                .thenReturn(new ParallelTournamentService.LiveRoll(5, 1L));

        double clientTs = System.currentTimeMillis();
        service.roll(token, clientTs);

        ArgumentCaptor<Long> captor = ArgumentCaptor.forClass(Long.class);
        verify(tournament).recordLiveRoll(eq("alice"), captor.capture());
        assertThat(captor.getValue()).isEqualTo(Math.round(clientTs + calibration.offset()));
    }
}
