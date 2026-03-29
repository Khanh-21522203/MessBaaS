package com.java_mess.java_mess.dto.readstate;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class GetUnreadCountResponse {
    private String channelId;
    private String clientUserId;
    private long unreadCount;
    private Long lastReadMessageId;
}
