package com.java_mess.java_mess.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import com.java_mess.java_mess.model.Message;
import com.java_mess.java_mess.model.User;
import com.java_mess.java_mess.service.ChannelMembershipService;
import com.java_mess.java_mess.service.ChannelService;
import com.java_mess.java_mess.service.MessageRuntimeStats;
import com.java_mess.java_mess.service.MessageService;
import com.java_mess.java_mess.service.ReadStateService;
import com.java_mess.java_mess.service.ReadStateSnapshot;
import com.java_mess.java_mess.service.UserService;
import com.java_mess.java_mess.websocket.ChannelWebSocketRegistry;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;

class ApiRouterReadStateTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void updateReadCursorAcceptsMessageIdAndReturnsStoredCursor() throws Exception {
        RecordingReadStateService readStateService = new RecordingReadStateService();
        readStateService.storedCursor = 42L;
        ApiRouter router = createRouter(readStateService);
        FullHttpRequest request = jsonRequest(
            HttpMethod.PUT,
            "/api/channels/ch-1/read-cursor",
            "{\"clientUserId\":\"client-1\",\"lastReadMessageId\":40}"
        );

        FullHttpResponse response = router.route(request);
        try {
            Map<String, Object> body = responseBody(response);
            assertEquals(200, response.status().code());
            assertTrue(readStateService.updateCalled);
            assertEquals("ch-1", readStateService.updateChannelId);
            assertEquals("client-1", readStateService.updateClientUserId);
            assertEquals(40L, readStateService.updateLastReadMessageId);
            assertEquals(42L, ((Number) body.get("lastReadMessageId")).longValue());
        } finally {
            response.release();
            request.release();
        }
    }

    @Test
    void updateReadCursorRejectsNegativeMessageId() throws Exception {
        RecordingReadStateService readStateService = new RecordingReadStateService();
        ApiRouter router = createRouter(readStateService);
        FullHttpRequest request = jsonRequest(
            HttpMethod.PUT,
            "/api/channels/ch-1/read-cursor",
            "{\"clientUserId\":\"client-1\",\"lastReadMessageId\":-1}"
        );

        FullHttpResponse response = router.route(request);
        try {
            Map<String, Object> body = responseBody(response);
            assertEquals(400, response.status().code());
            assertEquals("lastReadMessageId must be non-negative", body.get("error"));
            assertFalse(readStateService.updateCalled);
        } finally {
            response.release();
            request.release();
        }
    }

    @Test
    void getUnreadCountIncludesLastReadMessageIdField() throws Exception {
        RecordingReadStateService readStateService = new RecordingReadStateService();
        readStateService.unreadSnapshot = ReadStateSnapshot.builder()
            .lastReadMessageId(9L)
            .unreadCount(3L)
            .build();
        ApiRouter router = createRouter(readStateService);
        FullHttpRequest request = new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1,
            HttpMethod.GET,
            "/api/channels/ch-1/unread-count?clientUserId=client-1"
        );

        FullHttpResponse response = router.route(request);
        try {
            Map<String, Object> body = responseBody(response);
            assertEquals(200, response.status().code());
            assertEquals("ch-1", readStateService.unreadChannelId);
            assertEquals("client-1", readStateService.unreadClientUserId);
            assertEquals(3L, ((Number) body.get("unreadCount")).longValue());
            assertNotNull(body.get("lastReadMessageId"));
            assertEquals(9L, ((Number) body.get("lastReadMessageId")).longValue());
        } finally {
            response.release();
            request.release();
        }
    }

    private ApiRouter createRouter(ReadStateService readStateService) {
        return new ApiRouter(
            objectMapper,
            new NoopUserService(),
            new NoopChannelService(),
            new NoopChannelMembershipService(),
            new NoopMessageService(),
            readStateService,
            new ChannelWebSocketRegistry(objectMapper),
            new NoopDataSource(),
            AppConfig.builder()
                .port(8082)
                .bossThreads(1)
                .workerThreads(0)
                .businessThreads(0)
                .dbUrl("jdbc:mysql://localhost:3306/messbaas")
                .dbUsername("mess")
                .dbPassword("mess")
                .dbDriverClassName("com.mysql.cj.jdbc.Driver")
                .hotBufferPerChannel(100)
                .build()
        );
    }

    private FullHttpRequest jsonRequest(HttpMethod method, String uri, String body) {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1,
            method,
            uri,
            Unpooled.wrappedBuffer(payload)
        );
        request.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        request.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, payload.length);
        return request;
    }

    private Map<String, Object> responseBody(FullHttpResponse response) throws Exception {
        return objectMapper.readValue(
            response.content().toString(StandardCharsets.UTF_8),
            new TypeReference<>() {
            }
        );
    }

    private static final class RecordingReadStateService implements ReadStateService {
        private boolean updateCalled;
        private String updateChannelId;
        private String updateClientUserId;
        private Long updateLastReadMessageId;
        private long storedCursor;
        private String unreadChannelId;
        private String unreadClientUserId;
        private ReadStateSnapshot unreadSnapshot = ReadStateSnapshot.builder()
            .lastReadMessageId(null)
            .unreadCount(0L)
            .build();

        @Override
        public long updateReadCursor(String channelId, String clientUserId, long lastReadMessageId) {
            this.updateCalled = true;
            this.updateChannelId = channelId;
            this.updateClientUserId = clientUserId;
            this.updateLastReadMessageId = lastReadMessageId;
            return storedCursor;
        }

        @Override
        public ReadStateSnapshot getUnreadCount(String channelId, String clientUserId) {
            this.unreadChannelId = channelId;
            this.unreadClientUserId = clientUserId;
            return unreadSnapshot;
        }
    }

    private static final class NoopUserService implements UserService {
        @Override
        public User createUser(CreateUserRequest request) {
            throw new UnsupportedOperationException("Not used in this test");
        }

        @Override
        public User getUserByClientId(String clientUserId) {
            throw new UnsupportedOperationException("Not used in this test");
        }
    }

    private static final class NoopChannelService implements ChannelService {
        @Override
        public Channel createChannel(CreateChannelRequest request) {
            throw new UnsupportedOperationException("Not used in this test");
        }

        @Override
        public Channel getChannelByReferenceId(String clientReferenceId) {
            throw new UnsupportedOperationException("Not used in this test");
        }

        @Override
        public Channel getChannelById(String id) {
            throw new UnsupportedOperationException("Not used in this test");
        }

        @Override
        public List<Channel> listChannels(Instant beforeCreatedAt, int limit) {
            throw new UnsupportedOperationException("Not used in this test");
        }
    }

    private static final class NoopChannelMembershipService implements ChannelMembershipService {
        @Override
        public void addMember(String channelId, String clientUserId) {
            throw new UnsupportedOperationException("Not used in this test");
        }

        @Override
        public void removeMember(String channelId, String clientUserId) {
            throw new UnsupportedOperationException("Not used in this test");
        }

        @Override
        public List<User> listMembers(String channelId) {
            throw new UnsupportedOperationException("Not used in this test");
        }

        @Override
        public void assertMember(String channelId, String clientUserId) {
            throw new UnsupportedOperationException("Not used in this test");
        }
    }

    private static final class NoopMessageService implements MessageService {
        @Override
        public Message sendMessage(String channelId, SendMessageRequest request) {
            throw new UnsupportedOperationException("Not used in this test");
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

    private static final class NoopDataSource implements DataSource {
        @Override
        public Connection getConnection() throws SQLException {
            throw new SQLException("No database in test");
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            throw new SQLException("No database in test");
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
