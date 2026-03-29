package com.java_mess.java_mess.http;

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static io.netty.handler.codec.http.HttpResponseStatus.CONFLICT;
import static io.netty.handler.codec.http.HttpResponseStatus.FORBIDDEN;
import static io.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpResponseStatus.SERVICE_UNAVAILABLE;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.java_mess.java_mess.config.AppConfig;
import com.java_mess.java_mess.dto.channel.CreateChannelRequest;
import com.java_mess.java_mess.dto.channel.CreateChannelResponse;
import com.java_mess.java_mess.dto.channel.GetChannelResponse;
import com.java_mess.java_mess.dto.channel.ListChannelResponse;
import com.java_mess.java_mess.dto.channel.AddChannelMemberRequest;
import com.java_mess.java_mess.dto.channel.ChannelMemberResponse;
import com.java_mess.java_mess.dto.channel.ListChannelMemberResponse;
import com.java_mess.java_mess.dto.readstate.GetUnreadCountResponse;
import com.java_mess.java_mess.dto.readstate.UpdateReadCursorRequest;
import com.java_mess.java_mess.dto.readstate.UpdateReadCursorResponse;
import com.java_mess.java_mess.dto.message.ListMessageRequest;
import com.java_mess.java_mess.dto.message.ListMessageResponse;
import com.java_mess.java_mess.dto.message.SendMessageRequest;
import com.java_mess.java_mess.dto.message.SendMessageResponse;
import com.java_mess.java_mess.dto.user.CreateUserRequest;
import com.java_mess.java_mess.dto.user.CreateUserResponse;
import com.java_mess.java_mess.dto.user.GetUserResponse;
import com.java_mess.java_mess.exception.ChannelExistedException;
import com.java_mess.java_mess.exception.ChannelAccessDeniedException;
import com.java_mess.java_mess.exception.ChannelMemberExistedException;
import com.java_mess.java_mess.exception.ChannelMemberNotFoundException;
import com.java_mess.java_mess.exception.ChannelNotFoundException;
import com.java_mess.java_mess.exception.ClientMessageConflictException;
import com.java_mess.java_mess.exception.ClientUserIdExistedException;
import com.java_mess.java_mess.exception.UserNotFoundException;
import com.java_mess.java_mess.model.Channel;
import com.java_mess.java_mess.model.Message;
import com.java_mess.java_mess.model.User;
import com.java_mess.java_mess.service.ChannelService;
import com.java_mess.java_mess.service.MessageService;
import com.java_mess.java_mess.service.ChannelMembershipService;
import com.java_mess.java_mess.service.ReadStateService;
import com.java_mess.java_mess.service.ReadStateSnapshot;
import com.java_mess.java_mess.service.UserService;
import com.java_mess.java_mess.websocket.ChannelWebSocketRegistry;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.QueryStringDecoder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RequiredArgsConstructor
@Slf4j
public class ApiRouter {
    private static final int MAX_CLIENT_MESSAGE_ID_LENGTH = 128;
    private static final int MAX_MESSAGE_LENGTH = 10_000;
    private static final int MAX_IMAGE_URL_LENGTH = 2_000;

    private final ObjectMapper objectMapper;
    private final UserService userService;
    private final ChannelService channelService;
    private final ChannelMembershipService channelMembershipService;
    private final MessageService messageService;
    private final ReadStateService readStateService;
    private final ChannelWebSocketRegistry channelWebSocketRegistry;
    private final DataSource dataSource;
    private final AppConfig appConfig;

    public FullHttpResponse route(FullHttpRequest request) {
        QueryStringDecoder decoder = new QueryStringDecoder(request.uri());
        String path = normalizePath(decoder.path());
        HttpMethod method = request.method();

        try {
            if (HttpMethod.GET.equals(method) && isLiveHealthPath(path)) {
                return liveHealth();
            }
            if (HttpMethod.GET.equals(method) && isReadyHealthPath(path)) {
                return readyHealth();
            }
            if (HttpMethod.GET.equals(method) && "/api/ops/stats".equals(path)) {
                return runtimeStats();
            }
            if (HttpMethod.POST.equals(method) && "/api/users".equals(path)) {
                return createUser(request);
            }
            if (HttpMethod.GET.equals(method) && isUserLookupPath(path)) {
                return getUser(path);
            }
            if (HttpMethod.POST.equals(method) && "/api/channels".equals(path)) {
                return createChannel(request);
            }
            if (HttpMethod.GET.equals(method) && isChannelByReferencePath(path)) {
                return getChannelByReference(path);
            }
            if (HttpMethod.GET.equals(method) && "/api/channels".equals(path)) {
                return listChannels(decoder);
            }
            if (HttpMethod.GET.equals(method) && isChannelByIdPath(path)) {
                return getChannelById(path);
            }
            if (HttpMethod.POST.equals(method) && isChannelMembersPath(path)) {
                return addChannelMember(path, request);
            }
            if (HttpMethod.DELETE.equals(method) && isChannelMemberByUserPath(path)) {
                return removeChannelMember(path);
            }
            if (HttpMethod.GET.equals(method) && isChannelMembersPath(path)) {
                return listChannelMembers(path);
            }
            if (HttpMethod.PUT.equals(method) && isReadCursorPath(path)) {
                return updateReadCursor(path, request);
            }
            if (HttpMethod.GET.equals(method) && isUnreadCountPath(path)) {
                return getUnreadCount(path, decoder);
            }
            if (HttpMethod.POST.equals(method) && isMessagePath(path)) {
                return sendMessage(path, request);
            }
            if (HttpMethod.GET.equals(method) && isMessagePath(path)) {
                return listMessages(path, decoder);
            }
            return jsonResponse(NOT_FOUND, Map.of("error", "Route not found"));
        } catch (Exception exception) {
            log.warn("Request failed path={} method={}", path, method, exception);
            return jsonResponse(statusFor(exception), Map.of("error", messageFor(exception)));
        }
    }

    private FullHttpResponse createUser(FullHttpRequest request) throws JsonProcessingException {
        CreateUserRequest payload = readBody(request, CreateUserRequest.class);
        RequestValidator.requireNonBlank(payload.getName(), "name");
        RequestValidator.requireNonBlank(payload.getClientUserId(), "clientUserId");
        RequestValidator.requireNonBlank(payload.getProfileImgUrl(), "profileImgUrl");

        User user = userService.createUser(payload);
        return jsonResponse(OK, CreateUserResponse.builder().user(user).build());
    }

    private FullHttpResponse getUser(String path) throws JsonProcessingException {
        String clientUserId = pathSegment(path, 2);
        User user = userService.getUserByClientId(clientUserId);
        return jsonResponse(OK, GetUserResponse.builder().user(user).build());
    }

    private FullHttpResponse createChannel(FullHttpRequest request) throws JsonProcessingException {
        CreateChannelRequest payload = readBody(request, CreateChannelRequest.class);
        RequestValidator.requireNonBlank(payload.getName(), "name");
        RequestValidator.requireNonBlank(payload.getClientReferenceId(), "clientReferenceId");

        Channel channel = channelService.createChannel(payload);
        return jsonResponse(OK, CreateChannelResponse.builder().channel(channel).build());
    }

    private FullHttpResponse getChannelByReference(String path) throws JsonProcessingException {
        String clientReferenceId = pathSegment(path, 2);
        Channel channel = channelService.getChannelByReferenceId(clientReferenceId);
        return jsonResponse(OK, GetChannelResponse.builder().channel(channel).build());
    }

    private FullHttpResponse getChannelById(String path) throws JsonProcessingException {
        String channelId = pathSegment(path, 2);
        Channel channel = channelService.getChannelById(channelId);
        return jsonResponse(OK, GetChannelResponse.builder().channel(channel).build());
    }

    private FullHttpResponse listChannels(QueryStringDecoder decoder) throws JsonProcessingException {
        int limit = RequestValidator.requireInt(queryParamOrDefault(decoder, "limit", "50"), "limit");
        RequestValidator.requirePositive(limit, "limit");

        String beforeCreatedAtValue = optionalQueryParam(decoder, "beforeCreatedAt");
        Instant beforeCreatedAt = beforeCreatedAtValue == null
            ? null
            : RequestValidator.requireInstant(beforeCreatedAtValue, "beforeCreatedAt");

        List<Channel> channels = channelService.listChannels(beforeCreatedAt, limit);
        return jsonResponse(OK, ListChannelResponse.builder().channels(channels).build());
    }

    private FullHttpResponse addChannelMember(String path, FullHttpRequest request) throws JsonProcessingException {
        String channelId = pathSegment(path, 2);
        AddChannelMemberRequest payload = readBody(request, AddChannelMemberRequest.class);
        RequestValidator.requireNonBlank(payload.getClientUserId(), "clientUserId");

        channelMembershipService.addMember(channelId, payload.getClientUserId());
        return jsonResponse(
            OK,
            ChannelMemberResponse.builder()
                .channelId(channelId)
                .clientUserId(payload.getClientUserId())
                .build()
        );
    }

    private FullHttpResponse removeChannelMember(String path) throws JsonProcessingException {
        String channelId = pathSegment(path, 2);
        String clientUserId = pathSegment(path, 4);
        channelMembershipService.removeMember(channelId, clientUserId);
        return jsonResponse(
            OK,
            ChannelMemberResponse.builder()
                .channelId(channelId)
                .clientUserId(clientUserId)
                .build()
        );
    }

    private FullHttpResponse listChannelMembers(String path) throws JsonProcessingException {
        String channelId = pathSegment(path, 2);
        List<User> members = channelMembershipService.listMembers(channelId);
        return jsonResponse(OK, ListChannelMemberResponse.builder().members(members).build());
    }

    private FullHttpResponse updateReadCursor(String path, FullHttpRequest request) throws JsonProcessingException {
        String channelId = pathSegment(path, 2);
        UpdateReadCursorRequest payload = readBody(request, UpdateReadCursorRequest.class);
        RequestValidator.requireNonBlank(payload.getClientUserId(), "clientUserId");
        if (payload.getLastReadMessageId() == null) {
            throw new IllegalArgumentException("lastReadMessageId is required");
        }
        RequestValidator.requireNonNegative(payload.getLastReadMessageId(), "lastReadMessageId");
        long storedCursor = readStateService.updateReadCursor(
            channelId,
            payload.getClientUserId(),
            payload.getLastReadMessageId()
        );

        return jsonResponse(
            OK,
            UpdateReadCursorResponse.builder()
                .channelId(channelId)
                .clientUserId(payload.getClientUserId())
                .lastReadMessageId(storedCursor)
                .build()
        );
    }

    private FullHttpResponse getUnreadCount(String path, QueryStringDecoder decoder) throws JsonProcessingException {
        String channelId = pathSegment(path, 2);
        String clientUserId = queryParam(decoder, "clientUserId");
        RequestValidator.requireNonBlank(clientUserId, "clientUserId");

        ReadStateSnapshot snapshot = readStateService.getUnreadCount(channelId, clientUserId);
        return jsonResponse(
            OK,
            GetUnreadCountResponse.builder()
                .channelId(channelId)
                .clientUserId(clientUserId)
                .unreadCount(snapshot.getUnreadCount())
                .lastReadMessageId(snapshot.getLastReadMessageId())
                .build()
        );
    }

    private FullHttpResponse liveHealth() {
        return jsonResponse(OK, Map.of("status", "UP"));
    }

    private FullHttpResponse readyHealth() {
        boolean ready = isDatabaseReady();
        HttpResponseStatus status = ready ? OK : SERVICE_UNAVAILABLE;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", ready ? "UP" : "DOWN");
        body.put("databaseReady", ready);
        body.put("bossThreads", appConfig.getBossThreads());
        body.put("workerThreads", resolveWorkerThreads());
        body.put("businessThreads", resolveBusinessThreads());
        return jsonResponse(status, body);
    }

    private FullHttpResponse runtimeStats() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", messageService.runtimeStats());
        body.put("websocket", channelWebSocketRegistry.snapshotStats());
        body.put("runtime", Map.of(
            "bossThreads", appConfig.getBossThreads(),
            "workerThreads", resolveWorkerThreads(),
            "businessThreads", resolveBusinessThreads()
        ));
        return jsonResponse(OK, body);
    }

    private FullHttpResponse sendMessage(String path, FullHttpRequest request) throws JsonProcessingException {
        String channelId = pathSegment(path, 2);
        SendMessageRequest payload = readBody(request, SendMessageRequest.class);
        RequestValidator.requireNonBlank(payload.getClientUserId(), "clientUserId");
        RequestValidator.requireNonBlank(payload.getClientMessageId(), "clientMessageId");
        RequestValidator.requireAtLeastOneNonBlank(payload.getMessage(), "message", payload.getImgUrl(), "imgUrl");
        RequestValidator.requireMaxLength(payload.getClientMessageId(), MAX_CLIENT_MESSAGE_ID_LENGTH, "clientMessageId");
        RequestValidator.requireMaxLength(payload.getMessage(), MAX_MESSAGE_LENGTH, "message");
        RequestValidator.requireMaxLength(payload.getImgUrl(), MAX_IMAGE_URL_LENGTH, "imgUrl");

        Message message = messageService.sendMessage(channelId, payload);
        return jsonResponse(OK, SendMessageResponse.builder().message(message).build());
    }

    private FullHttpResponse listMessages(String path, QueryStringDecoder decoder) throws JsonProcessingException {
        String channelId = pathSegment(path, 2);
        String clientUserId = queryParam(decoder, "clientUserId");
        RequestValidator.requireNonBlank(clientUserId, "clientUserId");
        long pivotId = RequestValidator.requireLong(queryParam(decoder, "pivotId"), "pivotId");
        int prevLimit = RequestValidator.requireInt(queryParam(decoder, "prevLimit"), "prevLimit");
        int nextLimit = RequestValidator.requireInt(queryParam(decoder, "nextLimit"), "nextLimit");
        RequestValidator.requireNonNegative(prevLimit, "prevLimit");
        RequestValidator.requireNonNegative(nextLimit, "nextLimit");

        List<Message> messages = messageService.listMessages(ListMessageRequest.builder()
            .channelId(channelId)
            .clientUserId(clientUserId)
            .pivotId(pivotId)
            .prevLimit(prevLimit)
            .nextLimit(nextLimit)
            .build());
        return jsonResponse(OK, ListMessageResponse.builder().messages(messages).build());
    }

    private <T> T readBody(FullHttpRequest request, Class<T> type) throws JsonProcessingException {
        String body = request.content().toString(StandardCharsets.UTF_8);
        if (body.isBlank()) {
            throw new IllegalArgumentException("Request body is required");
        }
        return objectMapper.readValue(body, type);
    }

    private FullHttpResponse jsonResponse(HttpResponseStatus status, Object body) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(body);
            DefaultFullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, status, Unpooled.wrappedBuffer(json));
            response.headers().set(CONTENT_TYPE, "application/json; charset=UTF-8");
            response.headers().setInt(CONTENT_LENGTH, json.length);
            return response;
        } catch (JsonProcessingException exception) {
            byte[] json = "{\"error\":\"Failed to serialize response\"}".getBytes(StandardCharsets.UTF_8);
            DefaultFullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, INTERNAL_SERVER_ERROR, Unpooled.wrappedBuffer(json));
            response.headers().set(CONTENT_TYPE, "application/json; charset=UTF-8");
            response.headers().setInt(CONTENT_LENGTH, json.length);
            return response;
        }
    }

    private HttpResponseStatus statusFor(Exception exception) {
        if (exception instanceof IllegalArgumentException || exception instanceof JsonProcessingException) {
            return BAD_REQUEST;
        }
        if (exception instanceof UserNotFoundException || exception instanceof ChannelNotFoundException) {
            return NOT_FOUND;
        }
        if (exception instanceof ChannelAccessDeniedException) {
            return FORBIDDEN;
        }
        if (exception instanceof ClientUserIdExistedException || exception instanceof ChannelExistedException) {
            return CONFLICT;
        }
        if (exception instanceof ChannelMemberExistedException) {
            return CONFLICT;
        }
        if (exception instanceof ClientMessageConflictException) {
            return CONFLICT;
        }
        if (exception instanceof ChannelMemberNotFoundException) {
            return NOT_FOUND;
        }
        return INTERNAL_SERVER_ERROR;
    }

    private String messageFor(Exception exception) {
        return exception.getMessage() != null && !exception.getMessage().isBlank()
            ? exception.getMessage()
            : exception.getClass().getSimpleName();
    }

    private boolean isUserLookupPath(String path) {
        String[] segments = segments(path);
        return segments.length == 4
            && "api".equals(segments[0])
            && "users".equals(segments[1])
            && "by-client-user-id".equals(segments[3]);
    }

    private boolean isChannelByIdPath(String path) {
        String[] segments = segments(path);
        return segments.length == 3
            && "api".equals(segments[0])
            && "channels".equals(segments[1]);
    }

    private boolean isChannelByReferencePath(String path) {
        String[] segments = segments(path);
        return segments.length == 4
            && "api".equals(segments[0])
            && "channels".equals(segments[1])
            && "by-reference-id".equals(segments[3]);
    }

    private boolean isMessagePath(String path) {
        String[] segments = segments(path);
        return segments.length == 3
            && "api".equals(segments[0])
            && "messages".equals(segments[1]);
    }

    private boolean isLiveHealthPath(String path) {
        return "/health/live".equals(path) || "/healthz".equals(path);
    }

    private boolean isReadyHealthPath(String path) {
        return "/health/ready".equals(path) || "/readyz".equals(path);
    }

    private boolean isChannelMembersPath(String path) {
        String[] segments = segments(path);
        return segments.length == 4
            && "api".equals(segments[0])
            && "channels".equals(segments[1])
            && "members".equals(segments[3]);
    }

    private boolean isChannelMemberByUserPath(String path) {
        String[] segments = segments(path);
        return segments.length == 5
            && "api".equals(segments[0])
            && "channels".equals(segments[1])
            && "members".equals(segments[3]);
    }

    private boolean isReadCursorPath(String path) {
        String[] segments = segments(path);
        return segments.length == 4
            && "api".equals(segments[0])
            && "channels".equals(segments[1])
            && "read-cursor".equals(segments[3]);
    }

    private boolean isUnreadCountPath(String path) {
        String[] segments = segments(path);
        return segments.length == 4
            && "api".equals(segments[0])
            && "channels".equals(segments[1])
            && "unread-count".equals(segments[3]);
    }

    private String pathSegment(String path, int index) {
        return segments(path)[index];
    }

    private String[] segments(String path) {
        String normalized = normalizePath(path);
        return normalized.startsWith("/") ? normalized.substring(1).split("/") : normalized.split("/");
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        if (path.length() > 1 && path.endsWith("/")) {
            return path.substring(0, path.length() - 1);
        }
        return path;
    }

    private String queryParam(QueryStringDecoder decoder, String key) {
        List<String> values = decoder.parameters().get(key);
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("Missing query param: " + key);
        }
        return values.get(0);
    }

    private String optionalQueryParam(QueryStringDecoder decoder, String key) {
        List<String> values = decoder.parameters().get(key);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private String queryParamOrDefault(QueryStringDecoder decoder, String key, String defaultValue) {
        String value = optionalQueryParam(decoder, key);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private boolean isDatabaseReady() {
        try (
            Connection connection = dataSource.getConnection();
            PreparedStatement statement = connection.prepareStatement("select 1");
            ResultSet ignored = statement.executeQuery()
        ) {
            return true;
        } catch (SQLException exception) {
            log.warn("Readiness check failed", exception);
            return false;
        }
    }

    private int resolveWorkerThreads() {
        return appConfig.getWorkerThreads() > 0 ? appConfig.getWorkerThreads() : Runtime.getRuntime().availableProcessors() * 2;
    }

    private int resolveBusinessThreads() {
        return appConfig.getBusinessThreads() > 0 ? appConfig.getBusinessThreads() : Math.max(4, Runtime.getRuntime().availableProcessors() * 2);
    }
}
