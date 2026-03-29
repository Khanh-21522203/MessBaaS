package com.java_mess.java_mess.dto.message;

import java.time.Instant;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MessageEvent {
    private String eventType;
    private Long messageId;
    private String clientUserId;
    private String clientMessageId;
    private String channelId;
    private String message;
    private String imgUrl;
    private Instant createdAt;
}
