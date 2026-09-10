package com.acedicearena.service;

import com.acedicearena.domain.RequestAudit;
import com.acedicearena.repository.RequestAuditRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.mock;

class RequestAuditWriterTest {

    private final RequestAuditRepository repository = mock(RequestAuditRepository.class);

    private RequestAuditWriter newWriter(int queueCapacity, long slowThresholdMs) {
        return new RequestAuditWriter(repository, queueCapacity, slowThresholdMs);
    }

    private RequestAudit audit(int status, long durationMs) {
        return new RequestAudit("GET", "/api/game/state", "alice", "127.0.0.1",
                status, durationMs, Instant.now());
    }

    @Test
    void fastSuccessRequestIsAggregatedOnlyAndNotQueuedForPersist() {
        RequestAuditWriter writer = newWriter(1000, 500);
        writer.submit(audit(200, 10));
        assertEquals(0, writer.pendingPersisted());
        writer.flushAggregates();
    }

    @Test
    void slowSuccessRequestIsQueuedForPersist() {
        RequestAuditWriter writer = newWriter(1000, 500);
        writer.submit(audit(200, 600));
        assertEquals(1, writer.pendingPersisted());
    }

    @Test
    void serverErrorIsQueuedForPersistEvenWhenFast() {
        RequestAuditWriter writer = newWriter(1000, 500);
        writer.submit(audit(500, 5));
        assertEquals(1, writer.pendingPersisted());
    }

    @Test
    void clientErrorBelowThresholdIsAggregatedOnly() {
        RequestAuditWriter writer = newWriter(1000, 500);
        writer.submit(audit(409, 20));
        assertEquals(0, writer.pendingPersisted());
    }

    @Test
    void fullQueueDropsImmediatelyWithoutBlockingCaller() {
        RequestAuditWriter writer = newWriter(1000, 500);
        for (int i = 0; i < 1000; i++) {
            writer.submit(audit(500, 1));
        }
        assertEquals(1000, writer.pendingPersisted());
        assertTimeoutPreemptively(java.time.Duration.ofMillis(200), () -> {
            for (int i = 0; i < 100; i++) {
                writer.submit(audit(500, 1));
            }
        });
        assertEquals(100, writer.droppedCount());
    }

    @Test
    void flushResetsBucketsAndDroppedCounter() {
        RequestAuditWriter writer = newWriter(1000, 500);
        for (int i = 0; i < 1005; i++) {
            writer.submit(audit(500, 1));
        }
        assertEquals(5, writer.droppedCount());
        writer.flushAggregates();
        assertEquals(0, writer.droppedCount());
        // 再次 flush 空聚合不会抛异常。
        writer.flushAggregates();
    }

    @Test
    void thresholdIsConfigurable() {
        RequestAuditWriter writer = newWriter(1000, 100);
        writer.submit(audit(200, 150));
        assertEquals(1, writer.pendingPersisted());
    }
}
