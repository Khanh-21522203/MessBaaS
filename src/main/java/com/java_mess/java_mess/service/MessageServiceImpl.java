package com.java_mess.java_mess.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import com.java_mess.java_mess.dto.message.ListMessageRequest;
import com.java_mess.java_mess.dto.message.MessageEvent;
import com.java_mess.java_mess.dto.message.SendMessageRequest;
import com.java_mess.java_mess.exception.ChannelNotFoundException;
import com.java_mess.java_mess.exception.UserNotFoundException;
import com.java_mess.java_mess.model.Channel;
import com.java_mess.java_mess.model.Message;
import com.java_mess.java_mess.model.User;
import com.java_mess.java_mess.repository.ChannelRepository;
import com.java_mess.java_mess.repository.MessageRepository;
import com.java_mess.java_mess.repository.UserRepository;
import com.java_mess.java_mess.websocket.ChannelWebSocketRegistry;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class MessageServiceImpl implements MessageService {
    private final MessageRepository messageRepository;
    private final ChannelRepository channelRepository;
    private final UserRepository userRepository;
    private final ChannelWebSocketRegistry channelWebSocketRegistry;
    private final ChannelMessageHotStore channelMessageHotStore;
    private final ChannelMembershipService channelMembershipService;
    private final AtomicLong hotOnlyReads = new AtomicLong();
    private final AtomicLong hotPartialWithDbFallbackReads = new AtomicLong();
    private final AtomicLong dbOnlyReads = new AtomicLong();
    private final AtomicLong readSamples = new AtomicLong();

    @Override
    public Message sendMessage(String channelId, SendMessageRequest request) {
        User user = userRepository.findByClientUserId(request.getClientUserId())
            .orElseThrow(UserNotFoundException::new);
        Channel channel = channelRepository.findById(channelId)
            .orElseThrow(ChannelNotFoundException::new);
        channelMembershipService.assertMember(channel.getId(), request.getClientUserId());

        Instant timeInstant = Instant.now();
        Message message = messageRepository.save(Message.builder()
            .channel(channel)
            .user(user)
            .clientMessageId(request.getClientMessageId())
            .message(request.getMessage())
            .imgUrl(request.getImgUrl())
            .isDeleted(false)
            .createdAt(timeInstant)
            .build());

        channelMessageHotStore.append(message);
        
        MessageEvent messageEvent = MessageEvent.builder()
            .eventType("message.created")
            .messageId(message.getId())
            .clientUserId(message.getUser().getClientUserId())
            .clientMessageId(message.getClientMessageId())
            .channelId(channelId)
            .message(message.getMessage())
            .imgUrl(message.getImgUrl())
            .createdAt(message.getCreatedAt())
            .build();

        publishEvent(messageEvent);
        
        return message;
    }

    private void publishEvent(MessageEvent messageEvent) {
        try {
            channelWebSocketRegistry.broadcast(messageEvent);
        } catch (IllegalStateException exception) {
            log.warn("Failed to publish websocket event channelId={}", messageEvent.getChannelId(), exception);
        }
    }

    @Override
    public List<Message> listMessages(ListMessageRequest request) {
        channelRepository.findById(request.getChannelId()).orElseThrow(ChannelNotFoundException::new);
        channelMembershipService.assertMember(request.getChannelId(), request.getClientUserId());

        if (request.getPivotId() == 0) {
            if (request.getPrevLimit() <= 0) {
                return Collections.emptyList();
            }
            return latestMessages(request.getChannelId(), request.getPrevLimit());
        }
        List<Message> messages = new ArrayList<>();
        if (request.getPrevLimit() > 0) {
            messages.addAll(messagesBefore(request.getChannelId(), request.getPivotId(), request.getPrevLimit()));
        }
        if (request.getNextLimit() > 0) {
            messages.addAll(messagesAfter(request.getChannelId(), request.getPivotId(), request.getNextLimit()));
        }
        return messages;
    }

    private List<Message> latestMessages(String channelId, int limit) {
        List<Message> hotMessages = channelMessageHotStore.latest(channelId, limit);
        if (hotMessages.isEmpty()) {
            dbOnlyReads.incrementAndGet();
            maybeLogReadStats();
            return messageRepository.findLatestMessages(channelId, limit);
        }
        if (hotMessages.size() >= limit) {
            hotOnlyReads.incrementAndGet();
            maybeLogReadStats();
            return hotMessages;
        }

        hotPartialWithDbFallbackReads.incrementAndGet();
        maybeLogReadStats();
        List<Message> combined = new ArrayList<>(hotMessages);
        long oldestHotMessageId = hotMessages.get(hotMessages.size() - 1).getId();
        combined.addAll(messageRepository.listMessagesBeforeId(oldestHotMessageId, channelId, limit - hotMessages.size()));
        return combined;
    }

    private List<Message> messagesBefore(String channelId, long pivotId, int limit) {
        List<Message> hotMessages = channelMessageHotStore.before(channelId, pivotId, limit);
        if (hotMessages.isEmpty()) {
            dbOnlyReads.incrementAndGet();
            maybeLogReadStats();
            return messageRepository.listMessagesBeforeId(pivotId, channelId, limit);
        }
        if (hotMessages.size() >= limit) {
            hotOnlyReads.incrementAndGet();
            maybeLogReadStats();
            return hotMessages;
        }

        hotPartialWithDbFallbackReads.incrementAndGet();
        maybeLogReadStats();
        List<Message> combined = new ArrayList<>(hotMessages);
        long dbPivot = hotMessages.get(hotMessages.size() - 1).getId();
        combined.addAll(messageRepository.listMessagesBeforeId(dbPivot, channelId, limit - hotMessages.size()));
        return combined;
    }

    private List<Message> messagesAfter(String channelId, long pivotId, int limit) {
        List<Message> hotMessages = channelMessageHotStore.after(channelId, pivotId, limit);
        if (hotMessages.isEmpty()) {
            dbOnlyReads.incrementAndGet();
            maybeLogReadStats();
            return messageRepository.listMessagesAfterId(pivotId, channelId, limit);
        }
        if (hotMessages.size() >= limit) {
            hotOnlyReads.incrementAndGet();
            maybeLogReadStats();
            return hotMessages;
        }

        hotPartialWithDbFallbackReads.incrementAndGet();
        maybeLogReadStats();
        List<Message> combined = new ArrayList<>(hotMessages);
        long dbPivot = hotMessages.get(hotMessages.size() - 1).getId();
        combined.addAll(messageRepository.listMessagesAfterId(dbPivot, channelId, limit - hotMessages.size()));
        return combined;
    }

    @Override
    public MessageRuntimeStats runtimeStats() {
        return MessageRuntimeStats.builder()
            .hotOnly(hotOnlyReads.get())
            .hotPartialWithDbFallback(hotPartialWithDbFallbackReads.get())
            .dbOnly(dbOnlyReads.get())
            .hotStore(channelMessageHotStore.snapshotStats())
            .build();
    }

    private void maybeLogReadStats() {
        long sample = readSamples.incrementAndGet();
        if (sample % 1_000 == 0) {
            MessageRuntimeStats stats = runtimeStats();
            log.info(
                "Message read stats sample={} hotOnly={} hotPartialWithDbFallback={} dbOnly={} hotStore={}",
                sample,
                stats.getHotOnly(),
                stats.getHotPartialWithDbFallback(),
                stats.getDbOnly(),
                stats.getHotStore()
            );
        }
    }
}
