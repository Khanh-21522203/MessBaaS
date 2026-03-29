package com.java_mess.java_mess.websocket;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.java_mess.java_mess.dto.message.SendMessageRequest;
import com.java_mess.java_mess.http.RequestValidator;
import com.java_mess.java_mess.model.Message;
import com.java_mess.java_mess.service.MessageService;

import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Sharable
@RequiredArgsConstructor
@Slf4j
public class ChannelWebSocketFrameHandler extends SimpleChannelInboundHandler<WebSocketFrame> {
    private static final int MAX_CLIENT_MESSAGE_ID_LENGTH = 128;
    private static final int MAX_MESSAGE_LENGTH = 10_000;
    private static final int MAX_IMAGE_URL_LENGTH = 2_000;

    private final ChannelWebSocketRegistry channelWebSocketRegistry;
    private final MessageService messageService;
    private final ObjectMapper objectMapper;

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
            String channelId = ctx.channel().attr(ChannelWebSocketRegistry.CHANNEL_ID_ATTRIBUTE).get();
            String clientUserId = ctx.channel().attr(ChannelWebSocketRegistry.CLIENT_USER_ID_ATTRIBUTE).get();
            if (channelId == null || channelId.isBlank() || clientUserId == null || clientUserId.isBlank()) {
                ctx.close();
                return;
            }
            channelWebSocketRegistry.register(channelId, clientUserId, ctx.channel());
            return;
        }
        super.userEventTriggered(ctx, evt);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
        if (frame instanceof PingWebSocketFrame pingFrame) {
            ctx.writeAndFlush(new PongWebSocketFrame(pingFrame.content().retain()));
            return;
        }
        if (frame instanceof CloseWebSocketFrame) {
            ctx.close();
            return;
        }
        if (frame instanceof TextWebSocketFrame textFrame) {
            handleTextFrame(ctx, textFrame.text());
        }
    }

    private void handleTextFrame(ChannelHandlerContext ctx, String payload) {
        String channelId = ctx.channel().attr(ChannelWebSocketRegistry.CHANNEL_ID_ATTRIBUTE).get();
        String connectedClientUserId = ctx.channel().attr(ChannelWebSocketRegistry.CLIENT_USER_ID_ATTRIBUTE).get();
        if (channelId == null || channelId.isBlank()) {
            writeError(ctx, "channelId is required");
            return;
        }
        if (connectedClientUserId == null || connectedClientUserId.isBlank()) {
            writeError(ctx, "clientUserId is required");
            return;
        }

        try {
            JsonNode root = objectMapper.readTree(payload);
            String eventType = stringValue(root, "type");
            if (eventType == null || eventType.isBlank() || "message".equals(eventType)) {
                SendMessageRequest request = parseMessageRequest(payloadNode(root));
                RequestValidator.requireNonBlank(request.getClientUserId(), "clientUserId");
                RequestValidator.requireNonBlank(request.getClientMessageId(), "clientMessageId");
                RequestValidator.requireAtLeastOneNonBlank(request.getMessage(), "message", request.getImgUrl(), "imgUrl");
                RequestValidator.requireMaxLength(request.getClientMessageId(), MAX_CLIENT_MESSAGE_ID_LENGTH, "clientMessageId");
                RequestValidator.requireMaxLength(request.getMessage(), MAX_MESSAGE_LENGTH, "message");
                RequestValidator.requireMaxLength(request.getImgUrl(), MAX_IMAGE_URL_LENGTH, "imgUrl");
                if (!connectedClientUserId.equals(request.getClientUserId())) {
                    throw new IllegalArgumentException("clientUserId does not match websocket identity");
                }

                Message storedMessage = messageService.sendMessage(channelId, request);
                log.debug("Accepted websocket message channelId={} messageId={}", channelId, storedMessage.getId());
                return;
            }

            if ("typing".equals(eventType) || "presence".equals(eventType)) {
                JsonNode eventPayload = payloadNode(root);
                String eventClientUserId = stringValue(eventPayload, "clientUserId");
                RequestValidator.requireNonBlank(eventClientUserId, "clientUserId");
                if (!connectedClientUserId.equals(eventClientUserId)) {
                    throw new IllegalArgumentException("clientUserId does not match websocket identity");
                }

                Map<String, Object> event = new LinkedHashMap<>();
                event.put("type", eventType);
                event.put("channelId", channelId);
                event.put("clientUserId", eventClientUserId);
                copyOptionalString(eventPayload, event, "status");
                copyOptionalString(eventPayload, event, "state");
                copyOptionalBoolean(eventPayload, event, "isTyping");
                channelWebSocketRegistry.broadcast(channelId, event);
                return;
            }

            throw new IllegalArgumentException("Unsupported websocket event type: " + eventType);
        } catch (IllegalArgumentException | JsonProcessingException exception) {
            writeError(ctx, exception.getMessage() == null ? "Invalid websocket payload" : exception.getMessage());
        } catch (Exception exception) {
            log.warn("Failed to process websocket message remote={}", ctx.channel().remoteAddress(), exception);
            writeError(ctx, "Failed to process websocket message");
        }
    }

    private SendMessageRequest parseMessageRequest(JsonNode root) {
        return SendMessageRequest.builder()
            .clientUserId(stringValue(root, "clientUserId"))
            .clientMessageId(stringValue(root, "clientMessageId"))
            .message(stringValue(root, "message"))
            .imgUrl(stringValue(root, "imgUrl"))
            .build();
    }

    private JsonNode payloadNode(JsonNode root) {
        JsonNode payload = root.get("payload");
        return payload != null && payload.isObject() ? payload : root;
    }

    private String stringValue(JsonNode root, String fieldName) {
        JsonNode field = root.get(fieldName);
        if (field == null || field.isNull()) {
            return null;
        }
        return field.asText();
    }

    private void copyOptionalString(JsonNode source, Map<String, Object> target, String fieldName) {
        String value = stringValue(source, fieldName);
        if (value != null && !value.isBlank()) {
            target.put(fieldName, value);
        }
    }

    private void copyOptionalBoolean(JsonNode source, Map<String, Object> target, String fieldName) {
        JsonNode field = source.get(fieldName);
        if (field != null && field.isBoolean()) {
            target.put(fieldName, field.asBoolean());
        }
    }

    private void writeError(ChannelHandlerContext ctx, String error) {
        try {
            String json = objectMapper.writeValueAsString(Map.of("error", error));
            ctx.writeAndFlush(new TextWebSocketFrame(json));
        } catch (JsonProcessingException exception) {
            ctx.writeAndFlush(new TextWebSocketFrame("{\"error\":\"Failed to serialize websocket error\"}"));
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        channelWebSocketRegistry.unregister(ctx.channel());
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("WebSocket error remote={}", ctx.channel().remoteAddress(), cause);
        channelWebSocketRegistry.unregister(ctx.channel());
        ctx.close();
    }
}
