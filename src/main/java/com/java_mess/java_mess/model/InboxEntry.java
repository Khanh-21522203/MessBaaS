package com.java_mess.java_mess.model;

import java.time.Instant;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class InboxEntry {
    private String channelId;
    private long lastMessageId;
    private String lastSenderClientUserId;
    private String lastPreview;
    private long unreadCount;
    private Instant updatedAt;
}
