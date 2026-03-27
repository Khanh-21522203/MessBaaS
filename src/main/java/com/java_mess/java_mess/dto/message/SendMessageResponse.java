package com.java_mess.java_mess.dto.message;

import com.java_mess.java_mess.model.Message;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SendMessageResponse {
    private Message message;
}
