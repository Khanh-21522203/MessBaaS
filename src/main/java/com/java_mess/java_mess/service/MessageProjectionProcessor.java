package com.java_mess.java_mess.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.java_mess.java_mess.dto.message.MessageEvent;
import com.java_mess.java_mess.model.Channel;
import com.java_mess.java_mess.model.InboxEntry;
import com.java_mess.java_mess.model.Message;
import com.java_mess.java_mess.model.MessageOutboxEvent;
import com.java_mess.java_mess.model.User;
import com.java_mess.java_mess.repository.ChannelMemberRepository;
import com.java_mess.java_mess.repository.UserReadMessageRepository;
import com.java_mess.java_mess.websocket.ChannelWebSocketRegistry;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RequiredArgsConstructor
@Slf4j
public class MessageProjectionProcessor implements ProjectionProcessor {
    private final ChannelMessageHotStore channelMessageHotStore;
    private final ProjectionCacheStore projectionCacheStore;
    private final ChannelWebSocketRegistry channelWebSocketRegistry;
    private final ChannelMemberRepository channelMemberRepository;
    private final UserReadMessageRepository userReadMessageRepository;
    private final int hotBufferPerChannel;

    @Override
    public void process(MessageOutboxEvent event) {
        processInternal(event, true);
    }

    @Override
    public void processReplay(MessageOutboxEvent event) {
        processInternal(event, false);
    }

    private void processInternal(MessageOutboxEvent event, boolean emitRealtime) {
        Message message = mapMessage(event);
        channelMessageHotStore.append(message);
        projectionCacheStore.appendHotMessage(message, hotBufferPerChannel);

        if (emitRealtime) {
            MessageEvent websocketEvent = MessageEvent.builder()
                .eventType("message.created")
                .messageId(event.getMessageId())
                .clientUserId(event.getSenderClientUserId())
                .clientMessageId(event.getClientMessageId())
                .channelId(event.getChannelId())
                .message(event.getMessageBody())
                .imgUrl(event.getImgUrl())
                .createdAt(event.getMessageCreatedAt())
                .build();
            channelWebSocketRegistry.broadcast(websocketEvent);
        }

        List<User> members = channelMemberRepository.listMembers(event.getChannelId());
        for (User member : members) {
            projectionCacheStore.cacheMembership(event.getChannelId(), member.getId());
            Optional<Long> cursor = userReadMessageRepository.findReadCursor(event.getChannelId(), member.getId());
            cursor.ifPresent(value -> projectionCacheStore.setReadCursor(member.getId(), event.getChannelId(), value));
            long unread = cursor
                .map(value -> userReadMessageRepository.countUnreadMessages(event.getChannelId(), value))
                .orElseGet(() -> userReadMessageRepository.countAllMessages(event.getChannelId()));
            projectionCacheStore.setUnreadCountFromProjection(member.getId(), event.getChannelId(), unread, event.getMessageId());

            InboxEntry entry = InboxEntry.builder()
                .channelId(event.getChannelId())
                .lastMessageId(event.getMessageId())
                .lastSenderClientUserId(event.getSenderClientUserId())
                .lastPreview(preview(event))
                .unreadCount(unread)
                .updatedAt(event.getMessageCreatedAt() == null ? Instant.now() : event.getMessageCreatedAt())
                .build();
            projectionCacheStore.upsertInboxEntry(member.getId(), entry);
        }
    }

    private Message mapMessage(MessageOutboxEvent event) {
        return Message.builder()
            .id(event.getMessageId())
            .channel(Channel.builder().id(event.getChannelId()).build())
            .user(User.builder().id(event.getSenderUserId()).clientUserId(event.getSenderClientUserId()).build())
            .clientMessageId(event.getClientMessageId())
            .message(event.getMessageBody())
            .imgUrl(event.getImgUrl())
            .isDeleted(false)
            .createdAt(event.getMessageCreatedAt())
            .updatedAt(event.getMessageCreatedAt())
            .build();
    }

    private String preview(MessageOutboxEvent event) {
        if (event.getMessageBody() != null && !event.getMessageBody().isBlank()) {
            String value = event.getMessageBody().trim();
            return value.length() <= 140 ? value : value.substring(0, 140);
        }
        if (event.getImgUrl() != null && !event.getImgUrl().isBlank()) {
            return "[image]";
        }
        log.warn("Projection event has neither message nor image messageId={}", event.getMessageId());
        return "";
    }
}
