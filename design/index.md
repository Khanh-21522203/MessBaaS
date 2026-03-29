# MessBaaS — Design Index

MessBaaS is a single-node Java 21 chat backend that runs as a plain Netty server, exposes HTTP/WebSocket surfaces, persists chat data through raw JDBC/MySQL, enforces channel membership, supports idempotent sends, tracks read cursors/unread counts, and keeps a bounded per-channel hot cache for recent-history reads.

## Mental Map

```
┌─ Runtime Bootstrap ───────────────────────────────────────────────────────────────────────┐
│ Owns: process startup, config loading, DB pool + Flyway migration, Netty pipeline wiring │
│ Entry: src/main/java/com/java_mess/java_mess/MessBaaSServer.java                         │
│ Key:   src/main/java/com/java_mess/java_mess/server/NettyServer.java                     │
│ Uses:  JDBC Persistence and Schema, User HTTP API, Channel HTTP API, Message HTTP API, Channel Membership and Access Control, Read Receipts and Unread State, Operational Health and Runtime Stats │
└────────────────────────────────────────────────────────────────────────────────────────────┘

┌─ User HTTP API ────────────────────────────────────────────────────────────────────────────┐
│ Owns: POST/GET user routes keyed by clientUserId                                           │
│ Entry: src/main/java/com/java_mess/java_mess/http/ApiRouter.java                          │
│ Key:   src/main/java/com/java_mess/java_mess/service/UserServiceImpl.java                 │
│ Uses:  JDBC Persistence and Schema                                                          │
└────────────────────────────────────────────────────────────────────────────────────────────┘

┌─ Channel HTTP API ─────────────────────────────────────────────────────────────────────────┐
│ Owns: channel create, list/discovery, and read-by-reference/read-by-id routes              │
│ Entry: src/main/java/com/java_mess/java_mess/http/ApiRouter.java                          │
│ Key:   src/main/java/com/java_mess/java_mess/service/ChannelServiceImpl.java              │
│ Uses:  JDBC Persistence and Schema                                                          │
└────────────────────────────────────────────────────────────────────────────────────────────┘

┌─ Message HTTP API ─────────────────────────────────────────────────────────────────────────┐
│ Owns: idempotent message create route and membership-gated pivot-window history route      │
│ Entry: src/main/java/com/java_mess/java_mess/http/ApiRouter.java                          │
│ Key:   src/main/java/com/java_mess/java_mess/service/MessageServiceImpl.java              │
│ Uses:  User HTTP API, Channel HTTP API, Channel Membership and Access Control, Channel Hot Buffer Cache, JDBC Persistence and Schema, WebSocket Channel Stream │
└────────────────────────────────────────────────────────────────────────────────────────────┘

┌─ WebSocket Channel Stream ─────────────────────────────────────────────────────────────────┐
│ Owns: membership-gated handshake, channel registration, inbound envelope events, broadcast │
│ Entry: src/main/java/com/java_mess/java_mess/websocket/WebSocketHandshakeHandler.java     │
│ Key:   src/main/java/com/java_mess/java_mess/websocket/ChannelWebSocketFrameHandler.java  │
│ Uses:  Message HTTP API, Channel Membership and Access Control                              │
└────────────────────────────────────────────────────────────────────────────────────────────┘

┌─ Channel Membership and Access Control ────────────────────────────────────────────────────┐
│ Owns: member add/remove/list and membership assertion for message/ws flows                 │
│ Entry: src/main/java/com/java_mess/java_mess/http/ApiRouter.java                          │
│ Key:   src/main/java/com/java_mess/java_mess/service/ChannelMembershipServiceImpl.java    │
│ Uses:  JDBC Persistence and Schema, User HTTP API, Channel HTTP API                        │
└────────────────────────────────────────────────────────────────────────────────────────────┘

┌─ Read Receipts and Unread State ───────────────────────────────────────────────────────────┐
│ Owns: per-user read cursor writes and unread-count queries                                 │
│ Entry: src/main/java/com/java_mess/java_mess/http/ApiRouter.java                          │
│ Key:   src/main/java/com/java_mess/java_mess/service/ReadStateServiceImpl.java            │
│ Uses:  JDBC Persistence and Schema, User HTTP API, Channel HTTP API                        │
└────────────────────────────────────────────────────────────────────────────────────────────┘

┌─ Operational Health and Runtime Stats ─────────────────────────────────────────────────────┐
│ Owns: liveness/readiness probes and in-process runtime metric snapshots                    │
│ Entry: src/main/java/com/java_mess/java_mess/http/ApiRouter.java                          │
│ Key:   src/main/java/com/java_mess/java_mess/service/MessageServiceImpl.java              │
│ Uses:  Runtime Bootstrap and Wiring, Channel Hot Buffer Cache, WebSocket Channel Stream    │
└────────────────────────────────────────────────────────────────────────────────────────────┘

┌─ Channel Hot Buffer Cache ─────────────────────────────────────────────────────────────────┐
│ Owns: bounded in-memory per-channel message window cache                                   │
│ Entry: src/main/java/com/java_mess/java_mess/service/ChannelMessageHotStore.java          │
│ Uses:  Message HTTP API                                                                     │
└────────────────────────────────────────────────────────────────────────────────────────────┘

┌─ JDBC Persistence and Schema ──────────────────────────────────────────────────────────────┐
│ Owns: Flyway schema and synchronous JDBC repositories for user/channel/message tables      │
│ Key:   src/main/java/com/java_mess/java_mess/repository/UserRepository.java               │
│        src/main/java/com/java_mess/java_mess/repository/ChannelRepository.java            │
│        src/main/java/com/java_mess/java_mess/repository/MessageRepository.java            │
└────────────────────────────────────────────────────────────────────────────────────────────┘

┌─ Shared ───────────────────────────────────────────────────────────────────────────────────┐
│ Owns: request validation helpers and runtime/dev bootstrap metadata                        │
│ Key:   src/main/java/com/java_mess/java_mess/http/RequestValidator.java                   │
│        build.gradle, settings.gradle, docker-compose.yaml, docker/mysql/init.sql          │
└────────────────────────────────────────────────────────────────────────────────────────────┘
```

## Feature Matrix

| Feature | Description | File | Status |
|---------|-------------|------|--------|
| Runtime Bootstrap and Wiring | Startup path for config loading, DB pool/migrations, Netty pipeline wiring, and local run bootstrap contract | [runtime-bootstrap.md](runtime-bootstrap.md) | Stable |
| User HTTP API | Create and fetch users by client user ID | [user-http-api.md](user-http-api.md) | Stable |
| Channel HTTP API | Create channels and resolve by reference ID or channel ID | [channel-http-api.md](channel-http-api.md) | Stable |
| Message HTTP API | Send idempotent channel messages and query membership-gated history windows via pivot pagination | [message-http-api.md](message-http-api.md) | Stable |
| Idempotent Message Send and Reconnect-Safe Events | Enforce per-channel client message idempotency and emit persisted message identity in WS events | [idempotent-message-send.md](idempotent-message-send.md) | Stable |
| WebSocket Channel Stream | Membership-gated WS handshake, socket registry, message/presence envelope handling, and broadcast | [websocket-channel-stream.md](websocket-channel-stream.md) | Stable |
| Channel Hot Buffer Cache | In-memory bounded recent-message cache used before DB fallback | [channel-hot-buffer.md](channel-hot-buffer.md) | Stable |
| JDBC Persistence and Schema | Flyway-managed MySQL schema and raw JDBC repositories | [jdbc-persistence.md](jdbc-persistence.md) | Stable |
| Read Receipts and Unread State | Persist per-user read cursor and compute unread counts from message-ID cursor semantics | [read-receipts-unread-state.md](read-receipts-unread-state.md) | Stable |
| Channel Membership and Access Control | Persist channel participants and gate message send/read/ws subscription by membership | [channel-membership.md](channel-membership.md) | Stable |
| Operational Health and Runtime Stats | Liveness/readiness probes and runtime counters for message cache/websocket state | [operational-observability.md](operational-observability.md) | Stable |

## Cross-Cutting Concerns

Request validation is centralized in `src/main/java/com/java_mess/java_mess/http/RequestValidator.java` and applied in HTTP handlers and inbound WebSocket frame handling. Error mapping to transport status/payload happens at transport boundaries: HTTP maps known exception types to `400/403/404/409` in `ApiRouter.statusFor`, while WebSocket emits JSON error frames from `ChannelWebSocketFrameHandler.writeError`. Message fanout remains synchronous with writes: `MessageServiceImpl.sendMessage` persists/deduplicates in MySQL, appends to `ChannelMessageHotStore`, then publishes `MessageEvent` through `ChannelWebSocketRegistry.broadcast`. Operational observability is now built-in through `GET /health/live`, `GET /health/ready`, `GET /healthz`, `GET /readyz`, and `GET /api/ops/stats`.

## Notes

- Re-create pass 2 ownership audit (explicit file-to-feature claims):
- Runtime Bootstrap and Wiring: `src/main/java/com/java_mess/java_mess/config/AppConfigLoader.java`.
- User HTTP API: `src/main/java/com/java_mess/java_mess/dto/user/CreateUserRequest.java`, `src/main/java/com/java_mess/java_mess/dto/user/CreateUserResponse.java`, `src/main/java/com/java_mess/java_mess/dto/user/GetUserResponse.java`, `src/main/java/com/java_mess/java_mess/service/UserService.java`, `src/main/java/com/java_mess/java_mess/model/User.java`, `src/main/java/com/java_mess/java_mess/exception/ClientUserIdExistedException.java`, `src/main/java/com/java_mess/java_mess/exception/UserNotFoundException.java`.
- Channel HTTP API: `src/main/java/com/java_mess/java_mess/dto/channel/CreateChannelRequest.java`, `src/main/java/com/java_mess/java_mess/dto/channel/CreateChannelResponse.java`, `src/main/java/com/java_mess/java_mess/dto/channel/GetChannelResponse.java`, `src/main/java/com/java_mess/java_mess/service/ChannelService.java`, `src/main/java/com/java_mess/java_mess/model/Channel.java`, `src/main/java/com/java_mess/java_mess/exception/ChannelExistedException.java`, `src/main/java/com/java_mess/java_mess/exception/ChannelNotFoundException.java`.
- Message HTTP API: `src/main/java/com/java_mess/java_mess/dto/message/ListMessageRequest.java`, `src/main/java/com/java_mess/java_mess/dto/message/ListMessageResponse.java`, `src/main/java/com/java_mess/java_mess/dto/message/SendMessageRequest.java`, `src/main/java/com/java_mess/java_mess/dto/message/SendMessageResponse.java`, `src/main/java/com/java_mess/java_mess/dto/message/MessageEvent.java`, `src/main/java/com/java_mess/java_mess/service/MessageService.java`, `src/main/java/com/java_mess/java_mess/model/Message.java`.
- JDBC Persistence and Schema: `src/main/java/com/java_mess/java_mess/model/UserReadMessage.java`.
- WebSocket Channel Stream: `src/main/java/com/java_mess/java_mess/websocket/ChannelWebSocketRegistry.java`.
- Shared request-validation concern: `src/test/java/com/java_mess/java_mess/http/RequestValidatorTest.java`.
