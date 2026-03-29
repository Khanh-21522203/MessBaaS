package com.java_mess.java_mess.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.java_mess.java_mess.model.User;
import com.java_mess.java_mess.service.ChannelMembershipService;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;

class WebSocketHandshakeHandlerTest {

    @Test
    void handshakeRequiresClientUserIdQueryParam() {
        RecordingMembershipService membershipService = new RecordingMembershipService();
        WebSocketHandshakeHandler handler = new WebSocketHandshakeHandler(membershipService);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        FullHttpRequest request = new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1,
            HttpMethod.GET,
            "/ws/channels?channelId=ch-1"
        );

        channel.writeInbound(request.retain());

        FullHttpResponse response = channel.readOutbound();
        assertNotNull(response);
        assertEquals(400, response.status().code());
        assertTrue(response.content().toString(io.netty.util.CharsetUtil.UTF_8).contains("clientUserId query param is required"));
        response.release();
        request.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void validHandshakeSetsAttributesAndForwardsSanitizedRequest() {
        RecordingMembershipService membershipService = new RecordingMembershipService();
        WebSocketHandshakeHandler handler = new WebSocketHandshakeHandler(membershipService);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        FullHttpRequest request = new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1,
            HttpMethod.GET,
            "/ws/channels?channelId=ch-1&clientUserId=client-1"
        );

        channel.writeInbound(request.retain());

        FullHttpRequest forwarded = channel.readInbound();
        assertNotNull(forwarded);
        assertEquals("/ws/channels", forwarded.uri());
        assertEquals("ch-1", channel.attr(ChannelWebSocketRegistry.CHANNEL_ID_ATTRIBUTE).get());
        assertEquals("client-1", channel.attr(ChannelWebSocketRegistry.CLIENT_USER_ID_ATTRIBUTE).get());
        assertEquals("ch-1", membershipService.lastChannelId);
        assertEquals("client-1", membershipService.lastClientUserId);

        forwarded.release();
        request.release();
        channel.finishAndReleaseAll();
    }

    private static final class RecordingMembershipService implements ChannelMembershipService {
        private String lastChannelId;
        private String lastClientUserId;

        @Override
        public void addMember(String channelId, String clientUserId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void removeMember(String channelId, String clientUserId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.List<User> listMembers(String channelId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void assertMember(String channelId, String clientUserId) {
            this.lastChannelId = channelId;
            this.lastClientUserId = clientUserId;
        }
    }
}
