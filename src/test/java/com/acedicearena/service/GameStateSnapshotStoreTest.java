package com.acedicearena.service;

import com.acedicearena.domain.GameStateRecord;
import com.acedicearena.repository.GameStateRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 统一比赛快照规格：加载共享、失效后重载一次、低频 reconcile 兜底、盲盒广播不查库。 */
class GameStateSnapshotStoreTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private GameStateSnapshotStore newStore(GameStateRepository states, BlindBoxRoundService blindBoxRounds) {
        return new GameStateSnapshotStore(states, mapper, blindBoxRounds);
    }

    @Test
    void firstReaderLoadsOnceAndConcurrentReadersShareIt() throws Exception {
        GameStateRepository states = mock(GameStateRepository.class);
        GameStateRecord record = new GameStateRecord(1L, "{\"stage\":\"ROLL\",\"teams\":[]}", "test");
        when(states.findById(1L)).thenReturn(Optional.of(record));
        GameStateSnapshotStore store = newStore(states, mock(BlindBoxRoundService.class));

        int threads = 16;
        var pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        var futures = new java.util.ArrayList<java.util.concurrent.Future<GameStateSnapshotStore.Snapshot>>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                go.await();
                return store.current();
            }));
        }
        go.countDown();
        for (var future : futures)
            assertThat(future.get(5, TimeUnit.SECONDS)).isSameAs(store.current());
        pool.shutdown();

        verify(states, times(1)).findById(1L);
        assertThat(store.current().version()).isEqualTo(1L);
        // 命中路径不查版本表
        verify(states, never()).findVersionById(anyLong());
    }

    @Test
    void invalidationReloadsOnceAndBlindRevisionChangeInvalidates() {
        GameStateRepository states = mock(GameStateRepository.class);
        GameStateRecord record = new GameStateRecord(1L, "{\"stage\":\"BLIND_BOX\",\"teams\":[]}", "test");
        when(states.findById(1L)).thenReturn(Optional.of(record));
        BlindBoxRoundService blindBoxRounds = mock(BlindBoxRoundService.class);
        AtomicLong blindRevision = new AtomicLong();
        when(blindBoxRounds.revision()).thenAnswer(inv -> blindRevision.get());
        GameStateSnapshotStore store = newStore(states, blindBoxRounds);

        GameStateSnapshotStore.Snapshot first = store.current();
        assertThat(first.blindRevision()).isZero();
        assertThat(store.current()).isSameAs(first);

        // 盲盒首开 revision +1：不查库即判定失效，下一次读取重载一次
        blindRevision.incrementAndGet();
        GameStateSnapshotStore.Snapshot reloaded = store.current();
        assertThat(reloaded).isNotSameAs(first);
        assertThat(reloaded.blindRevision()).isEqualTo(1L);
        verify(states, times(2)).findById(1L);

        // 显式失效后再读重载一次
        store.invalidate();
        assertThat(store.current()).isNotSameAs(reloaded);
        verify(states, times(3)).findById(1L);
    }

    @Test
    void reconcileInvalidatesWhenDatabaseVersionDrifts() {
        GameStateRepository states = mock(GameStateRepository.class);
        GameStateRecord record = new GameStateRecord(1L, "{\"stage\":\"ROLL\",\"teams\":[]}", "test");
        when(states.findById(1L)).thenReturn(Optional.of(record));
        AtomicLong dbVersion = new AtomicLong(1L);
        when(states.findVersionById(1L)).thenAnswer(inv -> Optional.of(dbVersion.get()));
        GameStateSnapshotStore store = newStore(states, mock(BlindBoxRoundService.class));

        GameStateSnapshotStore.Snapshot first = store.current();
        // 版本未漂移：reconcile 不动快照
        store.reconcile();
        assertThat(store.current()).isSameAs(first);

        // 人工改库/漏失效：版本漂移后 reconcile 失效快照，下一次读取重载
        dbVersion.incrementAndGet();
        store.reconcile();
        assertThat(store.current()).isNotSameAs(first);
        verify(states, times(2)).findById(1L);

        // 快照为空时 reconcile 不主动加载，保持读者驱动
        store.invalidate();
        store.reconcile();
        verify(states, times(2)).findById(1L);
    }

    @Test
    void blindBoxChangedInvalidatesSnapshotWithoutQueryingDatabase() {
        GameStateRepository states = mock(GameStateRepository.class);
        GameStateSnapshotStore store = mock(GameStateSnapshotStore.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<GameStateSnapshotStore> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(store);
        LobbyEventService events = new LobbyEventService(states, provider);

        events.blindBoxChanged();

        // 失效快照但盲盒合并广播走 version=null，不重载快照、不查版本
        verify(store).invalidate();
        verify(store, never()).current();
        verify(states, never()).findVersionById(anyLong());
        events.close();
    }
}
