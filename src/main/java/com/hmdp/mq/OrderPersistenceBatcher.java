package com.hmdp.mq;

import com.hmdp.dto.SeckillOrderMessage;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.OrderBatchWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/** MQ callbacks wait for the commit; this queue never acknowledges or replaces durable MQ storage. */
@Slf4j
@Component
public class OrderPersistenceBatcher {
    private static final int BATCH_SIZE = 32;
    private final BlockingQueue<Pending> queue = new ArrayBlockingQueue<>(512);
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "seckill-batch-writer");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean stopping;
    @Resource private OrderBatchWriter writer;
    @Resource private IVoucherOrderService orders;

    @PostConstruct
    public void start() { executor.scheduleWithFixedDelay(this::flush, 0, 20, TimeUnit.MILLISECONDS); }

    public void createOrder(SeckillOrderMessage message) {
        Pending pending = new Pending(message);
        try {
            if (stopping || !queue.offer(pending, 1, TimeUnit.SECONDS))
                throw new IllegalStateException("Order persistence queue unavailable");
            // Handle shutdown racing with offer; the broker will redeliver an unacknowledged message.
            if (stopping && queue.remove(pending)) pending.committed.completeExceptionally(new IllegalStateException("Stopping"));
            pending.committed.get(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted before persistence confirmation", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Order persistence not confirmed; retry through MQ", e);
        }
    }

    private void flush() {
        List<Pending> batch = new ArrayList<>(BATCH_SIZE);
        queue.drainTo(batch, BATCH_SIZE);
        if (batch.isEmpty()) return;
        if (batch.size() > 1) {
            try {
                writer.write(batch.stream().map(p -> p.message).collect(Collectors.toList()));
                // The Spring transaction proxy has committed before write() returns.
                batch.forEach(p -> p.committed.complete(null));
                return;
            } catch (Exception e) {
                // The failed batch was rolled back. Isolate duplicate/invalid items using the
                // existing per-order transaction so a poison message cannot block valid orders.
                log.warn("批量落库回滚，逐条隔离重试，batchSize={}", batch.size(), e);
            }
        }
        for (Pending pending : batch) {
            try {
                orders.createOrder(pending.message);
                pending.committed.complete(null);
            } catch (Exception e) {
                pending.committed.completeExceptionally(e);
            }
        }
    }

    @PreDestroy
    public void stop() {
        stopping = true;
        executor.shutdown();
        Pending pending;
        while ((pending = queue.poll()) != null)
            pending.committed.completeExceptionally(new IllegalStateException("Stopping before persistence"));
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static class Pending {
        final SeckillOrderMessage message;
        final CompletableFuture<Void> committed = new CompletableFuture<>();
        Pending(SeckillOrderMessage message) { this.message = message; }
    }
}
