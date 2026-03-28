# MessBaaS — Design Index

MessBaaS is a single-node Java 21 chat backend that runs as a plain Netty server, exposes HTTP APIs and a native WebSocket endpoint, persists chat data through raw JDBC/MySQL, and keeps a bounded in-memory per-channel hot cache to speed recent-history reads.

## Mental Map

```
┌─ Runtime Bootstrap ─────────────────────────────┐  ┌─ HTTP API ──────────────────────────────────────┐
│ Owns: config load, DB pool/migrations, wiring   │  │ Owns: REST routes for users, channels, messages │
│ Entry: MessBaaSServer.java                      │  │ Entry: http/ApiRouter.java                      │
│ Key:   config/AppConfig.java                    │  │ Key:   http/HttpApiHandler.java                 │
│        server/NettyServer.java                  │  │        http/RequestValidator.java               │
└─────────────────────────────────────────────────┘  │ Uses:  JDBC Persistence, WebSocket Registry     │
                                                     └─────────────────────────────────────────────────┘

┌─ WebSocket Channel Stream ──────────────────────┐  ┌─ Channel Hot Buffer ────────────────────────────┐
│ Owns: WS handshake, socket registry, broadcast  │  │ Owns: bounded in-memory recent-message cache    │
│ Entry: websocket/WebSocketHandshakeHandler.java │  │ Entry: service/ChannelMessageHotStore.java      │
│ Key:   websocket/ChannelWebSocketRegistry.java  │  └─────────────────────────────────────────────────┘
│        websocket/ChannelWebSocketFrameHandler   │
│ Uses:  HTTP API (sendMessage path)              │  ┌─ JDBC Persistence ──────────────────────────────┐
└─────────────────────────────────────────────────┘  │ Owns: Flyway schema, JDBC repos for all tables  │
                                                     │ Key:   repository/UserRepository.java           │
                                                     │        repository/ChannelRepository.java        │
                                                     │        repository/MessageRepository.java        │
                                                     │        db/migration/V1__init.sql                │
                                                     └─────────────────────────────────────────────────┘
```

## Feature Matrix

| Feature | Description | File | Status |
|---------|-------------|------|--------|
| Runtime Bootstrap and Wiring | Startup path for config loading, DB pool/migrations, and Netty pipeline wiring | [runtime-bootstrap.md](runtime-bootstrap.md) | Stable |
| User HTTP API | Create and fetch users by client user ID | [user-http-api.md](user-http-api.md) | Stable |
| Channel HTTP API | Create channels and resolve by reference ID or channel ID | [channel-http-api.md](channel-http-api.md) | Stable |
| Message HTTP API | Send messages and query channel history windows via pivot pagination | [message-http-api.md](message-http-api.md) | Stable |
| WebSocket Channel Stream | Channel-scoped WS handshake, socket registry, broadcast, and inbound text send path | [websocket-channel-stream.md](websocket-channel-stream.md) | Stable |
| Channel Hot Buffer Cache | In-memory bounded recent-message cache used before DB fallback | [channel-hot-buffer.md](channel-hot-buffer.md) | Stable |
| JDBC Persistence and Schema | Flyway-managed MySQL schema and raw JDBC repositories | [jdbc-persistence.md](jdbc-persistence.md) | Stable |

## Cross-Cutting Concerns

Request validation is centralized in `src/main/java/com/java_mess/java_mess/http/RequestValidator.java` and applied consistently in both HTTP route handlers (`ApiRouter`) and inbound WebSocket text handling (`ChannelWebSocketFrameHandler`). Error mapping to transport status/payload happens at transport boundaries: HTTP maps known exception types to `400/404/409` in `ApiRouter.statusFor`, while WebSocket emits JSON error frames from `ChannelWebSocketFrameHandler.writeError`. Message fanout is synchronous with writes: `MessageServiceImpl.sendMessage` persists to MySQL, appends to `ChannelMessageHotStore`, then publishes a `MessageEvent` through `ChannelWebSocketRegistry.broadcast`. Observability is log-only (SLF4J); there are no metrics, tracing IDs, or health endpoints.

## Notes

