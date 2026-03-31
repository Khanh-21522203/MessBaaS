package com.java_mess.java_mess.service;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

public class LatencyTracker {
    private final long[] samplesNanos;
    private final AtomicLong index = new AtomicLong();

    public LatencyTracker(int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("Latency sample size must be positive");
        }
        this.samplesNanos = new long[size];
    }

    public void recordNanos(long durationNanos) {
        if (durationNanos <= 0L) {
            return;
        }
        long i = index.getAndIncrement();
        samplesNanos[(int) (i % samplesNanos.length)] = durationNanos;
    }

    public LatencySnapshot snapshot() {
        long count = Math.min(index.get(), samplesNanos.length);
        if (count <= 0L) {
            return LatencySnapshot.builder()
                .sampleCount(0L)
                .p95Ms(0D)
                .p99Ms(0D)
                .build();
        }
        long[] copy = new long[(int) count];
        for (int i = 0; i < count; i++) {
            copy[i] = samplesNanos[i];
        }
        Arrays.sort(copy);
        return LatencySnapshot.builder()
            .sampleCount(count)
            .p95Ms(toMillis(percentile(copy, 95)))
            .p99Ms(toMillis(percentile(copy, 99)))
            .build();
    }

    private long percentile(long[] sorted, int percentile) {
        if (sorted.length == 0) {
            return 0L;
        }
        int index = (int) Math.ceil((percentile / 100.0) * sorted.length) - 1;
        int bounded = Math.max(0, Math.min(sorted.length - 1, index));
        return sorted[bounded];
    }

    private double toMillis(long nanos) {
        return nanos / 1_000_000.0;
    }
}
