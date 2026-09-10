package com.acedicearena.service;

import com.acedicearena.domain.RequestAudit;
import com.acedicearena.repository.RequestAuditRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

@Service
public class RequestAuditWriter {
    private static final Logger log = LoggerFactory.getLogger(RequestAuditWriter.class);

    private final RequestAuditRepository repository;
    private final BlockingQueue<RequestAudit> queue;
    private final long slowThresholdMs;
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong skipped = new AtomicLong();
    private volatile Map<BucketKey, Bucket> buckets = new ConcurrentHashMap<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "request-audit-writer");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean running = true;

    public RequestAuditWriter(RequestAuditRepository repository,
                              @Value("${app.audit.queue-capacity:50000}") int queueCapacity,
                              @Value("${app.audit.slow-threshold-ms:500}") long slowThresholdMs) {
        this.repository = repository;
        this.queue = new ArrayBlockingQueue<>(Math.max(1000, queueCapacity));
        this.slowThresholdMs = slowThresholdMs;
    }

    @PostConstruct
    void start() {
        worker.execute(this::writeLoop);
    }

    /**
     * 所有请求先进入内存分钟级聚合；只有慢请求和 5xx 逐条落库。
     * 入队为无等待 offer，队列满立即丢弃并计数，绝不阻塞请求线程。
     */
    public void submit(RequestAudit audit) {
        aggregate(audit);
        if (audit.getStatusCode() < 500 && audit.getDurationMs() < slowThresholdMs) {
            skipped.incrementAndGet();
            return;
        }
        if (!queue.offer(audit)) {
            dropped.incrementAndGet();
        }
    }

    private void aggregate(RequestAudit audit) {
        Bucket bucket = buckets.computeIfAbsent(BucketKey.of(audit), key -> new Bucket());
        bucket.count.increment();
        bucket.totalMs.add(audit.getDurationMs());
        bucket.maxMs.accumulateAndGet(audit.getDurationMs(), Math::max);
    }

    /**
     * 每分钟把聚合结果写到应用日志并重置；普通成功请求不再逐条写 MySQL。
     */
    @Scheduled(fixedRate = 60_000)
    void flushAggregates() {
        Map<BucketKey, Bucket> snapshot = buckets;
        buckets = new ConcurrentHashMap<>();
        long droppedCount = dropped.getAndSet(0);
        long skippedCount = skipped.getAndSet(0);
        if (snapshot.isEmpty() && droppedCount == 0 && skippedCount == 0) {
            return;
        }
        snapshot.forEach((key, bucket) -> {
            long count = bucket.count.sum();
            long total = bucket.totalMs.sum();
            log.info("request-audit aggregate minute={} {} {} {}xx count={} avgMs={} maxMs={}",
                    key.minute, key.method, key.path, key.statusClass, count,
                    count == 0 ? 0 : total / count, bucket.maxMs.get());
        });
        log.info("request-audit aggregated-only={} dropped(queue full)={}", skippedCount, droppedCount);
    }

    /** 测试与运维观测用。 */
    long droppedCount() {
        return dropped.get();
    }

    /** 测试与运维观测用。 */
    int pendingPersisted() {
        return queue.size();
    }

    record BucketKey(String minute, String method, String path, int statusClass) {
        static BucketKey of(RequestAudit audit) {
            long epochMinute = audit.getRequestedAt().getEpochSecond() / 60;
            return new BucketKey(String.valueOf(epochMinute), audit.getMethod(),
                    audit.getPath(), audit.getStatusCode() / 100);
        }
    }

    static final class Bucket {
        final LongAdder count = new LongAdder();
        final LongAdder totalMs = new LongAdder();
        final AtomicLong maxMs = new AtomicLong();
    }

    private void writeLoop() {
        List<RequestAudit> batch = new ArrayList<>(200);
        while (running || !queue.isEmpty()) {
            try {
                RequestAudit first = queue.poll(500, TimeUnit.MILLISECONDS);
                if (first == null) continue;
                batch.add(first);
                queue.drainTo(batch, 199);
                repository.saveAll(batch);
                batch.clear();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (RuntimeException e) {
                batch.forEach(queue::offer);
                batch.clear();
                try {
                    TimeUnit.SECONDS.sleep(1);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    @PreDestroy
    void close() {
        running = false;
        worker.shutdown();
        try {
            worker.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
