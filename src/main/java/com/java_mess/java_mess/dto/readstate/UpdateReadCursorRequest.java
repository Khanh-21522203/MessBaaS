package com.java_mess.java_mess.dto.readstate;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateReadCursorRequest {
    private String clientUserId;
    private Long lastReadMessageId;
}
