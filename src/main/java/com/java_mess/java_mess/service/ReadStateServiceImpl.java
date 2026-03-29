package com.java_mess.java_mess.service;

import java.util.Optional;

import com.java_mess.java_mess.exception.ChannelNotFoundException;
import com.java_mess.java_mess.exception.UserNotFoundException;
import com.java_mess.java_mess.model.Channel;
import com.java_mess.java_mess.model.User;
import com.java_mess.java_mess.repository.ChannelRepository;
import com.java_mess.java_mess.repository.UserReadMessageRepository;
import com.java_mess.java_mess.repository.UserRepository;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class ReadStateServiceImpl implements ReadStateService {
    private final UserRepository userRepository;
    private final ChannelRepository channelRepository;
    private final UserReadMessageRepository userReadMessageRepository;

    @Override
    public long updateReadCursor(String channelId, String clientUserId, long lastReadMessageId) {
        User user = userRepository.findByClientUserId(clientUserId).orElseThrow(UserNotFoundException::new);
        Channel channel = channelRepository.findById(channelId).orElseThrow(ChannelNotFoundException::new);
        return userReadMessageRepository.upsertReadCursor(channel.getId(), user.getId(), lastReadMessageId);
    }

    @Override
    public ReadStateSnapshot getUnreadCount(String channelId, String clientUserId) {
        User user = userRepository.findByClientUserId(clientUserId).orElseThrow(UserNotFoundException::new);
        Channel channel = channelRepository.findById(channelId).orElseThrow(ChannelNotFoundException::new);

        Optional<Long> cursor = userReadMessageRepository.findReadCursor(channel.getId(), user.getId());
        long unreadCount = cursor
            .map(value -> userReadMessageRepository.countUnreadMessages(channel.getId(), value))
            .orElseGet(() -> userReadMessageRepository.countAllMessages(channel.getId()));

        return ReadStateSnapshot.builder()
            .lastReadMessageId(cursor.orElse(null))
            .unreadCount(unreadCount)
            .build();
    }
}
