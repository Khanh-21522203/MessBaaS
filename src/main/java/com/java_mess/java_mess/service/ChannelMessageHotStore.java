package com.java_mess.java_mess.service;

import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

import com.java_mess.java_mess.model.Channel;
import com.java_mess.java_mess.model.Message;
import com.java_mess.java_mess.model.User;

public class ChannelMessageHotStore {
    private final int perChannelLimit;
    private final ConcurrentMap<String, ChannelBuffer> channels = new ConcurrentHashMap<>();
    private final AtomicLong latestHit = new AtomicLong();
    private final AtomicLong beforeHit = new AtomicLong();
    private final AtomicLong afterHit = new AtomicLong();
    private final AtomicLong miss = new AtomicLong();
    private final AtomicLong eviction = new AtomicLong();

    public ChannelMessageHotStore(int perChannelLimit) {
        if (perChannelLimit <= 0) {
            throw new IllegalArgumentException("perChannelLimit must be positive");
        }
        this.perChannelLimit = perChannelLimit;
    }

    public void append(Message message) {
        if (message == null || message.getChannel() == null || message.getChannel().getId() == null || message.getId() == null) {
            throw new IllegalArgumentException("message with persisted id and channel is required");
        }
        channels.computeIfAbsent(message.getChannel().getId(), ignored -> new ChannelBuffer(perChannelLimit))
            .append(copy(message));
    }

    public List<Message> latest(String channelId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        ChannelBuffer buffer = channels.get(channelId);
        if (buffer == null) {
            miss.incrementAndGet();
            return List.of();
        }
        List<Message> result = buffer.latest(limit);
        if (result.isEmpty()) {
            miss.incrementAndGet();
        } else {
            latestHit.incrementAndGet();
        }
        return result;
    }

    public List<Message> before(String channelId, long pivotId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        ChannelBuffer buffer = channels.get(channelId);
        if (buffer == null) {
            miss.incrementAndGet();
            return List.of();
        }
        List<Message> result = buffer.before(pivotId, limit);
        if (result.isEmpty()) {
            miss.incrementAndGet();
        } else {
            beforeHit.incrementAndGet();
        }
        return result;
    }

    public List<Message> after(String channelId, long pivotId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        ChannelBuffer buffer = channels.get(channelId);
        if (buffer == null) {
            miss.incrementAndGet();
            return List.of();
        }
        List<Message> result = buffer.after(pivotId, limit);
        if (result.isEmpty()) {
            miss.incrementAndGet();
        } else {
            afterHit.incrementAndGet();
        }
        return result;
    }

    public ChannelHotStoreStats snapshotStats() {
        return ChannelHotStoreStats.builder()
            .latestHit(latestHit.get())
            .beforeHit(beforeHit.get())
            .afterHit(afterHit.get())
            .miss(miss.get())
            .eviction(eviction.get())
            .channelCount(channels.size())
            .build();
    }

    private Message copy(Message message) {
        Channel channel = message.getChannel() == null ? null : Channel.builder()
            .id(message.getChannel().getId())
            .name(message.getChannel().getName())
            .clientReferenceId(message.getChannel().getClientReferenceId())
            .createdAt(message.getChannel().getCreatedAt())
            .build();

        User user = message.getUser() == null ? null : User.builder()
            .id(message.getUser().getId())
            .clientUserId(message.getUser().getClientUserId())
            .name(message.getUser().getName())
            .profileImgUrl(message.getUser().getProfileImgUrl())
            .createdAt(message.getUser().getCreatedAt())
            .build();

        return Message.builder()
            .id(message.getId())
            .channel(channel)
            .user(user)
            .clientMessageId(message.getClientMessageId())
            .message(message.getMessage())
            .imgUrl(message.getImgUrl())
            .isDeleted(message.getIsDeleted())
            .createdAt(message.getCreatedAt())
            .updatedAt(message.getUpdatedAt())
            .build();
    }

    private final class ChannelBuffer {
        private final int limit;
        private final NavigableMap<Long, Message> messages = new TreeMap<>();

        private ChannelBuffer(int limit) {
            this.limit = limit;
        }

        private synchronized void append(Message message) {
            messages.put(message.getId(), message);
            while (messages.size() > limit) {
                messages.pollFirstEntry();
                eviction.incrementAndGet();
            }
        }

        private synchronized List<Message> latest(int requestedLimit) {
            return collect(messages.descendingMap(), requestedLimit);
        }

        private synchronized List<Message> before(long pivotId, int requestedLimit) {
            return collect(messages.headMap(pivotId, false).descendingMap(), requestedLimit);
        }

        private synchronized List<Message> after(long pivotId, int requestedLimit) {
            return collect(messages.tailMap(pivotId, false), requestedLimit);
        }

        private List<Message> collect(NavigableMap<Long, Message> source, int requestedLimit) {
            List<Message> result = new ArrayList<>(Math.min(requestedLimit, source.size()));
            for (Message message : source.values()) {
                if (result.size() >= requestedLimit) {
                    break;
                }
                result.add(copy(message));
            }
            return result;
        }
    }
}
