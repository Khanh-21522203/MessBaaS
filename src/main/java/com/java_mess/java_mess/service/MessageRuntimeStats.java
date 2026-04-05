package com.java_mess.java_mess.service;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MessageRuntimeStats {
    private long hotOnly;
    private long hotPartialWithDbFallback;
    private long dbOnly;
    private double hotHitRatio;
    private double dbFallbackRatio;
    private ChannelHotStoreStats hotStore;
    private LatencySnapshot sendLatency;
    private LatencySnapshot historyLatency;
    private ProjectionRuntimeStats projection;
    private ProjectionCacheRuntimeStats projectionCache;
}
