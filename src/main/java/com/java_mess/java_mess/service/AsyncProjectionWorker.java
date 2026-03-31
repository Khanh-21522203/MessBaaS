package com.java_mess.java_mess.service;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import com.java_mess.java_mess.model.MessageOutboxEvent;
import com.java_mess.java_mess.repository.MessageOutboxRepository;
import com.java_mess.java_mess.repository.MessageOutboxStatus;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class AsyncProjectionWorker implements AutoCloseable {
    private final MessageOutboxRepository messageOutboxRepository;
    private final ProjectionProcessor projectionProcessor;
    private final int pollMillis;
    private final int batchSize;
    private final int maxAttempts;
    private final int baseBackoffMillis;
    private final int leaseMillis;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicLong processed = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final LatencyTracker lagTracker = new LatencyTracker(2_048);
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "projection-worker");
        thread.setDaemon(true);
        return thread;
    });

    public AsyncProjectionWorker(
        MessageOutboxRepository messageOutboxRepository,
        ProjectionProcessor projectionProcessor,
        int pollMillis,
        int batchSize,
        int maxAttempts,
        int baseBackoffMillis,
        int leaseMillis
    ) {
        this.messageOutboxRepository = messageOutboxRepository;
        this.projectionProcessor = projectionProcessor;
        this.pollMillis = Math.max(50, pollMillis);
        this.batchSize = Math.max(1, batchSize);
        this.maxAttempts = Math.max(1, maxAttempts);
        this.baseBackoffMillis = Math.max(100, baseBackoffMillis);
        this.leaseMillis = Math.max(500, leaseMillis);
    }

    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        executor.scheduleWithFixedDelay(this::runCycle, 0, pollMillis, TimeUnit.MILLISECONDS);
        log.info("Started async projection worker pollMillis={} batchSize={}", pollMillis, batchSize);
    }

    public ProjectionRuntimeStats runtimeStats() {
        return ProjectionRuntimeStats.builder()
            .pendingBacklog(messageOutboxRepository.countPendingBacklog())
            .inProgress(messageOutboxRepository.countByStatus(MessageOutboxStatus.IN_PROGRESS))
            .retry(messageOutboxRepository.countByStatus(MessageOutboxStatus.RETRY))
            .deadLetter(messageOutboxRepository.countByStatus(MessageOutboxStatus.DEAD_LETTER))
            .processed(processed.get())
            .failed(failed.get())
            .lagLatency(lagTracker.snapshot())
            .build();
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private void runCycle() {
        try {
            List<MessageOutboxEvent> batch = messageOutboxRepository.claimBatch(batchSize, leaseMillis);
            if (batch.isEmpty()) {
                return;
            }
            for (MessageOutboxEvent event : batch) {
                process(event);
            }
        } catch (Exception exception) {
            log.warn("Projection worker cycle failed", exception);
        }
    }

    private void process(MessageOutboxEvent event) {
        try {
            projectionProcessor.process(event);
            messageOutboxRepository.markDone(event.getId());
            processed.incrementAndGet();
            if (event.getMessageCreatedAt() != null) {
                long lagNanos = Math.max(0L, Instant.now().toEpochMilli() - event.getMessageCreatedAt().toEpochMilli()) * 1_000_000L;
                lagTracker.recordNanos(lagNanos);
            }
        } catch (Exception exception) {
            failed.incrementAndGet();
            if (event.getAttemptCount() >= maxAttempts) {
                messageOutboxRepository.markDeadLetter(event.getId(), exception.getMessage());
                log.warn("Projection dead-letter messageId={} outboxId={}", event.getMessageId(), event.getId(), exception);
                return;
            }
            int delay = retryDelayMillis(event.getAttemptCount());
            messageOutboxRepository.markRetry(event.getId(), delay, exception.getMessage());
            log.warn(
                "Projection retry scheduled messageId={} outboxId={} attempt={} delayMillis={}",
                event.getMessageId(),
                event.getId(),
                event.getAttemptCount(),
                delay,
                exception
            );
        }
    }

    private int retryDelayMillis(int attemptCount) {
        int exponent = Math.max(0, attemptCount - 1);
        long raw = (long) baseBackoffMillis << Math.min(exponent, 10);
        long bounded = Math.min(raw, 60_000L);
        return (int) bounded;
    }
}
