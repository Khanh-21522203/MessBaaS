package com.java_mess.java_mess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.java_mess.java_mess.config.AppConfig;
import com.java_mess.java_mess.config.AppConfigLoader;
import com.java_mess.java_mess.http.ApiRouter;
import com.java_mess.java_mess.http.HttpApiHandler;
import com.java_mess.java_mess.repository.ChannelRepository;
import com.java_mess.java_mess.repository.MessageRepository;
import com.java_mess.java_mess.repository.UserRepository;
import com.java_mess.java_mess.server.NettyServer;
import com.java_mess.java_mess.service.ChannelService;
import com.java_mess.java_mess.service.ChannelServiceImpl;
import com.java_mess.java_mess.service.ChannelMessageHotStore;
import com.java_mess.java_mess.service.MessageService;
import com.java_mess.java_mess.service.MessageServiceImpl;
import com.java_mess.java_mess.service.UserService;
import com.java_mess.java_mess.service.UserServiceImpl;
import com.java_mess.java_mess.websocket.ChannelWebSocketFrameHandler;
import com.java_mess.java_mess.websocket.ChannelWebSocketRegistry;
import com.java_mess.java_mess.websocket.WebSocketHandshakeHandler;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.flywaydb.core.Flyway;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MessBaaSServer {
    public static void main(String[] args) throws Exception {
        AppConfig config = AppConfigLoader.load();
        HikariDataSource dataSource = createDataSource(config);
        runMigrations(dataSource);

        ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        UserRepository userRepository = new UserRepository(dataSource);
        ChannelRepository channelRepository = new ChannelRepository(dataSource);
        MessageRepository messageRepository = new MessageRepository(dataSource);

        ChannelWebSocketRegistry channelWebSocketRegistry = new ChannelWebSocketRegistry(objectMapper);
        ChannelMessageHotStore channelMessageHotStore = new ChannelMessageHotStore(config.getHotBufferPerChannel());

        UserService userService = new UserServiceImpl(userRepository);
        ChannelService channelService = new ChannelServiceImpl(channelRepository);
        MessageService messageService = new MessageServiceImpl(
            messageRepository,
            channelRepository,
            userRepository,
            channelWebSocketRegistry,
            channelMessageHotStore
        );

        ApiRouter apiRouter = new ApiRouter(objectMapper, userService, channelService, messageService);
        HttpApiHandler httpApiHandler = new HttpApiHandler(apiRouter);
        WebSocketHandshakeHandler webSocketHandshakeHandler = new WebSocketHandshakeHandler();
        ChannelWebSocketFrameHandler webSocketFrameHandler = new ChannelWebSocketFrameHandler(
            channelWebSocketRegistry,
            messageService,
            objectMapper
        );

        NettyServer nettyServer = new NettyServer(config, httpApiHandler, webSocketHandshakeHandler, webSocketFrameHandler);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down MessBaaS");
            dataSource.close();
        }));

        nettyServer.start();
    }

    private static HikariDataSource createDataSource(AppConfig config) {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(config.getDbUrl());
        hikariConfig.setUsername(config.getDbUsername());
        hikariConfig.setPassword(config.getDbPassword());
        hikariConfig.setDriverClassName(config.getDbDriverClassName());
        hikariConfig.setMaximumPoolSize(resolveBusinessThreads(config) * 2);
        hikariConfig.setMinimumIdle(Math.max(2, resolveBusinessThreads(config)));
        hikariConfig.setAutoCommit(true);
        return new HikariDataSource(hikariConfig);
    }

    private static void runMigrations(HikariDataSource dataSource) {
        Flyway.configure()
            .dataSource(dataSource)
            .baselineOnMigrate(true)
            .validateOnMigrate(true)
            .load()
            .migrate();
    }

    private static int resolveBusinessThreads(AppConfig config) {
        return config.getBusinessThreads() > 0
            ? config.getBusinessThreads()
            : Math.max(4, Runtime.getRuntime().availableProcessors() * 2);
    }
}
