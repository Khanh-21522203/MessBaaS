## WebSocket Channel Stream

### Purpose

Provide native WebSocket channel subscriptions and allow clients to submit message-send payloads over text frames.

### Scope

**In scope:**
- Handshake validation at `/ws/channels?channelId=...`.
- Per-channel socket registration/unregistration.
- Broadcast of `MessageEvent` payloads to subscribed sockets.
- Inbound text-frame message parsing/validation and handoff to `MessageService.sendMessage`.

**Out of scope:**
- Authentication and channel access control.
- History replay over WebSocket.
- Reliable delivery acknowledgements/retry protocol.

### Primary User Flow

1. Client opens WebSocket connection to `/ws/channels?channelId={channelId}`.
2. Handshake validates route + `channelId` query parameter.
3. Connection is registered in in-memory channel group.
4. Server pushes `MessageEvent` JSON when messages are sent to that channel.
5. Client may send text JSON (`clientUserId`, `message`, `imgUrl`) to create a message through the same service path used by HTTP.

### System Flow

1. `HttpApiHandler.isWebSocketUpgrade` detects `Upgrade: websocket` for `/ws/channels` and forwards request down pipeline.
2. `WebSocketHandshakeHandler.channelRead0` validates path/`channelId`, stores `channelId` in `ChannelWebSocketRegistry.CHANNEL_ID_ATTRIBUTE`, rewrites URI to `/ws/channels`, and forwards.
3. `WebSocketServerProtocolHandler` completes Netty handshake.
4. `ChannelWebSocketFrameHandler.userEventTriggered` handles `HandshakeComplete` and calls `ChannelWebSocketRegistry.register(channelId, channel)`.
5. `ChannelWebSocketFrameHandler.channelRead0` handles:
- `PingWebSocketFrame` -> responds with `PongWebSocketFrame`.
- `CloseWebSocketFrame` -> closes channel.
- `TextWebSocketFrame` -> parse + validate payload, call `MessageService.sendMessage`.
6. `MessageServiceImpl.sendMessage` persists + appends hot cache + triggers broadcast; `ChannelWebSocketRegistry.broadcast` emits JSON `TextWebSocketFrame` to group.
7. On disconnect/error, `ChannelWebSocketFrameHandler` unregisters channel.

```
Client WS connect /ws/channels?channelId=ch-1
  -> WebSocketHandshakeHandler validates channelId
  -> WebSocketServerProtocolHandler handshake
  -> ChannelWebSocketFrameHandler registers socket

Client text frame {clientUserId,message,imgUrl}
  -> ChannelWebSocketFrameHandler.parseRequest
  -> MessageServiceImpl.sendMessage
  -> ChannelWebSocketRegistry.broadcast(MessageEvent)
  -> all sockets for channelId receive TextWebSocketFrame(JSON)
```

### Data Model

- Inbound text payload (`SendMessageRequest`):
- `clientUserId (String)`
- `message (String)`
- `imgUrl (String)`
- Outbound push payload (`MessageEvent`):
- `clientUserId (String)`
- `channelId (String)`
- `message (String)`
- `imgUrl (String)`
- Registry store (`ChannelWebSocketRegistry`): `ConcurrentMap<String, ChannelGroup>` keyed by `channelId`.

### Interfaces and Contracts

- WebSocket handshake route: `GET /ws/channels?channelId=<id>` with `Upgrade: websocket`.
- Handshake errors:
- `404` JSON `{"error":"WebSocket route not found"}` for wrong path.
- `400` JSON `{"error":"channelId query param is required"}` if missing/blank.
- Inbound text frame contract:
- JSON object with `clientUserId`, `message`, `imgUrl`.
- Invalid payload returns WebSocket text frame `{"error":"..."}` and keeps connection open.
- Outbound event contract:
- JSON-serialized `MessageEvent` broadcast to all channels registered under the same `channelId`.

### Dependencies

**Internal modules:**
- `http/HttpApiHandler` (upgrade pass-through).
- `websocket/WebSocketHandshakeHandler`, `websocket/ChannelWebSocketFrameHandler`, `websocket/ChannelWebSocketRegistry`.
- `service/MessageService` for inbound message writes.
- `http/RequestValidator` for field validation.

**External services/libraries:**
- Netty WebSocket codec and channel groups.
- Jackson `ObjectMapper` for frame JSON serialization.

### Failure Modes and Edge Cases

- Missing channel attribute on post-handshake socket: frame handler closes connection.
- Invalid JSON payload or missing required fields: handler sends error text frame and does not call service.
- Service/runtime exception while handling text frame: warning log + generic error frame `Failed to process websocket message`.
- Serialization failure during error generation: fallback literal frame `{"error":"Failed to serialize websocket error"}`.
- `ChannelWebSocketRegistry.broadcast` silently no-ops if no subscribers exist for channel.

### Observability and Debugging

- `ChannelWebSocketRegistry` logs register/unregister events with `channelId` and remote address at INFO.
- `ChannelWebSocketFrameHandler` logs accepted inbound message at DEBUG and failures at WARN.
- Unit coverage in `src/test/java/com/java_mess/java_mess/websocket/ChannelWebSocketFrameHandlerTest.java` verifies valid-send dispatch and invalid-payload error frames.

### Risks and Notes

- No authentication/authorization on WebSocket route.
- No delivery acknowledgements or replay, so disconnected clients miss events.
- Broadcast serialization errors raise `IllegalStateException` and can fail the send path.

Changes:

