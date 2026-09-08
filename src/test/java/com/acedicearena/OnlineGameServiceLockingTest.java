package com.acedicearena;

import com.acedicearena.service.OnlineGameService;
import com.acedicearena.service.ParallelTournamentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 新令牌模型的并发与死锁行为特征化测试。
 * 覆盖同一账号并发 join 只留一个生效令牌、并发 roll 只记一次（重复由状态机拒绝）、
 * roll 转调状态机时不持有监视器（死锁回归），以及多队员并行掷骰互不干扰。
 */
class OnlineGameServiceLockingTest {
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
    void concurrentJoinsForTheSameAccountLeaveExactlyOneLiveToken() throws Exception {
        List<String> tokens = new ArrayList<>();
        List<Throwable> errors = runConcurrent(10,
                () -> { synchronized (tokens) { tokens.add(service.join("alice", "u1").token()); } });

        assertThat(errors).isEmpty();
        assertThat(tokens).hasSize(10);
        long live = tokens.stream().filter(token -> {
            try { service.ping(token, (double) System.currentTimeMillis()); service.calibrate(token, 20d); return true; }
            catch (SecurityException dead) { return false; }
        }).count();
        assertThat(live).as("同一账号并发 join 后只应有一个生效令牌").isEqualTo(1);
    }

    @Test
    void concurrentRollsOnTheSameTokenAreRecordedOnlyOnce() throws Exception {
        String token = joinAndCalibrate("alice", "u1");
        AtomicInteger calls = new AtomicInteger();
        when(tournament.recordLiveRoll(eq("alice"), anyLong())).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) return new ParallelTournamentService.LiveRoll(4, 123L);
            throw new IllegalStateException("你本轮已经掷过骰子");
        });

        AtomicInteger successes = new AtomicInteger();
        List<Throwable> errors = runConcurrent(10, () -> {
            try {
                service.roll(token, (double) System.currentTimeMillis());
                successes.incrementAndGet();
            } catch (IllegalStateException duplicate) { /* 状态机的重复拒绝 */ }
        });

        assertThat(errors).isEmpty();
        assertThat(successes.get()).isEqualTo(1);
        verify(tournament, times(10)).recordLiveRoll(eq("alice"), anyLong());
    }

    /**
     * 死锁回归：recordLiveRoll 是 @Transactional 会取行锁；roll 若持监视器等行锁，
     * 与"事务内持行锁再调本服务 synchronized 方法"的线程形成 AB-BA 死锁。
     * 转调必须在监视器外执行：recordLiveRoll 阻塞期间 ping 应能立即完成。
     */
    @Test
    void rollDelegatesToTheTournamentOutsideTheMonitor() throws Exception {
        String token = joinAndCalibrate("alice", "u1");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(tournament.recordLiveRoll(anyString(), anyLong())).thenAnswer(invocation -> {
            entered.countDown();
            assertThat(release.await(2, TimeUnit.SECONDS)).isTrue();
            return new ParallelTournamentService.LiveRoll(3, 1L);
        });

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            Thread roller = new Thread(() -> service.roll(token, (double) System.currentTimeMillis()));
            roller.start();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            service.ping(token, (double) System.currentTimeMillis());   // 监视器被持有时会卡在这里
            release.countDown();
            roller.join(2_000);
            assertThat(roller.isAlive()).isFalse();
        });
    }

    @Test
    void manyPlayersRollInParallelWithoutInterference() throws Exception {
        when(tournament.recordLiveRoll(anyString(), anyLong()))
                .thenReturn(new ParallelTournamentService.LiveRoll(6, 1L));
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            String username = "player" + i;
            tasks.add(() -> {
                String token = joinAndCalibrate(username, "u-" + username);
                service.roll(token, (double) System.currentTimeMillis());
            });
        }
        List<Throwable> errors = runConcurrent(tasks);

        assertThat(errors).isEmpty();
        verify(tournament, times(30)).recordLiveRoll(anyString(), anyLong());
        assertThat(service.stateView().get("devices")).isNotNull();
    }

    /* ---------- 构造与并发工具 ---------- */

    private String joinAndCalibrate(String username, String playerId) {
        String token = service.join(username, playerId).token();
        service.ping(token, (double) System.currentTimeMillis());
        service.calibrate(token, 20d);
        return token;
    }

    @FunctionalInterface
    private interface ThrowingRunnable { void run(); }

    private List<Throwable> runConcurrent(int threads, ThrowingRunnable task) throws InterruptedException {
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) tasks.add(task::run);
        return runConcurrent(tasks);
    }

    private List<Throwable> runConcurrent(List<Runnable> tasks) throws InterruptedException {
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(tasks.size());
        List<Throwable> errors = new ArrayList<>();
        for (Runnable task : tasks) {
            new Thread(() -> {
                try {
                    gate.await();
                    task.run();
                } catch (Throwable t) {
                    synchronized (errors) { errors.add(t); }
                } finally {
                    done.countDown();
                }
            }).start();
        }
        gate.countDown();
        assertThat(done.await(15, TimeUnit.SECONDS)).as("所有并发任务应在 15 秒内完成").isTrue();
        return errors;
    }
}
