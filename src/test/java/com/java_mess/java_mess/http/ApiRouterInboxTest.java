package com.java_mess.java_mess.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.java_mess.java_mess.config.AppConfig;
import com.java_mess.java_mess.dto.channel.CreateChannelRequest;
import com.java_mess.java_mess.dto.message.ListMessageRequest;
import com.java_mess.java_mess.dto.message.SendMessageRequest;
import com.java_mess.java_mess.dto.user.CreateUserRequest;
import com.java_mess.java_mess.model.Channel;
import com.java_mess.java_mess.model.InboxEntry;
import com.java_mess.java_mess.model.Message;
import com.java_mess.java_mess.model.User;
import com.java_mess.java_mess.service.ChannelMembershipService;
import com.java_mess.java_mess.service.ChannelService;
import com.java_mess.java_mess.service.InboxRuntimeStats;
import com.java_mess.java_mess.service.InboxService;
import com.java_mess.java_mess.service.MessageRuntimeStats;
import com.java_mess.java_mess.service.MessageService;
import com.java_mess.java_mess.service.ReadStateService;
import com.java_mess.java_mess.service.ReadStateSnapshot;
import com.java_mess.java_mess.service.UserService;
import com.java_mess.java_mess.websocket.ChannelWebSocketRegistry;

import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;

class ApiRouterInboxTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void listInboxReturnsConversations() throws Exception {
        RecordingInboxService inboxService = new RecordingInboxService();
        inboxService.entries = List.of(
            InboxEntry.builder()
                .channelId("ch-1")
                .lastMessageId(100L)
                .lastSenderClientUserId("alice")
                .lastPreview("hello")
                .unreadCount(2L)
                .build()
        );
        ApiRouter router = createRouter(inboxService);
        FullHttpRequest request = new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1,
            HttpMethod.GET,
            "/api/inbox?clientUserId=client-1&limit=5"
        );

        FullHttpResponse response = router.route(request);
        try {
            Map<String, Object> body = objectMapper.readValue(response.content().toString(StandardCharsets.UTF_8), new TypeReference<>() {
            });
            assertEquals(200, response.status().code());
            assertEquals("client-1", inboxService.lastClientUserId);
            assertEquals(5, inboxService.lastLimit);
            List<?> conversations = (List<?>) body.get("conversations");
            assertNotNull(conversations);
            assertEquals(1, conversations.size());
        } finally {
            response.release();
            request.release();
        }
    }

    @Test
    void listInboxRejectsLimitOverMax() throws Exception {
        RecordingInboxService inboxService = new RecordingInboxService();
        ApiRouter router = createRouter(inboxService);
        FullHttpRequest request = new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1,
            HttpMethod.GET,
            "/api/inbox?clientUserId=client-1&limit=999"
        );

        FullHttpResponse response = router.route(request);
        try {
            Map<String, Object> body = objectMapper.readValue(response.content().toString(StandardCharsets.UTF_8), new TypeReference<>() {
            });
            assertEquals(400, response.status().code());
            assertTrue(String.valueOf(body.get("error")).contains("limit must be <="));
        } finally {
            response.release();
            request.release();
        }
    }

    private ApiRouter createRouter(RecordingInboxService inboxService) {
        return new ApiRouter(
            objectMapper,
            new NoopUserService(),
            new NoopChannelService(),
            new NoopChannelMembershipService(),
            new NoopMessageService(),
            new NoopReadStateService(),
            inboxService,
            new ChannelWebSocketRegistry(objectMapper),
            new NoopDataSource(),
            AppConfig.builder()
                .port(8082)
                .bossThreads(1)
                .workerThreads(0)
                .businessThreads(0)
                .dbUrl("jdbc:mysql://localhost:3306/mess_baas")
                .dbUsername("root")
                .dbPassword("mysql")
                .dbDriverClassName("com.mysql.cj.jdbc.Driver")
                .hotBufferPerChannel(100)
                .inboxDefaultLimit(50)
                .inboxMaxLimit(200)
                .build()
        );
    }

    private static final class RecordingInboxService implements InboxService {
        private String lastClientUserId;
        private int lastLimit;
        private List<InboxEntry> entries = List.of();

        @Override
        public List<InboxEntry> listInbox(String clientUserId, int limit) {
            this.lastClientUserId = clientUserId;
            this.lastLimit = limit;
            return entries;
        }

        @Override
        public InboxRuntimeStats runtimeStats() {
            return InboxRuntimeStats.builder().build();
        }
    }

    private static final class NoopUserService implements UserService {
        @Override
        public User createUser(CreateUserRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public User getUserByClientId(String clientUserId) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class NoopChannelService implements ChannelService {
        @Override
        public Channel createChannel(CreateChannelRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Channel getChannelByReferenceId(String clientReferenceId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Channel getChannelById(String id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Channel> listChannels(Instant beforeCreatedAt, int limit) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class NoopChannelMembershipService implements ChannelMembershipService {
        @Override
        public void addMember(String channelId, String clientUserId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void removeMember(String channelId, String clientUserId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<User> listMembers(String channelId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void assertMember(String channelId, String clientUserId) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class NoopMessageService implements MessageService {
        @Override
        public Message sendMessage(String channelId, SendMessageRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Message> listMessages(ListMessageRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public MessageRuntimeStats runtimeStats() {
            return MessageRuntimeStats.builder().build();
        }
    }

    private static final class NoopReadStateService implements ReadStateService {
        @Override
        public long updateReadCursor(String channelId, String clientUserId, long lastReadMessageId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ReadStateSnapshot getUnreadCount(String channelId, String clientUserId) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class NoopDataSource implements DataSource {
        @Override
        public Connection getConnection() throws SQLException {
            throw new SQLException("No db");
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            throw new SQLException("No db");
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLException("Unsupported");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getGlobal();
        }
    }
}
