package com.java_mess.java_mess.websocket;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class WebSocketRegistryStats {
    private int channelCount;
    private int activeConnectionCount;
    private long broadcastAttempt;
    private long broadcastFailed;
    private boolean distributedFanoutEnabled;
    private long distributedPublishAttempt;
    private long distributedPublishFailed;
    private long distributedReceiveAttempt;
    private long distributedReceiveDroppedLoop;
    private long distributedReceiveFailed;
}
