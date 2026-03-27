package com.java_mess.java_mess.websocket;

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
    private final ChannelWebSocketRegistry channelWebSocketRegistry;

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
            String channelId = ctx.channel().attr(ChannelWebSocketRegistry.CHANNEL_ID_ATTRIBUTE).get();
            if (channelId == null || channelId.isBlank()) {
                ctx.close();
                return;
            }
            channelWebSocketRegistry.register(channelId, ctx.channel());
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
            log.debug("Ignoring inbound websocket payload remote={} payload={}", ctx.channel().remoteAddress(), textFrame.text());
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
