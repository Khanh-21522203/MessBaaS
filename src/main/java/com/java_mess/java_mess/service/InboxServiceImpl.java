package com.java_mess.java_mess.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import com.java_mess.java_mess.exception.UserNotFoundException;
import com.java_mess.java_mess.model.InboxEntry;
import com.java_mess.java_mess.model.Message;
import com.java_mess.java_mess.model.User;
import com.java_mess.java_mess.repository.ChannelMemberRepository;
import com.java_mess.java_mess.repository.MessageRepository;
import com.java_mess.java_mess.repository.UserReadMessageRepository;
import com.java_mess.java_mess.repository.UserRepository;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class InboxServiceImpl implements InboxService {
    private final UserRepository userRepository;
    private final ChannelMemberRepository channelMemberRepository;
    private final MessageRepository messageRepository;
    private final UserReadMessageRepository userReadMessageRepository;
    private final ProjectionCacheStore projectionCacheStore;
    private final AtomicLong cacheHit = new AtomicLong();
    private final AtomicLong dbFallback = new AtomicLong();
    private final LatencyTracker latencyTracker = new LatencyTracker(2_048);

    @Override
    public List<InboxEntry> listInbox(String clientUserId, int limit) {
        long startedAt = System.nanoTime();
        try {
            if (limit <= 0) {
                return List.of();
            }
            User user = userRepository.findByClientUserId(clientUserId).orElseThrow(UserNotFoundException::new);
            List<InboxEntry> cached = projectionCacheStore.listInboxEntries(user.getId(), limit);
            if (!cached.isEmpty()) {
                cacheHit.incrementAndGet();
                return cached;
            }

            dbFallback.incrementAndGet();
            List<String> channelIds = channelMemberRepository.listChannelIdsByUser(user.getId());
            if (channelIds.isEmpty()) {
                return List.of();
            }

            List<InboxEntry> entries = new ArrayList<>();
            for (String channelId : channelIds) {
                List<Message> latestMessages = messageRepository.findLatestMessages(channelId, 1);
                if (latestMessages.isEmpty()) {
                    continue;
                }
                Message latestMessage = latestMessages.get(0);
                Optional<Long> readCursor = userReadMessageRepository.findReadCursor(channelId, user.getId());
                readCursor.ifPresent(value -> projectionCacheStore.setReadCursor(user.getId(), channelId, value));
                long unreadCount = readCursor
                    .map(value -> userReadMessageRepository.countUnreadMessages(channelId, value))
                    .orElseGet(() -> userReadMessageRepository.countAllMessages(channelId));
                projectionCacheStore.setUnreadCount(user.getId(), channelId, unreadCount);

                String preview = preview(latestMessage);
                InboxEntry entry = InboxEntry.builder()
                    .channelId(channelId)
                    .lastMessageId(latestMessage.getId())
                    .lastSenderClientUserId(latestMessage.getUser() == null ? null : latestMessage.getUser().getClientUserId())
                    .lastPreview(preview)
                    .unreadCount(unreadCount)
                    .updatedAt(latestMessage.getCreatedAt() == null ? Instant.now() : latestMessage.getCreatedAt())
                    .build();
                projectionCacheStore.upsertInboxEntry(user.getId(), entry);
                entries.add(entry);
            }

            return entries.stream()
                .sorted(Comparator.comparingLong(InboxEntry::getLastMessageId).reversed())
                .limit(limit)
                .toList();
        } finally {
            latencyTracker.recordNanos(System.nanoTime() - startedAt);
        }
    }

    @Override
    public InboxRuntimeStats runtimeStats() {
        long hits = cacheHit.get();
        long fallbacks = dbFallback.get();
        long total = hits + fallbacks;
        return InboxRuntimeStats.builder()
            .cacheHit(hits)
            .dbFallback(fallbacks)
            .cacheHitRatio(total == 0 ? 0.0d : (double) hits / total)
            .latency(latencyTracker.snapshot())
            .build();
    }

    private String preview(Message message) {
        if (message.getMessage() != null && !message.getMessage().isBlank()) {
            String normalized = message.getMessage().trim();
            return normalized.length() <= 140 ? normalized : normalized.substring(0, 140);
        }
        if (message.getImgUrl() != null && !message.getImgUrl().isBlank()) {
            return "[image]";
        }
        return "";
    }
}
