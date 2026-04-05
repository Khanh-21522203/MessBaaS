package com.java_mess.java_mess.service;

import java.time.Instant;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ProjectionReconcileRuntimeStats {
    private boolean enabled;
    private int batchSize;
    private int intervalSeconds;
    private long checkpoint;
    private long cycles;
    private long processed;
    private long failed;
    private Instant lastRunAt;
}
