package com.java_mess.java_mess.websocket;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class WebSocketRegistryStats {
    private int channelCount;
    private int activeConnectionCount;
}
