package com.java_mess.java_mess.service;

import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import com.java_mess.java_mess.model.Message;
import com.java_mess.java_mess.model.MessageOutboxEvent;
import com.java_mess.java_mess.repository.MessageRepository;
import com.java_mess.java_mess.repository.ProjectionReconcileStateRepository;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ProjectionReconcileWorker implements AutoCloseable {
    private static final String MESSAGE_SCOPE = "messageProjection";

    private final ProjectionReconcileStateRepository stateRepository;
    private final MessageRepository messageRepository;
    private final ProjectionProcessor projectionProcessor;
    private final int batchSize;
    private final int intervalSeconds;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicLong checkpoint = new AtomicLong();
    private final AtomicLong cycles = new AtomicLong();
    private final AtomicLong processed = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private volatile Instant lastRunAt;

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "projection-reconcile-worker");
        thread.setDaemon(true);
        return thread;
    });

    public ProjectionReconcileWorker(
        ProjectionReconcileStateRepository stateRepository,
        MessageRepository messageRepository,
        ProjectionProcessor projectionProcessor,
        int batchSize,
        int intervalSeconds
    ) {
        this.stateRepository = stateRepository;
        this.messageRepository = messageRepository;
        this.projectionProcessor = projectionProcessor;
        this.batchSize = Math.max(1, batchSize);
        this.intervalSeconds = Math.max(10, intervalSeconds);
    }

    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        OptionalLong stored = stateRepository.findCheckpoint(MESSAGE_SCOPE);
        checkpoint.set(stored.orElse(0L));
        executor.scheduleWithFixedDelay(this::runCycle, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        log.info(
            "Started projection reconcile worker checkpoint={} batchSize={} intervalSeconds={}",
            checkpoint.get(),
            batchSize,
            intervalSeconds
        );
    }

    public ProjectionReconcileRuntimeStats runtimeStats() {
        return ProjectionReconcileRuntimeStats.builder()
            .enabled(started.get())
            .batchSize(batchSize)
            .intervalSeconds(intervalSeconds)
            .checkpoint(checkpoint.get())
            .cycles(cycles.get())
            .processed(processed.get())
            .failed(failed.get())
            .lastRunAt(lastRunAt)
            .build();
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private void runCycle() {
        try {
            lastRunAt = Instant.now();
            cycles.incrementAndGet();
            long currentCheckpoint = checkpoint.get();
            List<Message> messages = messageRepository.listMessagesForReconcileAfterId(currentCheckpoint, batchSize);
            if (messages.isEmpty()) {
                return;
            }

            long latestSeen = currentCheckpoint;
            for (Message message : messages) {
                projectionProcessor.processReplay(toOutboxEvent(message));
                latestSeen = Math.max(latestSeen, message.getId() == null ? latestSeen : message.getId());
            }

            stateRepository.upsertCheckpoint(MESSAGE_SCOPE, latestSeen);
            checkpoint.set(latestSeen);
            processed.addAndGet(messages.size());
        } catch (Exception exception) {
            failed.incrementAndGet();
            log.warn("Projection reconcile worker cycle failed", exception);
        }
    }

    private MessageOutboxEvent toOutboxEvent(Message message) {
        return MessageOutboxEvent.builder()
            .messageId(message.getId())
            .channelId(message.getChannel() == null ? null : message.getChannel().getId())
            .senderUserId(message.getUser() == null ? null : message.getUser().getId())
            .senderClientUserId(message.getUser() == null ? null : message.getUser().getClientUserId())
            .clientMessageId(message.getClientMessageId())
            .messageBody(message.getMessage())
            .imgUrl(message.getImgUrl())
            .messageCreatedAt(message.getCreatedAt())
            .build();
    }
}
