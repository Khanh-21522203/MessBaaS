package com.java_mess.java_mess.http;

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static io.netty.handler.codec.http.HttpResponseStatus.CONFLICT;
import static io.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.java_mess.java_mess.dto.channel.CreateChannelRequest;
import com.java_mess.java_mess.dto.channel.CreateChannelResponse;
import com.java_mess.java_mess.dto.channel.GetChannelResponse;
import com.java_mess.java_mess.dto.message.ListMessageRequest;
import com.java_mess.java_mess.dto.message.ListMessageResponse;
import com.java_mess.java_mess.dto.message.SendMessageRequest;
import com.java_mess.java_mess.dto.message.SendMessageResponse;
import com.java_mess.java_mess.dto.user.CreateUserRequest;
import com.java_mess.java_mess.dto.user.CreateUserResponse;
import com.java_mess.java_mess.dto.user.GetUserResponse;
import com.java_mess.java_mess.exception.ChannelExistedException;
import com.java_mess.java_mess.exception.ChannelNotFoundException;
import com.java_mess.java_mess.exception.ClientUserIdExistedException;
import com.java_mess.java_mess.exception.UserNotFoundException;
import com.java_mess.java_mess.model.Channel;
import com.java_mess.java_mess.model.Message;
import com.java_mess.java_mess.model.User;
import com.java_mess.java_mess.service.ChannelService;
import com.java_mess.java_mess.service.MessageService;
import com.java_mess.java_mess.service.UserService;

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
    private final ObjectMapper objectMapper;
    private final UserService userService;
    private final ChannelService channelService;
    private final MessageService messageService;

    public FullHttpResponse route(FullHttpRequest request) {
        QueryStringDecoder decoder = new QueryStringDecoder(request.uri());
        String path = normalizePath(decoder.path());
        HttpMethod method = request.method();

        try {
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
            if (HttpMethod.GET.equals(method) && isChannelByIdPath(path)) {
                return getChannelById(path);
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

    private FullHttpResponse sendMessage(String path, FullHttpRequest request) throws JsonProcessingException {
        String channelId = pathSegment(path, 2);
        SendMessageRequest payload = readBody(request, SendMessageRequest.class);
        RequestValidator.requireNonBlank(payload.getClientUserId(), "clientUserId");
        RequestValidator.requireNonBlank(payload.getMessage(), "message");
        RequestValidator.requireNonBlank(payload.getImgUrl(), "imgUrl");

        Message message = messageService.sendMessage(channelId, payload);
        return jsonResponse(OK, SendMessageResponse.builder().message(message).build());
    }

    private FullHttpResponse listMessages(String path, QueryStringDecoder decoder) throws JsonProcessingException {
        String channelId = pathSegment(path, 2);
        long pivotId = RequestValidator.requireLong(queryParam(decoder, "pivotId"), "pivotId");
        int prevLimit = RequestValidator.requireInt(queryParam(decoder, "prevLimit"), "prevLimit");
        int nextLimit = RequestValidator.requireInt(queryParam(decoder, "nextLimit"), "nextLimit");
        RequestValidator.requireNonNegative(prevLimit, "prevLimit");
        RequestValidator.requireNonNegative(nextLimit, "nextLimit");

        List<Message> messages = messageService.listMessages(ListMessageRequest.builder()
            .channelId(channelId)
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
        if (exception instanceof ClientUserIdExistedException || exception instanceof ChannelExistedException) {
            return CONFLICT;
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
}
