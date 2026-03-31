package com.java_mess.java_mess.service;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ProjectionRuntimeStats {
    private long pendingBacklog;
    private long inProgress;
    private long retry;
    private long deadLetter;
    private long processed;
    private long failed;
    private LatencySnapshot lagLatency;
}
