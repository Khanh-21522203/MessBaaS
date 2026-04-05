package com.java_mess.java_mess.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

class ChannelWebSocketRegistryTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void distributedPayloadBroadcastsToLocalSubscribers() throws Exception {
        ChannelWebSocketRegistry registry = new ChannelWebSocketRegistry(objectMapper);
        EmbeddedChannel socket = new EmbeddedChannel();
        registry.register("ch-1", "client-1", socket);
        setPrivateField(registry, "distributedSourceNodeId", "node-a");

        String rawPayload = "{\"type\":\"typing\",\"channelId\":\"ch-1\",\"clientUserId\":\"remote\"}";
        String envelope = objectMapper.writeValueAsString(Map.of(
            "sourceNodeId", "node-b",
            "payload", rawPayload
        ));

        registry.handleDistributedMessageForTest("ws:channel:ch-1", envelope);

        TextWebSocketFrame frame = socket.readOutbound();
        assertNotNull(frame);
        assertEquals(rawPayload, frame.text());
        frame.release();
        socket.finishAndReleaseAll();
    }

    @Test
    void distributedPayloadFromSameNodeIsDropped() throws Exception {
        ChannelWebSocketRegistry registry = new ChannelWebSocketRegistry(objectMapper);
        EmbeddedChannel socket = new EmbeddedChannel();
        registry.register("ch-1", "client-1", socket);
        setPrivateField(registry, "distributedSourceNodeId", "node-a");

        String envelope = objectMapper.writeValueAsString(Map.of(
            "sourceNodeId", "node-a",
            "payload", "{\"type\":\"typing\",\"channelId\":\"ch-1\"}"
        ));

        registry.handleDistributedMessageForTest("ws:channel:ch-1", envelope);

        TextWebSocketFrame frame = socket.readOutbound();
        assertNull(frame);
        WebSocketRegistryStats stats = registry.snapshotStats();
        assertTrue(stats.getDistributedReceiveAttempt() >= 1);
        assertEquals(1L, stats.getDistributedReceiveDroppedLoop());
        socket.finishAndReleaseAll();
    }

    private static void setPrivateField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
