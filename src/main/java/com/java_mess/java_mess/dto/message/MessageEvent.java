package com.java_mess.java_mess.dto.message;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MessageEvent {
    private String clientUserId;
    private String channelId;
    private String message;
    private String imgUrl;
}
