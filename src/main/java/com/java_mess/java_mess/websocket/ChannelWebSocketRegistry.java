package com.java_mess.java_mess.websocket;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.java_mess.java_mess.dto.message.MessageEvent;

import io.netty.channel.Channel;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.GlobalEventExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RequiredArgsConstructor
@Slf4j
public class ChannelWebSocketRegistry {
    public static final AttributeKey<String> CHANNEL_ID_ATTRIBUTE = AttributeKey.valueOf("channelId");
    public static final AttributeKey<String> CLIENT_USER_ID_ATTRIBUTE = AttributeKey.valueOf("clientUserId");

    private final ObjectMapper objectMapper;
    private final ConcurrentMap<String, ChannelGroup> channels = new ConcurrentHashMap<>();

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
        if (channelId == null || channelId.isBlank()) {
            return;
        }
        ChannelGroup group = channels.get(channelId);
        if (group == null || group.isEmpty()) {
            return;
        }
        String payload = serialize(event);
        if (payload == null) {
            return;
        }
        group.writeAndFlush(new TextWebSocketFrame(payload));
    }

    public WebSocketRegistryStats snapshotStats() {
        return WebSocketRegistryStats.builder()
            .channelCount(channels.size())
            .activeConnectionCount(channels.values().stream().mapToInt(ChannelGroup::size).sum())
            .build();
    }

    private String serialize(Object event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
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
}
