package com.java_mess.java_mess.websocket;

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

import java.nio.charset.StandardCharsets;
import java.util.List;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.QueryStringDecoder;

@Sharable
public class WebSocketHandshakeHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        QueryStringDecoder decoder = new QueryStringDecoder(request.uri());
        if (!"/ws/channels".equals(decoder.path())) {
            writeError(ctx, NOT_FOUND, "WebSocket route not found");
            return;
        }

        List<String> channelIds = decoder.parameters().get("channelId");
        if (channelIds == null || channelIds.isEmpty() || channelIds.get(0).isBlank()) {
            writeError(ctx, BAD_REQUEST, "channelId query param is required");
            return;
        }

        ctx.channel().attr(ChannelWebSocketRegistry.CHANNEL_ID_ATTRIBUTE).set(channelIds.get(0));
        request.setUri("/ws/channels");
        ctx.fireChannelRead(request.retain());
    }

    private void writeError(ChannelHandlerContext ctx, io.netty.handler.codec.http.HttpResponseStatus status, String message) {
        byte[] json = ("{\"error\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8);
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, status, Unpooled.wrappedBuffer(json));
        response.headers().set(CONTENT_TYPE, "application/json; charset=UTF-8");
        response.headers().setInt(CONTENT_LENGTH, json.length);
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }
}
