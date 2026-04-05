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
    private final ProjectionCacheStore projectionCacheStore;

    @Override
    public long updateReadCursor(String channelId, String clientUserId, long lastReadMessageId) {
        User user = userRepository.findByClientUserId(clientUserId).orElseThrow(UserNotFoundException::new);
        Channel channel = channelRepository.findById(channelId).orElseThrow(ChannelNotFoundException::new);
        long storedCursor = userReadMessageRepository.upsertReadCursor(channel.getId(), user.getId(), lastReadMessageId);
        projectionCacheStore.setReadCursor(user.getId(), channel.getId(), storedCursor);
        long unread = userReadMessageRepository.countUnreadMessages(channel.getId(), storedCursor);
        projectionCacheStore.setUnreadCountWithVersion(user.getId(), channel.getId(), unread, storedCursor);
        return storedCursor;
    }

    @Override
    public ReadStateSnapshot getUnreadCount(String channelId, String clientUserId) {
        User user = userRepository.findByClientUserId(clientUserId).orElseThrow(UserNotFoundException::new);
        Channel channel = channelRepository.findById(channelId).orElseThrow(ChannelNotFoundException::new);
        String userId = user.getId();
        String resolvedChannelId = channel.getId();

        Optional<Long> cursor = userReadMessageRepository.findReadCursor(resolvedChannelId, userId);
        Optional<Long> cachedCursor = projectionCacheStore.getReadCursor(userId, resolvedChannelId);
        if (cursor.isPresent() && cachedCursor.isPresent() && !cursor.get().equals(cachedCursor.get())) {
            projectionCacheStore.markProjectionDriftDetected();
        }
        cursor.ifPresent(value -> projectionCacheStore.setReadCursor(userId, resolvedChannelId, value));

        Optional<Long> cachedUnread = projectionCacheStore.getUnreadCount(userId, resolvedChannelId);
        long unreadCount;
        if (cachedUnread.isPresent()) {
            unreadCount = cachedUnread.get();
        } else {
            unreadCount = cursor
                .map(value -> userReadMessageRepository.countUnreadMessages(resolvedChannelId, value))
                .orElseGet(() -> userReadMessageRepository.countAllMessages(resolvedChannelId));
            projectionCacheStore.markUnreadRepairWrite(1);
        }
        projectionCacheStore.setUnreadCount(userId, resolvedChannelId, unreadCount);

        return ReadStateSnapshot.builder()
            .lastReadMessageId(cursor.orElse(null))
            .unreadCount(unreadCount)
            .build();
    }
}
