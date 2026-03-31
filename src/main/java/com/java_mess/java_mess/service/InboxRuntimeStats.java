package com.java_mess.java_mess.service;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class InboxRuntimeStats {
    private long cacheHit;
    private long dbFallback;
    private LatencySnapshot latency;
}
