package com.java_mess.java_mess.dto.readstate;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class UpdateReadCursorResponse {
    private String channelId;
    private String clientUserId;
    private Long lastReadMessageId;
}
