package com.java_mess.java_mess.service;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ProjectionCacheRuntimeStats {
    private boolean redisEnabled;
    private boolean redisAvailable;
    private long redisErrors;
    private long localFallbackReads;
    private long projectionDriftDetected;
    private long hotRepairWrites;
    private long inboxRepairWrites;
    private long readRepairWrites;
    private long unreadRepairWrites;
}
