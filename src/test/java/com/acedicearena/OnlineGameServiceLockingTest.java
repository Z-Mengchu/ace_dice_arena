package com.acedicearena;

import com.acedicearena.service.OnlineGameService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 并发与死锁行为的特征化测试。
 * 覆盖同一席位并发去重、满员一次性完成、roll 与倒计时到点竞争、多队并行、
 * 读写并发、subscribe 快照与并发写的交错，以及 ready 出锁发布事件的死锁回归。
 */
class OnlineGameServiceLockingTest {
    private ApplicationEventPublisher publisher;
    private OnlineGameService service;

    @BeforeEach
    void setUp() {
        publisher = mock(ApplicationEventPublisher.class);
        service = new OnlineGameService(publisher);
    }

    @Test
    void concurrentRollsOnTheSameSeatAreRecordedOnlyOnce() throws Exception {
        String[] tokens = joinTeam("t1");
        service.arm("t1");
        long goTs = service.go("t1");

        List<Throwable> errors = runConcurrent(10, () -> service.roll(tokens[0], (double) goTs));

        assertThat(errors).isEmpty();
        verify(publisher, times(1)).publishEvent(any(OnlineGameService.DiceTimingProgressEvent.class));
        verify(publisher, never()).publishEvent(any(OnlineGameService.DiceTimingReadyEvent.class));
    }

    @Test
    void fullHouseRollsTriggerTimingReadyExactlyOnce() throws Exception {
        String[] tokens = joinTeam("t1");
        service.arm("t1");
        long goTs = service.go("t1");

        List<Runnable> tasks = new ArrayList<>();
        for (String token : tokens) tasks.add(() -> service.roll(token, (double) goTs));
        List<Throwable> errors = runConcurrent(tasks);

        assertThat(errors).isEmpty();
        assertThat(service.isTimingReady("t1")).isTrue();
        verify(publisher, times(5)).publishEvent(any(OnlineGameService.DiceTimingProgressEvent.class));
        verify(publisher, times(1)).publishEvent(any(OnlineGameService.DiceTimingReadyEvent.class));
    }

    @Test
    void rollsRacingWithCountdownExpiryPublishAttackStartedExactlyOnce() throws Exception {
        List<String> lineup = List.of("u1", "u2", "u3", "u4", "u5");
        String[] tokens = joinTeam("t1", lineup);
        service.prepare("t1", "g1", 1, lineup);
        for (String token : tokens) service.ready(token, true);

        long target = service.startCountdown("t1");
        Thread.sleep(Math.max(0, target - System.currentTimeMillis()) + 50);

        List<Throwable> errors = runConcurrent(5, i -> service.roll(tokens[i], (double) target));
        Thread.sleep(500);   // 让调度线程的 markGoTs 路径落定

        assertThat(errors).isEmpty();
        verify(publisher, times(1)).publishEvent(any(OnlineGameService.DiceAttackStartedEvent.class));
        assertThat(service.isTimingReady("t1")).isTrue();
    }

    @Test
    void eightTeamsRunFullFlowInParallelWithoutInterference() throws Exception {
        List<String> teams = List.of("t1", "t2", "t3", "t4", "t5", "t6", "t7", "t8");
        List<Runnable> tasks = new ArrayList<>();
        for (String team : teams) tasks.add(() -> {
            List<String> lineup = List.of("u1", "u2", "u3", "u4", "u5");
            String[] tokens = joinTeam(team, lineup);
            service.prepare(team, "m-" + team, 1, lineup);
            for (String token : tokens) service.ready(token, true);
            service.forceStart(team);
            long now = System.currentTimeMillis();
            for (String token : tokens) service.roll(token, (double) now);
            service.finalRoll(team);
        });
        List<Throwable> errors = runConcurrent(tasks);

        assertThat(errors).isEmpty();
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(publisher, atLeastOnce()).publishEvent(captor.capture());
        List<OnlineGameService.DiceRevealEvent> reveals = captor.getAllValues().stream()
                .filter(OnlineGameService.DiceRevealEvent.class::isInstance)
                .map(OnlineGameService.DiceRevealEvent.class::cast).toList();
        assertThat(reveals).hasSize(8);
        assertThat(reveals).allMatch(e -> e.dice().size() == 5);
        assertThat(reveals.stream().map(OnlineGameService.DiceRevealEvent::teamId).distinct()).hasSize(8);
    }

    @Test
    void readersNeverObserveAPartiallyMutatedState() throws Exception {
        String[] tokens = joinTeam("t1");
        service.arm("t1");
        long goTs = service.go("t1");

        List<Runnable> tasks = new ArrayList<>();
        tasks.add(() -> { for (String token : tokens) service.roll(token, (double) goTs); });
        tasks.add(() -> { for (int i = 0; i < 200; i++) service.join("t2", i % 5 + 1, "churn" + i); });
        for (int r = 0; r < 4; r++) tasks.add(() -> {
            for (int i = 0; i < 250; i++) {
                service.stateView();
                service.isTeamReady("t1");
                service.isTimingReady("t1");
            }
        });
        List<Throwable> errors = runConcurrent(tasks);

        assertThat(errors).isEmpty();
        assertThat(service.isTimingReady("t1")).isTrue();
        assertThat(service.stateView().get("devices")).isNotNull();
    }

    @Test
    void subscribeSnapshotsStayConsistentUnderConcurrentWrites() throws Exception {
        List<String> lineup = List.of("u1", "u2", "u3", "u4", "u5");
        List<Runnable> tasks = new ArrayList<>();
        tasks.add(() -> {
            for (int i = 0; i < 50; i++) service.subscribe("host");
        });
        tasks.add(() -> {
            for (int i = 0; i < 10; i++) {
                service.prepare("t3", "m" + i, 1, lineup);
                service.go("t3");
                service.reset();
            }
        });
        List<Throwable> errors = runConcurrent(tasks);

        assertThat(errors).isEmpty();
    }

    /**
     * 死锁回归：监听器（@Transactional）模拟先取 InnoDB 行锁，另一线程持该行锁调 isTimingReady。
     * ready() 若在锁内发布事件则形成 AB-BA 死锁；出锁发布后两线程都应在限期内完成。
     */
    @Test
    void readyPublishesReadinessEventOutsideTheMonitor() throws Exception {
        ReentrantLock dbRowLock = new ReentrantLock();
        doAnswer(invocation -> {
            dbRowLock.lock();
            try { Thread.sleep(50); } finally { dbRowLock.unlock(); }
            return null;
        }).when(publisher).publishEvent(any(OnlineGameService.DiceReadinessChangedEvent.class));

        List<String> lineup = List.of("u1", "u2", "u3", "u4", "u5");
        String token = service.join("t1", 1, "队员1", "u1").token();
        calibrate(token);
        service.prepare("t1", "g1", 1, lineup);

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            CountDownLatch dbLockHeld = new CountDownLatch(1);
            Thread transactionalReader = new Thread(() -> {
                dbRowLock.lock();
                try {
                    dbLockHeld.countDown();
                    Thread.sleep(100);
                    service.isTimingReady("t1");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    dbRowLock.unlock();
                }
            });
            transactionalReader.start();
            assertThat(dbLockHeld.await(2, TimeUnit.SECONDS)).isTrue();
            service.ready(token, true);
            transactionalReader.join(2_000);
            assertThat(transactionalReader.isAlive()).isFalse();
        });
    }

    /* ---------- 构造与并发工具 ---------- */

    private String[] joinTeam(String team) {
        return joinTeam(team, null);
    }

    private String[] joinTeam(String team, List<String> playerIds) {
        String[] tokens = new String[5];
        for (int i = 0; i < 5; i++) {
            String playerId = playerIds == null ? null : playerIds.get(i);
            tokens[i] = service.join(team, i + 1, team + "-" + (i + 1), playerId).token();
            calibrate(tokens[i]);
        }
        return tokens;
    }

    private void calibrate(String token) {
        service.ping(token, (double) System.currentTimeMillis());
        service.calibrate(token, 20d);
    }

    @FunctionalInterface
    private interface ThrowingRunnable { void run(); }

    private List<Throwable> runConcurrent(int threads, ThrowingRunnable task) throws InterruptedException {
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) tasks.add(task::run);
        return runConcurrent(tasks);
    }

    private List<Throwable> runConcurrent(int threads, IntTask task) throws InterruptedException {
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int index = i;
            tasks.add(() -> task.run(index));
        }
        return runConcurrent(tasks);
    }

    @FunctionalInterface
    private interface IntTask { void run(int index); }

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
