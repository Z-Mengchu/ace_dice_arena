package com.acedicearena.service;

import com.acedicearena.domain.RequestAudit;
import com.acedicearena.repository.RequestAuditRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Service
public class RequestAuditWriter {
    private final RequestAuditRepository repository;
    private final BlockingQueue<RequestAudit> queue;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "request-audit-writer");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean running = true;

    public RequestAuditWriter(RequestAuditRepository repository,
                              @Value("${app.audit.queue-capacity:50000}") int queueCapacity) {
        this.repository = repository;
        this.queue = new ArrayBlockingQueue<>(Math.max(1000, queueCapacity));
    }

    @PostConstruct
    void start() { worker.execute(this::writeLoop); }

    /** 审计入队不占用请求线程；队列满时短暂等待，尽量保证现场请求不丢记录。 */
    public void submit(RequestAudit audit) {
        try { queue.offer(audit, 50, TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
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
                try { TimeUnit.SECONDS.sleep(1); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
            }
        }
    }

    @PreDestroy
    void close() {
        running = false;
        worker.shutdown();
        try { worker.awaitTermination(3, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
