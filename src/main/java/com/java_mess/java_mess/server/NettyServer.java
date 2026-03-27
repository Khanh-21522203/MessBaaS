package com.java_mess.java_mess.server;

import com.java_mess.java_mess.config.AppConfig;
import com.java_mess.java_mess.http.HttpApiHandler;
import com.java_mess.java_mess.websocket.ChannelWebSocketFrameHandler;
import com.java_mess.java_mess.websocket.WebSocketHandshakeHandler;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RequiredArgsConstructor
@Slf4j
public class NettyServer {
    private final AppConfig config;
    private final HttpApiHandler httpApiHandler;
    private final WebSocketHandshakeHandler webSocketHandshakeHandler;
    private final ChannelWebSocketFrameHandler channelWebSocketFrameHandler;

    public void start() throws InterruptedException {
        int workerThreads = config.getWorkerThreads() > 0 ? config.getWorkerThreads() : Runtime.getRuntime().availableProcessors() * 2;
        int businessThreads = config.getBusinessThreads() > 0 ? config.getBusinessThreads() : Math.max(4, Runtime.getRuntime().availableProcessors() * 2);

        EventLoopGroup bossGroup = new NioEventLoopGroup(config.getBossThreads());
        EventLoopGroup workerGroup = new NioEventLoopGroup(workerThreads);
        DefaultEventExecutorGroup businessGroup = new DefaultEventExecutorGroup(businessThreads);

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 1024)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        channel.pipeline().addLast(new HttpServerCodec());
                        channel.pipeline().addLast(new HttpObjectAggregator(1024 * 1024));
                        channel.pipeline().addLast(new ChunkedWriteHandler());
                        channel.pipeline().addLast(businessGroup, httpApiHandler);
                        channel.pipeline().addLast(webSocketHandshakeHandler);
                        channel.pipeline().addLast(new WebSocketServerProtocolHandler("/ws/channels", null, true));
                        channel.pipeline().addLast(channelWebSocketFrameHandler);
                    }
                });

            log.info("Starting Netty server port={} workerThreads={} businessThreads={}", config.getPort(), workerThreads, businessThreads);
            bootstrap.bind(config.getPort()).sync().channel().closeFuture().sync();
        } finally {
            businessGroup.shutdownGracefully().sync();
            workerGroup.shutdownGracefully().sync();
            bossGroup.shutdownGracefully().sync();
        }
    }
}
