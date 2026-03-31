package com.java_mess.java_mess.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java_mess.java_mess.model.InboxEntry;
import com.java_mess.java_mess.model.Message;

import redis.clients.jedis.JedisPooled;

public class ProjectionCacheStore {
    private final ObjectMapper objectMapper;
    private final JedisPooled redis;
    private final int hotWindowScanLimit;
    private final ConcurrentMap<String, Set<String>> localMembership = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ConcurrentMap<String, Long>> localUnread = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ConcurrentMap<String, Long>> localReadCursor = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ConcurrentMap<String, InboxEntry>> localInbox = new ConcurrentHashMap<>();
    private final AtomicLong redisErrors = new AtomicLong();
    private final AtomicLong localFallbackReads = new AtomicLong();

    public ProjectionCacheStore(ObjectMapper objectMapper, JedisPooled redis, int hotWindowScanLimit) {
        this.objectMapper = objectMapper;
        this.redis = redis;
        this.hotWindowScanLimit = Math.max(64, hotWindowScanLimit);
    }

    public boolean isRedisEnabled() {
        return redis != null;
    }

    public boolean isRedisAvailable() {
        if (redis == null) {
            return false;
        }
        try {
            return "PONG".equalsIgnoreCase(redis.ping());
        } catch (RuntimeException exception) {
            redisErrors.incrementAndGet();
            return false;
        }
    }

    public void appendHotMessage(Message message, int perChannelLimit) {
        if (redis == null || message == null || message.getChannel() == null || message.getChannel().getId() == null) {
            return;
        }
        try {
            String key = "channel:" + message.getChannel().getId() + ":messages:hot";
            redis.lpush(key, serialize(message));
            redis.ltrim(key, 0, Math.max(1, perChannelLimit) - 1L);
        } catch (RuntimeException exception) {
            redisErrors.incrementAndGet();
        }
    }

    public List<Message> latestHotMessages(String channelId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<Message> window = loadHotWindow(channelId);
        if (window.isEmpty()) {
            localFallbackReads.incrementAndGet();
            return List.of();
        }
        if (window.size() <= limit) {
            return window;
        }
        return new ArrayList<>(window.subList(0, limit));
    }

    public List<Message> beforeHotMessages(String channelId, long pivotId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<Message> window = loadHotWindow(channelId);
        if (window.isEmpty()) {
            localFallbackReads.incrementAndGet();
            return List.of();
        }
        List<Message> filtered = new ArrayList<>();
        for (Message message : window) {
            if (message.getId() != null && message.getId() < pivotId) {
                filtered.add(message);
                if (filtered.size() >= limit) {
                    break;
                }
            }
        }
        return filtered;
    }

    public List<Message> afterHotMessages(String channelId, long pivotId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<Message> window = loadHotWindow(channelId);
        if (window.isEmpty()) {
            localFallbackReads.incrementAndGet();
            return List.of();
        }
        List<Message> filtered = new ArrayList<>();
        for (Message message : window) {
            if (message.getId() != null && message.getId() > pivotId) {
                filtered.add(message);
            }
        }
        if (filtered.isEmpty()) {
            return List.of();
        }
        Collections.reverse(filtered);
        if (filtered.size() <= limit) {
            return filtered;
        }
        return new ArrayList<>(filtered.subList(0, limit));
    }

    public void cacheMembership(String channelId, String userId) {
        localMembership.computeIfAbsent(channelId, ignored -> ConcurrentHashMap.newKeySet()).add(userId);
        if (redis == null) {
            return;
        }
        try {
            redis.sadd(membershipKey(channelId), userId);
        } catch (RuntimeException exception) {
            redisErrors.incrementAndGet();
        }
    }

    public void evictMembership(String channelId, String userId) {
        Set<String> members = localMembership.get(channelId);
        if (members != null) {
            members.remove(userId);
        }
        if (redis == null) {
            return;
        }
        try {
            redis.srem(membershipKey(channelId), userId);
        } catch (RuntimeException exception) {
            redisErrors.incrementAndGet();
        }
    }

    public Boolean isMemberCached(String channelId, String userId) {
        if (redis != null) {
            try {
                return redis.sismember(membershipKey(channelId), userId);
            } catch (RuntimeException exception) {
                redisErrors.incrementAndGet();
            }
        }
        Set<String> members = localMembership.get(channelId);
        if (members == null) {
            return null;
        }
        return members.contains(userId);
    }

    public void setReadCursor(String userId, String channelId, long readCursor) {
        localReadCursor
            .computeIfAbsent(userId, ignored -> new ConcurrentHashMap<>())
            .put(channelId, readCursor);
        if (redis == null) {
            return;
        }
        try {
            redis.hset(readCursorKey(userId), channelId, String.valueOf(readCursor));
        } catch (RuntimeException exception) {
            redisErrors.incrementAndGet();
        }
    }

    public Optional<Long> getReadCursor(String userId, String channelId) {
        if (redis != null) {
            try {
                String value = redis.hget(readCursorKey(userId), channelId);
                if (value != null) {
                    return Optional.of(Long.parseLong(value));
                }
            } catch (RuntimeException exception) {
                redisErrors.incrementAndGet();
            }
        }
        Map<String, Long> userRead = localReadCursor.get(userId);
        if (userRead == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(userRead.get(channelId));
    }

    public void setUnreadCount(String userId, String channelId, long unreadCount) {
        localUnread
            .computeIfAbsent(userId, ignored -> new ConcurrentHashMap<>())
            .put(channelId, unreadCount);
        if (redis == null) {
            return;
        }
        try {
            redis.hset(unreadKey(userId), channelId, String.valueOf(unreadCount));
        } catch (RuntimeException exception) {
            redisErrors.incrementAndGet();
        }
    }

    public Optional<Long> getUnreadCount(String userId, String channelId) {
        if (redis != null) {
            try {
                String value = redis.hget(unreadKey(userId), channelId);
                if (value != null) {
                    return Optional.of(Long.parseLong(value));
                }
            } catch (RuntimeException exception) {
                redisErrors.incrementAndGet();
            }
        }
        Map<String, Long> unread = localUnread.get(userId);
        if (unread == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(unread.get(channelId));
    }

    public void upsertInboxEntry(String userId, InboxEntry entry) {
        localInbox
            .computeIfAbsent(userId, ignored -> new ConcurrentHashMap<>())
            .put(entry.getChannelId(), entry);
        if (redis == null) {
            return;
        }
        try {
            redis.zadd(inboxOrderKey(userId), entry.getLastMessageId(), entry.getChannelId());
            redis.hset(inboxValueKey(userId), entry.getChannelId(), serialize(entry));
        } catch (RuntimeException exception) {
            redisErrors.incrementAndGet();
        }
    }

    public List<InboxEntry> listInboxEntries(String userId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        if (redis != null) {
            try {
                List<String> channelIds = redis.zrevrange(inboxOrderKey(userId), 0, limit - 1L)
                    .stream()
                    .toList();
                if (!channelIds.isEmpty()) {
                    List<InboxEntry> entries = new ArrayList<>();
                    for (String channelId : channelIds) {
                        String payload = redis.hget(inboxValueKey(userId), channelId);
                        if (payload == null) {
                            continue;
                        }
                        InboxEntry entry = deserialize(payload, InboxEntry.class);
                        if (entry != null) {
                            entries.add(entry);
                        }
                    }
                    if (!entries.isEmpty()) {
                        return entries;
                    }
                }
            } catch (RuntimeException exception) {
                redisErrors.incrementAndGet();
            }
        }

        localFallbackReads.incrementAndGet();
        Map<String, InboxEntry> entries = localInbox.get(userId);
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        return entries.values().stream()
            .sorted(Comparator.comparingLong(InboxEntry::getLastMessageId).reversed())
            .limit(limit)
            .toList();
    }

    public ProjectionCacheRuntimeStats runtimeStats() {
        return ProjectionCacheRuntimeStats.builder()
            .redisEnabled(redis != null)
            .redisAvailable(isRedisAvailable())
            .redisErrors(redisErrors.get())
            .localFallbackReads(localFallbackReads.get())
            .build();
    }

    private List<Message> loadHotWindow(String channelId) {
        if (redis == null) {
            return List.of();
        }
        try {
            String key = "channel:" + channelId + ":messages:hot";
            List<String> payloads = redis.lrange(key, 0, hotWindowScanLimit - 1L);
            if (payloads.isEmpty()) {
                return List.of();
            }
            List<Message> messages = new ArrayList<>(payloads.size());
            for (String payload : payloads) {
                Message message = deserialize(payload, Message.class);
                if (message != null) {
                    messages.add(message);
                }
            }
            return messages;
        } catch (RuntimeException exception) {
            redisErrors.incrementAndGet();
            return List.of();
        }
    }

    private String membershipKey(String channelId) {
        return "channel:" + channelId + ":members";
    }

    private String readCursorKey(String userId) {
        return "user:" + userId + ":reads";
    }

    private String unreadKey(String userId) {
        return "user:" + userId + ":unread";
    }

    private String inboxOrderKey(String userId) {
        return "user:" + userId + ":inbox:order";
    }

    private String inboxValueKey(String userId) {
        return "user:" + userId + ":inbox:value";
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to serialize projection cache value", exception);
        }
    }

    private <T> T deserialize(String payload, Class<T> type) {
        try {
            return objectMapper.readValue(payload, type);
        } catch (IOException exception) {
            return null;
        }
    }
}
