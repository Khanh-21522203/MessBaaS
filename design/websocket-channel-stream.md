## WebSocket Channel Stream

### Purpose

Provide native WebSocket channel subscriptions, enforce channel membership at handshake/send boundaries, and support both message and lightweight presence/typing frame events.

### Scope

**In scope:**
- Handshake validation at `/ws/channels?channelId=...&clientUserId=...`.
- Per-channel socket registration/unregistration.
- Broadcast of `MessageEvent` payloads to subscribed sockets.
- Inbound text-frame envelope parsing (`message`, `typing`, `presence`) with validation and routing.

**Out of scope:**
- Token-based authentication/authorization.
- History replay over WebSocket.
- Reliable delivery acknowledgements/retry protocol.

### Primary User Flow

1. Client opens WebSocket connection to `/ws/channels?channelId={channelId}&clientUserId={clientUserId}`.
2. Handshake validates route, query params, and membership.
3. Connection is registered in in-memory channel group.
4. Server pushes `MessageEvent` JSON (with persisted message identity) when messages are sent to that channel.
5. Client may send:
- `type="message"` envelope to create a persisted chat message.
- `type="typing"` / `type="presence"` envelopes for ephemeral broadcast-only events.

### System Flow

1. `HttpApiHandler.isWebSocketUpgrade` detects `Upgrade: websocket` for `/ws/channels` and forwards request down pipeline.
2. `WebSocketHandshakeHandler.channelRead0` validates path, `channelId`, `clientUserId`, and membership via `ChannelMembershipService.assertMember`; it stores both IDs as channel attributes, rewrites URI to `/ws/channels`, and forwards.
3. `WebSocketServerProtocolHandler` completes Netty handshake.
4. `ChannelWebSocketFrameHandler.userEventTriggered` handles `HandshakeComplete` and calls `ChannelWebSocketRegistry.register(channelId, clientUserId, channel)`.
5. `ChannelWebSocketFrameHandler.channelRead0` handles:
- `PingWebSocketFrame` -> responds with `PongWebSocketFrame`.
- `CloseWebSocketFrame` -> closes channel.
- `TextWebSocketFrame` -> parse envelope:
- `message` -> validate and call `MessageService.sendMessage`.
- `typing`/`presence` -> validate and broadcast ephemeral event map through `ChannelWebSocketRegistry.broadcast(channelId, event)`.
6. `MessageServiceImpl.sendMessage` persists + appends hot cache + triggers broadcast; `ChannelWebSocketRegistry.broadcast` emits JSON `TextWebSocketFrame` to group.
7. On disconnect/error, `ChannelWebSocketFrameHandler` unregisters channel.

```
Client WS connect /ws/channels?channelId=ch-1&clientUserId=u-1
  -> WebSocketHandshakeHandler validates channelId/clientUserId + membership
  -> WebSocketServerProtocolHandler handshake
  -> ChannelWebSocketFrameHandler registers socket

Client text frame {"type":"message","payload":{clientUserId,clientMessageId,message,imgUrl}}
  -> ChannelWebSocketFrameHandler.parse envelope
  -> MessageServiceImpl.sendMessage
  -> ChannelWebSocketRegistry.broadcast(MessageEvent)
  -> all sockets for channelId receive TextWebSocketFrame(JSON)
```

### Data Model

- Inbound message payload (`SendMessageRequest`):
- `clientUserId (String)`
- `clientMessageId (String)`
- `message (String?)`
- `imgUrl (String?)`
- Outbound push payload (`MessageEvent`):
- `eventType (String)` default `message.created`
- `messageId (Long)`
- `clientUserId (String)`
- `clientMessageId (String)`
- `channelId (String)`
- `message (String)`
- `imgUrl (String?)`
- `createdAt (Instant)`
- Registry store (`ChannelWebSocketRegistry`): `ConcurrentMap<String, ChannelGroup>` keyed by `channelId`.

### Interfaces and Contracts

- WebSocket handshake route: `GET /ws/channels?channelId=<id>&clientUserId=<id>` with `Upgrade: websocket`.
- Handshake errors:
- `404` JSON `{"error":"WebSocket route not found"}` for wrong path.
- `400` JSON for missing `channelId` or `clientUserId`.
- `403` JSON when user is not a channel member.
- Inbound text frame contract:
- JSON object or envelope. If `type` is omitted, payload is treated as `message` event.
- Unsupported `type` is rejected with an error frame.
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

- Missing channel/user identity attributes on post-handshake socket: frame handler closes connection.
- Invalid JSON payload or missing required fields: handler sends error text frame and does not call service.
- `clientUserId` mismatch between handshake identity and frame payload returns an error frame.
- Service/runtime exception while handling text frame: warning log + generic error frame `Failed to process websocket message`.
- Serialization failure during error generation: fallback literal frame `{"error":"Failed to serialize websocket error"}`.
- `ChannelWebSocketRegistry.broadcast` logs serialization failures and drops that event payload without throwing upstream.

### Observability and Debugging

- `ChannelWebSocketRegistry` logs register/unregister events with `channelId` and remote address at INFO.
- `ChannelWebSocketFrameHandler` logs accepted inbound message at DEBUG and failures at WARN.
- Unit coverage in `src/test/java/com/java_mess/java_mess/websocket/ChannelWebSocketFrameHandlerTest.java` verifies valid-send dispatch and invalid-payload error frames.

### Risks and Notes

- Membership checks rely on `clientUserId` identity (no token verification yet).
- No delivery acknowledgements or replay, so disconnected clients miss events.
- Ephemeral `typing/presence` events are transient and not persisted.

Changes:

> Suggested [Impact: High] [Effort: L]: Decouple WebSocket fanout from HTTP/WS send critical path by consuming committed message events asynchronously, with retry/backoff and replay-safe deduplication so reconnect storms and transient broadcast failures do not increase send latency or lose committed events.
> Source: user request — design/update.md
> Approach: keep `src/main/java/com/java_mess/java_mess/websocket/ChannelWebSocketFrameHandler.java` focused on validation and message submission; move fanout trigger from synchronous direct broadcast in `src/main/java/com/java_mess/java_mess/service/MessageServiceImpl.java` to async projector/fanout worker consumption; maintain publish contract in `src/main/java/com/java_mess/java_mess/websocket/ChannelWebSocketRegistry.java` with idempotent event emission keys.
> Builds on: existing membership-gated websocket handshake and channel registry broadcast mechanics.
> Constraints: reliability-first operation; no message loss after successful MySQL commit; tolerate eventual fanout delay under backpressure.
> Edge cases: reconnect storms creating burst re-subscriptions, duplicate event deliveries during retry, worker backlog growth, channel with zero active subscribers.
> Risk: absent dedupe/replay boundaries can produce duplicate websocket events and client-side inconsistency.
