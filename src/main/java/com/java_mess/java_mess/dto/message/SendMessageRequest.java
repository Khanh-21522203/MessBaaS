package com.java_mess.java_mess.dto.message;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SendMessageRequest {
    private String clientUserId;
    private String clientMessageId;
    private String message;
    private String imgUrl;
}
