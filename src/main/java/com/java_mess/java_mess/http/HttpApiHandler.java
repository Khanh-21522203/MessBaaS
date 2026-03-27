package com.java_mess.java_mess.http;

import static io.netty.handler.codec.http.HttpHeaderNames.CONNECTION;
import static io.netty.handler.codec.http.HttpHeaderValues.KEEP_ALIVE;
import static io.netty.handler.codec.http.HttpHeaderValues.WEBSOCKET;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.QueryStringDecoder;
import lombok.RequiredArgsConstructor;

@Sharable
@RequiredArgsConstructor
public class HttpApiHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private final ApiRouter apiRouter;

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (isWebSocketUpgrade(request)) {
            ctx.fireChannelRead(request.retain());
            return;
        }

        FullHttpResponse response = apiRouter.route(request);
        boolean keepAlive = HttpUtil.isKeepAlive(request);
        if (keepAlive) {
            response.headers().set(CONNECTION, KEEP_ALIVE);
        }

        if (keepAlive) {
            ctx.writeAndFlush(response);
        } else {
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }
    }

    private boolean isWebSocketUpgrade(FullHttpRequest request) {
        String upgrade = request.headers().get("Upgrade");
        if (upgrade == null || !WEBSOCKET.toString().equalsIgnoreCase(upgrade)) {
            return false;
        }
        QueryStringDecoder decoder = new QueryStringDecoder(request.uri());
        return "/ws/channels".equals(decoder.path());
    }
}
