package com.java_mess.java_mess.service;

import java.util.List;

import com.java_mess.java_mess.dto.message.ListMessageRequest;
import com.java_mess.java_mess.dto.message.SendMessageRequest;
import com.java_mess.java_mess.model.Message;

public interface MessageService {
    Message sendMessage(String channelId, SendMessageRequest request);

    List<Message> listMessages(ListMessageRequest request);

    MessageRuntimeStats runtimeStats();
}
