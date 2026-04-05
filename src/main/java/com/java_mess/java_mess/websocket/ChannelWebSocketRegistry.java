package com.java_mess.java_mess.websocket;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.java_mess.java_mess.dto.message.MessageEvent;

import io.netty.channel.Channel;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.GlobalEventExecutor;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.JedisPubSub;

@RequiredArgsConstructor
@Slf4j
public class ChannelWebSocketRegistry implements AutoCloseable {
    public static final AttributeKey<String> CHANNEL_ID_ATTRIBUTE = AttributeKey.valueOf("channelId");
    public static final AttributeKey<String> CLIENT_USER_ID_ATTRIBUTE = AttributeKey.valueOf("clientUserId");
    private static final String DISTRIBUTED_CHANNEL_PREFIX = "ws:channel:";

    private final ObjectMapper objectMapper;
    private final ConcurrentMap<String, ChannelGroup> channels = new ConcurrentHashMap<>();
    private final AtomicLong broadcastAttempt = new AtomicLong();
    private final AtomicLong broadcastFailed = new AtomicLong();
    private final AtomicLong distributedPublishAttempt = new AtomicLong();
    private final AtomicLong distributedPublishFailed = new AtomicLong();
    private final AtomicLong distributedReceiveAttempt = new AtomicLong();
    private final AtomicLong distributedReceiveDroppedLoop = new AtomicLong();
    private final AtomicLong distributedReceiveFailed = new AtomicLong();
    private final AtomicBoolean distributedSubscriberStarted = new AtomicBoolean(false);

    private volatile JedisPooled distributedPublisher;
    private volatile String distributedSourceNodeId;
    private volatile ExecutorService distributedSubscriberExecutor;
    private volatile JedisPubSub distributedSubscriber;
    private volatile Jedis distributedSubscriberConnection;

    public void register(String channelId, String clientUserId, Channel channel) {
        channel.attr(CHANNEL_ID_ATTRIBUTE).set(channelId);
        channel.attr(CLIENT_USER_ID_ATTRIBUTE).set(clientUserId);
        channels.computeIfAbsent(channelId, ignored -> new DefaultChannelGroup(GlobalEventExecutor.INSTANCE)).add(channel);
        log.info("Registered websocket channelId={} clientUserId={} remote={}", channelId, clientUserId, channel.remoteAddress());
    }

    public void unregister(Channel channel) {
        String channelId = channel.attr(CHANNEL_ID_ATTRIBUTE).getAndSet(null);
        String clientUserId = channel.attr(CLIENT_USER_ID_ATTRIBUTE).getAndSet(null);
        if (channelId == null) {
            return;
        }
        ChannelGroup group = channels.get(channelId);
        if (group != null) {
            group.remove(channel);
            if (group.isEmpty()) {
                channels.remove(channelId, group);
            }
        }
        log.info("Unregistered websocket channelId={} clientUserId={} remote={}", channelId, clientUserId, channel.remoteAddress());
    }

    public void broadcast(MessageEvent event) {
        broadcast(event.getChannelId(), event);
    }

    public void broadcast(String channelId, Object event) {
        broadcastAttempt.incrementAndGet();
        if (channelId == null || channelId.isBlank()) {
            return;
        }
        String payload = serialize(event);
        if (payload == null) {
            broadcastFailed.incrementAndGet();
            return;
        }
        broadcastSerializedLocal(channelId, payload);
        publishDistributed(channelId, payload);
    }

    public WebSocketRegistryStats snapshotStats() {
        return WebSocketRegistryStats.builder()
            .channelCount(channels.size())
            .activeConnectionCount(channels.values().stream().mapToInt(ChannelGroup::size).sum())
            .broadcastAttempt(broadcastAttempt.get())
            .broadcastFailed(broadcastFailed.get())
            .distributedFanoutEnabled(distributedFanoutEnabled())
            .distributedPublishAttempt(distributedPublishAttempt.get())
            .distributedPublishFailed(distributedPublishFailed.get())
            .distributedReceiveAttempt(distributedReceiveAttempt.get())
            .distributedReceiveDroppedLoop(distributedReceiveDroppedLoop.get())
            .distributedReceiveFailed(distributedReceiveFailed.get())
            .build();
    }

    public synchronized void startDistributedFanout(JedisPooled redisPublisher, String redisHost, int redisPort, String sourceNodeId) {
        if (redisPublisher == null || redisHost == null || redisHost.isBlank() || redisPort <= 0 || sourceNodeId == null || sourceNodeId.isBlank()) {
            log.warn("Distributed websocket fanout not started because config is incomplete");
            return;
        }
        this.distributedPublisher = redisPublisher;
        this.distributedSourceNodeId = sourceNodeId;

        if (!distributedSubscriberStarted.compareAndSet(false, true)) {
            return;
        }

        distributedSubscriberExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ws-distributed-fanout");
            thread.setDaemon(true);
            return thread;
        });
        distributedSubscriberExecutor.submit(() -> subscribeLoop(redisHost, redisPort));
        log.info("Distributed websocket fanout started sourceNodeId={} redisHost={} redisPort={}", sourceNodeId, redisHost, redisPort);
    }

    private void subscribeLoop(String redisHost, int redisPort) {
        try (Jedis connection = new Jedis(redisHost, redisPort)) {
            distributedSubscriberConnection = connection;
            JedisPubSub subscriber = new JedisPubSub() {
                @Override
                public void onPMessage(String pattern, String channel, String message) {
                    handleDistributedMessage(channel, message);
                }
            };
            distributedSubscriber = subscriber;
            connection.psubscribe(subscriber, DISTRIBUTED_CHANNEL_PREFIX + "*");
        } catch (Exception exception) {
            distributedReceiveFailed.incrementAndGet();
            log.warn("Distributed websocket fanout subscriber stopped", exception);
        } finally {
            distributedSubscriber = null;
            distributedSubscriberConnection = null;
            distributedSubscriberStarted.set(false);
        }
    }

    @Override
    public synchronized void close() {
        if (distributedSubscriber != null) {
            try {
                distributedSubscriber.punsubscribe();
            } catch (RuntimeException exception) {
                log.warn("Failed to unsubscribe distributed websocket fanout", exception);
            }
        }
        if (distributedSubscriberConnection != null) {
            try {
                distributedSubscriberConnection.close();
            } catch (RuntimeException exception) {
                log.warn("Failed to close distributed websocket fanout connection", exception);
            }
        }
        if (distributedSubscriberExecutor != null) {
            distributedSubscriberExecutor.shutdownNow();
            distributedSubscriberExecutor = null;
        }
        distributedSubscriber = null;
        distributedSubscriberConnection = null;
        distributedSubscriberStarted.set(false);
    }

    void handleDistributedMessageForTest(String channel, String message) {
        handleDistributedMessage(channel, message);
    }

    private String serialize(Object event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            broadcastFailed.incrementAndGet();
            log.warn("Failed to serialize websocket event", exception);
            return null;
        }
    }

    public int channelCount() {
        return channels.size();
    }

    public int activeConnectionCount() {
        return channels.values().stream().mapToInt(ChannelGroup::size).sum();
    }

    private void broadcastSerializedLocal(String channelId, String payload) {
        ChannelGroup group = channels.get(channelId);
        if (group == null || group.isEmpty()) {
            return;
        }
        group.writeAndFlush(new TextWebSocketFrame(payload));
    }

    private void publishDistributed(String channelId, String payload) {
        if (!distributedFanoutEnabled()) {
            return;
        }
        distributedPublishAttempt.incrementAndGet();
        try {
            FanoutEnvelope envelope = new FanoutEnvelope(distributedSourceNodeId, payload);
            String value = objectMapper.writeValueAsString(envelope);
            distributedPublisher.publish(DISTRIBUTED_CHANNEL_PREFIX + channelId, value);
        } catch (IOException | RuntimeException exception) {
            distributedPublishFailed.incrementAndGet();
            log.warn("Failed to publish websocket event to distributed fanout channelId={}", channelId, exception);
        }
    }

    private void handleDistributedMessage(String channel, String rawPayload) {
        distributedReceiveAttempt.incrementAndGet();
        if (channel == null || !channel.startsWith(DISTRIBUTED_CHANNEL_PREFIX) || rawPayload == null || rawPayload.isBlank()) {
            distributedReceiveFailed.incrementAndGet();
            return;
        }
        try {
            FanoutEnvelope envelope = objectMapper.readValue(rawPayload, FanoutEnvelope.class);
            if (envelope == null || envelope.getPayload() == null || envelope.getPayload().isBlank()) {
                distributedReceiveFailed.incrementAndGet();
                return;
            }
            if (distributedSourceNodeId != null && distributedSourceNodeId.equals(envelope.getSourceNodeId())) {
                distributedReceiveDroppedLoop.incrementAndGet();
                return;
            }
            String channelId = channel.substring(DISTRIBUTED_CHANNEL_PREFIX.length());
            broadcastSerializedLocal(channelId, envelope.getPayload());
        } catch (IOException exception) {
            distributedReceiveFailed.incrementAndGet();
            log.warn("Failed to decode distributed websocket payload channel={}", channel, exception);
        }
    }

    private boolean distributedFanoutEnabled() {
        return distributedPublisher != null && distributedSourceNodeId != null && !distributedSourceNodeId.isBlank();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    private static class FanoutEnvelope {
        private String sourceNodeId;
        private String payload;
    }
}
