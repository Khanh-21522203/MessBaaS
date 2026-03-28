package com.java_mess.java_mess.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.java_mess.java_mess.dto.message.ListMessageRequest;
import com.java_mess.java_mess.dto.message.SendMessageRequest;
import com.java_mess.java_mess.model.Channel;
import com.java_mess.java_mess.model.Message;
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

        channel.writeInbound(new TextWebSocketFrame("{\"clientUserId\":\"client-1\",\"message\":\"hello\",\"imgUrl\":\"https://img\"}"));

        assertEquals("ch-1", messageService.lastChannelId);
        assertNotNull(messageService.lastRequest);
        assertEquals("client-1", messageService.lastRequest.getClientUserId());
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

        channel.writeInbound(new TextWebSocketFrame("{\"message\":\"hello\",\"imgUrl\":\"https://img\"}"));

        assertFalse(messageService.called);
        TextWebSocketFrame errorFrame = channel.readOutbound();
        assertNotNull(errorFrame);
        Map<String, String> body = objectMapper.readValue(errorFrame.text(), new TypeReference<>() {});
        assertEquals("clientUserId is required", body.get("error"));

        errorFrame.release();
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
    }
}
