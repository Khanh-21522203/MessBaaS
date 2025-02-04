package com.java_mess.java_mess.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.java_mess.java_mess.dto.message.ListMessageRequest;
import com.java_mess.java_mess.dto.message.SendMessageRequest;
import com.java_mess.java_mess.exception.UserNotFoundException;
import com.java_mess.java_mess.model.App;
import com.java_mess.java_mess.model.Message;
import com.java_mess.java_mess.model.User;
import com.java_mess.java_mess.repository.ChannelRepository;
import com.java_mess.java_mess.repository.MessageRepository;
import com.java_mess.java_mess.repository.UserRepository;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class MessageServiceImpl implements MessageService {
  @Autowired
  private MessageRepository messageRepository;
  @Autowired
  private ChannelRepository channelRepository;
  @Autowired
  private UserRepository userRepository;

  @Override
  public Message sendMessage(App app, String channelId, SendMessageRequest request) {
    User user = userRepository.findByAppIdAndClientUserId(app.getId(), request.getClientUserId())
        .orElseThrow(() -> new UserNotFoundException());
    Message message = messageRepository.save(Message.builder()
        .channel(channelRepository.getReferenceById(channelId))
        .user(user)
        .message(request.getMessage())
        .imgUrl(request.getImgUrl())
        .isDeleted(false)
        .createdAt(Instant.now())
        .build());
    return message;
  }

  @Override
  public List<Message> listMessages(App app, ListMessageRequest request) {
    if (request.getPivotId() == 0) {
      return messageRepository.listLatestMessages(request.getChannelId(), request.getPrevLimit());
    }
    List<Message> messages = new ArrayList<>();
    if (request.getPrevLimit() > 0) {
      messages.addAll(messageRepository.listMessagesBeforeId(request.getPivotId(), request.getChannelId(),
          request.getPrevLimit()));
    }
    if (request.getNextLimit() > 0) {
      messages.addAll(messageRepository.listMessagesAfterId(request.getPivotId(), request.getChannelId(),
          request.getNextLimit()));
    }
    return messages;
  }

}
