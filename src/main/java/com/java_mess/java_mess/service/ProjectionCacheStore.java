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
    private final boolean localProjectionCacheEnabled;

    private final ConcurrentMap<String, Set<String>> localMembership = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ConcurrentMap<String, Long>> localUnread = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ConcurrentMap<String, Long>> localUnreadVersion = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ConcurrentMap<String, Long>> localReadCursor = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ConcurrentMap<String, InboxEntry>> localInbox = new ConcurrentHashMap<>();

    private final AtomicLong redisErrors = new AtomicLong();
    private final AtomicLong localFallbackReads = new AtomicLong();
    private final AtomicLong projectionDriftDetected = new AtomicLong();
    private final AtomicLong hotRepairWrites = new AtomicLong();
    private final AtomicLong inboxRepairWrites = new AtomicLong();
    private final AtomicLong readRepairWrites = new AtomicLong();
    private final AtomicLong unreadRepairWrites = new AtomicLong();

    public ProjectionCacheStore(ObjectMapper objectMapper, JedisPooled redis, int hotWindowScanLimit) {
        this(objectMapper, redis, hotWindowScanLimit, true);
    }

    public ProjectionCacheStore(ObjectMapper objectMapper, JedisPooled redis, int hotWindowScanLimit, boolean localProjectionCacheEnabled) {
        this.objectMapper = objectMapper;
        this.redis = redis;
        this.hotWindowScanLimit = Math.max(64, hotWindowScanLimit);
        this.localProjectionCacheEnabled = localProjectionCacheEnabled;
    }

    public boolean isRedisEnabled() {
        return redis != null;
    }

    public boolean isLocalProjectionCacheEnabled() {
        return localProjectionCacheEnabled;
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
        if (localProjectionCacheEnabled) {
            localMembership.computeIfAbsent(channelId, ignored -> ConcurrentHashMap.newKeySet()).add(userId);
        }
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
        if (localProjectionCacheEnabled) {
            Set<String> members = localMembership.get(channelId);
            if (members != null) {
                members.remove(userId);
            }
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
        if (!localProjectionCacheEnabled) {
            return null;
        }
        Set<String> members = localMembership.get(channelId);
        if (members == null) {
            return null;
        }
        return members.contains(userId);
    }

    public void setReadCursor(String userId, String channelId, long readCursor) {
        long normalized = Math.max(0L, readCursor);
        if (localProjectionCacheEnabled) {
            localReadCursor
                .computeIfAbsent(userId, ignored -> new ConcurrentHashMap<>())
                .merge(channelId, normalized, Math::max);
        }
        if (redis != null) {
            try {
                String key = readCursorKey(userId);
                String current = redis.hget(key, channelId);
                long currentValue = parseLong(current).orElse(-1L);
                if (normalized > currentValue) {
                    redis.hset(key, channelId, String.valueOf(normalized));
                }
            } catch (RuntimeException exception) {
                redisErrors.incrementAndGet();
            }
        }
        readRepairWrites.incrementAndGet();
    }

    public Optional<Long> getReadCursor(String userId, String channelId) {
        if (redis != null) {
            try {
                String value = redis.hget(readCursorKey(userId), channelId);
                Optional<Long> parsed = parseLong(value);
                if (parsed.isPresent()) {
                    return parsed;
                }
            } catch (RuntimeException exception) {
                redisErrors.incrementAndGet();
            }
        }
        if (!localProjectionCacheEnabled) {
            return Optional.empty();
        }
        Map<String, Long> userRead = localReadCursor.get(userId);
        if (userRead == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(userRead.get(channelId));
    }

    public void setUnreadCount(String userId, String channelId, long unreadCount) {
        writeUnreadCount(userId, channelId, unreadCount, null, false);
    }

    public void setUnreadCountWithVersion(String userId, String channelId, long unreadCount, long sourceVersion) {
        writeUnreadCount(userId, channelId, unreadCount, sourceVersion, true);
    }

    public void setUnreadCountFromProjection(String userId, String channelId, long unreadCount, long sourceMessageId) {
        writeUnreadCount(userId, channelId, unreadCount, sourceMessageId, true);
    }

    public Optional<Long> getUnreadCount(String userId, String channelId) {
        if (redis != null) {
            try {
                String value = redis.hget(unreadKey(userId), channelId);
                Optional<Long> parsed = parseLong(value);
                if (parsed.isPresent()) {
                    return parsed;
                }
            } catch (RuntimeException exception) {
                redisErrors.incrementAndGet();
            }
        }
        if (!localProjectionCacheEnabled) {
            return Optional.empty();
        }
        Map<String, Long> unread = localUnread.get(userId);
        if (unread == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(unread.get(channelId));
    }

    public void upsertInboxEntry(String userId, InboxEntry entry) {
        if (entry == null || entry.getChannelId() == null) {
            return;
        }

        boolean written = false;
        if (localProjectionCacheEnabled) {
            ConcurrentMap<String, InboxEntry> userInbox = localInbox.computeIfAbsent(userId, ignored -> new ConcurrentHashMap<>());
            userInbox.compute(entry.getChannelId(), (ignored, existing) -> {
                if (existing == null || entry.getLastMessageId() >= existing.getLastMessageId()) {
                    return entry;
                }
                return existing;
            });
        }

        if (redis != null) {
            try {
                String orderKey = inboxOrderKey(userId);
                String channelId = entry.getChannelId();
                String valueKey = inboxValueKey(userId);
                double nextScore = entry.getLastMessageId();
                Double currentScore = redis.zscore(orderKey, channelId);
                if (currentScore == null || nextScore >= currentScore) {
                    redis.zadd(orderKey, nextScore, channelId);
                    redis.hset(valueKey, channelId, serialize(entry));
                    written = true;
                }
            } catch (RuntimeException exception) {
                redisErrors.incrementAndGet();
            }
        } else {
            written = true;
        }

        if (written) {
            inboxRepairWrites.incrementAndGet();
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
        if (!localProjectionCacheEnabled) {
            return List.of();
        }
        Map<String, InboxEntry> entries = localInbox.get(userId);
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        return entries.values().stream()
            .sorted(Comparator.comparingLong(InboxEntry::getLastMessageId).reversed())
            .limit(limit)
            .toList();
    }

    public void markHotRepairWrite(int count) {
        if (count > 0) {
            hotRepairWrites.addAndGet(count);
        }
    }

    public void markInboxRepairWrite(int count) {
        if (count > 0) {
            inboxRepairWrites.addAndGet(count);
        }
    }

    public void markReadRepairWrite(int count) {
        if (count > 0) {
            readRepairWrites.addAndGet(count);
        }
    }

    public void markUnreadRepairWrite(int count) {
        if (count > 0) {
            unreadRepairWrites.addAndGet(count);
        }
    }

    public void markProjectionDriftDetected() {
        projectionDriftDetected.incrementAndGet();
    }

    public ProjectionCacheRuntimeStats runtimeStats() {
        return ProjectionCacheRuntimeStats.builder()
            .redisEnabled(redis != null)
            .redisAvailable(isRedisAvailable())
            .redisErrors(redisErrors.get())
            .localFallbackReads(localFallbackReads.get())
            .projectionDriftDetected(projectionDriftDetected.get())
            .hotRepairWrites(hotRepairWrites.get())
            .inboxRepairWrites(inboxRepairWrites.get())
            .readRepairWrites(readRepairWrites.get())
            .unreadRepairWrites(unreadRepairWrites.get())
            .build();
    }

    private void writeUnreadCount(String userId, String channelId, long unreadCount, Long sourceVersion, boolean monotonicGuard) {
        long normalized = Math.max(0L, unreadCount);
        Optional<Long> existingVersion = loadUnreadVersion(userId, channelId);

        if (monotonicGuard && sourceVersion != null && existingVersion.isPresent() && sourceVersion < existingVersion.get()) {
            markProjectionDriftDetected();
            return;
        }

        if (localProjectionCacheEnabled) {
            localUnread
                .computeIfAbsent(userId, ignored -> new ConcurrentHashMap<>())
                .put(channelId, normalized);
            if (sourceVersion != null) {
                localUnreadVersion
                    .computeIfAbsent(userId, ignored -> new ConcurrentHashMap<>())
                    .put(channelId, sourceVersion);
            }
        }

        if (redis != null) {
            try {
                redis.hset(unreadKey(userId), channelId, String.valueOf(normalized));
                if (sourceVersion != null) {
                    redis.hset(unreadVersionKey(userId), channelId, String.valueOf(sourceVersion));
                }
            } catch (RuntimeException exception) {
                redisErrors.incrementAndGet();
            }
        }

        unreadRepairWrites.incrementAndGet();
    }

    private Optional<Long> loadUnreadVersion(String userId, String channelId) {
        if (redis != null) {
            try {
                Optional<Long> parsed = parseLong(redis.hget(unreadVersionKey(userId), channelId));
                if (parsed.isPresent()) {
                    return parsed;
                }
            } catch (RuntimeException exception) {
                redisErrors.incrementAndGet();
            }
        }
        if (!localProjectionCacheEnabled) {
            return Optional.empty();
        }
        Map<String, Long> versions = localUnreadVersion.get(userId);
        if (versions == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(versions.get(channelId));
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

    private String unreadVersionKey(String userId) {
        return "user:" + userId + ":unread:version";
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

    private Optional<Long> parseLong(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(value));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }
}
