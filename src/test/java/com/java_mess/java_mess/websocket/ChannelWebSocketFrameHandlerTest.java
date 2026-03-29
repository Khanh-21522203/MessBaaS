package com.java_mess.java_mess.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.java_mess.java_mess.dto.message.ListMessageRequest;
import com.java_mess.java_mess.dto.message.SendMessageRequest;
import com.java_mess.java_mess.model.Channel;
import com.java_mess.java_mess.model.Message;
import com.java_mess.java_mess.service.MessageRuntimeStats;
import com.java_mess.java_mess.service.MessageService;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

class ChannelWebSocketFrameHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void textFrameWithValidPayloadCallsMessageService() {
        RecordingMessageService messageService = new RecordingMessageService();
        ChannelWebSocketFrameHandler handler = new ChannelWebSocketFrameHandler(
            new ChannelWebSocketRegistry(objectMapper),
            messageService,
            objectMapper
        );

        EmbeddedChannel channel = new EmbeddedChannel(handler);
        channel.attr(ChannelWebSocketRegistry.CHANNEL_ID_ATTRIBUTE).set("ch-1");
        channel.attr(ChannelWebSocketRegistry.CLIENT_USER_ID_ATTRIBUTE).set("client-1");

        channel.writeInbound(new TextWebSocketFrame("{\"clientUserId\":\"client-1\",\"clientMessageId\":\"m-1\",\"message\":\"hello\",\"imgUrl\":\"https://img\"}"));

        assertEquals("ch-1", messageService.lastChannelId);
        assertNotNull(messageService.lastRequest);
        assertEquals("client-1", messageService.lastRequest.getClientUserId());
        assertEquals("m-1", messageService.lastRequest.getClientMessageId());
        assertEquals("hello", messageService.lastRequest.getMessage());
        assertEquals("https://img", messageService.lastRequest.getImgUrl());
        channel.finishAndReleaseAll();
    }

    @Test
    void textFrameWithInvalidPayloadReturnsErrorAndSkipsServiceCall() throws Exception {
        RecordingMessageService messageService = new RecordingMessageService();
        ChannelWebSocketFrameHandler handler = new ChannelWebSocketFrameHandler(
            new ChannelWebSocketRegistry(objectMapper),
            messageService,
            objectMapper
        );

        EmbeddedChannel channel = new EmbeddedChannel(handler);
        channel.attr(ChannelWebSocketRegistry.CHANNEL_ID_ATTRIBUTE).set("ch-1");
        channel.attr(ChannelWebSocketRegistry.CLIENT_USER_ID_ATTRIBUTE).set("client-1");

        channel.writeInbound(new TextWebSocketFrame("{\"clientMessageId\":\"m-1\",\"message\":\"hello\",\"imgUrl\":\"https://img\"}"));

        assertFalse(messageService.called);
        TextWebSocketFrame errorFrame = channel.readOutbound();
        assertNotNull(errorFrame);
        Map<String, String> body = objectMapper.readValue(errorFrame.text(), new TypeReference<>() {});
        assertEquals("clientUserId is required", body.get("error"));

        errorFrame.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void envelopeMessagePayloadCallsMessageService() {
        RecordingMessageService messageService = new RecordingMessageService();
        ChannelWebSocketFrameHandler handler = new ChannelWebSocketFrameHandler(
            new ChannelWebSocketRegistry(objectMapper),
            messageService,
            objectMapper
        );

        EmbeddedChannel channel = new EmbeddedChannel(handler);
        channel.attr(ChannelWebSocketRegistry.CHANNEL_ID_ATTRIBUTE).set("ch-1");
        channel.attr(ChannelWebSocketRegistry.CLIENT_USER_ID_ATTRIBUTE).set("client-1");

        channel.writeInbound(new TextWebSocketFrame("""
            {"type":"message","payload":{"clientUserId":"client-1","clientMessageId":"m-2","message":"hello","imgUrl":"https://img"}}
            """));

        assertTrue(messageService.called);
        assertEquals("m-2", messageService.lastRequest.getClientMessageId());
        channel.finishAndReleaseAll();
    }

    @Test
    void typingEventIsBroadcastWithoutCallingMessageService() throws Exception {
        RecordingMessageService messageService = new RecordingMessageService();
        ChannelWebSocketRegistry registry = new ChannelWebSocketRegistry(objectMapper);
        ChannelWebSocketFrameHandler handler = new ChannelWebSocketFrameHandler(
            registry,
            messageService,
            objectMapper
        );

        EmbeddedChannel channel = new EmbeddedChannel(handler);
        channel.attr(ChannelWebSocketRegistry.CHANNEL_ID_ATTRIBUTE).set("ch-1");
        channel.attr(ChannelWebSocketRegistry.CLIENT_USER_ID_ATTRIBUTE).set("client-1");
        registry.register("ch-1", "client-1", channel);

        channel.writeInbound(new TextWebSocketFrame("""
            {"type":"typing","payload":{"clientUserId":"client-1","isTyping":true}}
            """));

        assertFalse(messageService.called);
        TextWebSocketFrame outbound = channel.readOutbound();
        assertNotNull(outbound);
        Map<String, Object> body = objectMapper.readValue(outbound.text(), new TypeReference<>() {});
        assertEquals("typing", body.get("type"));
        assertEquals("client-1", body.get("clientUserId"));
        assertEquals("ch-1", body.get("channelId"));
        assertEquals(Boolean.TRUE, body.get("isTyping"));
        outbound.release();
        channel.finishAndReleaseAll();
    }

    private static final class RecordingMessageService implements MessageService {
        private boolean called;
        private String lastChannelId;
        private SendMessageRequest lastRequest;

        @Override
        public Message sendMessage(String channelId, SendMessageRequest request) {
            this.called = true;
            this.lastChannelId = channelId;
            this.lastRequest = request;
            return Message.builder()
                .id(1L)
                .channel(Channel.builder().id(channelId).build())
                .message(request.getMessage())
                .imgUrl(request.getImgUrl())
                .build();
        }

        @Override
        public List<Message> listMessages(ListMessageRequest request) {
            throw new UnsupportedOperationException("Not used in this test");
        }

        @Override
        public MessageRuntimeStats runtimeStats() {
            return MessageRuntimeStats.builder().build();
        }
    }
}
