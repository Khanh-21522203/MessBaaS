package com.java_mess.java_mess.dto.message;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ListMessageRequest {
    private String channelId;
    private String clientUserId;
    private long pivotId;
    private int prevLimit;
    private int nextLimit;
}
