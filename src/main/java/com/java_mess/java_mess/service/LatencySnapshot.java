package com.java_mess.java_mess.service;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LatencySnapshot {
    private long sampleCount;
    private double p95Ms;
    private double p99Ms;
}
