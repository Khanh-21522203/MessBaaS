package com.java_mess.java_mess.model;

import java.time.Instant;

import com.java_mess.java_mess.repository.MessageOutboxStatus;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MessageOutboxEvent {
    private long id;
    private long messageId;
    private String channelId;
    private String senderUserId;
    private String senderClientUserId;
    private String clientMessageId;
    private String messageBody;
    private String imgUrl;
    private Instant messageCreatedAt;
    private MessageOutboxStatus status;
    private int attemptCount;
    private Instant nextAttemptAt;
}
