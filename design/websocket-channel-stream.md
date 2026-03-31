## WebSocket Channel Stream

### Purpose

Handle channel-scoped WebSocket connectivity and event delivery with membership-gated access, while using asynchronous projection-driven fanout for persisted message events.

### Scope

**In scope:**
- Handshake validation for `/ws/channels?channelId=...&clientUserId=...`.
- Socket registration/unregistration by channel.
- Inbound envelope handling (`message`, `typing`, `presence`).
- Outbound broadcast of persisted message events from async projector.

**Out of scope:**
- Exactly-once delivery guarantees.
- Replay/history synchronization protocol.
- Token authentication.

### Primary User Flow

1. Client connects with channel/user query params.
2. Handshake verifies membership.
3. Client sends `message` envelope; server persists via HTTP-equivalent service.
4. Async projector later broadcasts persisted `message.created` events.
5. `typing`/`presence` events remain direct broadcast-only ephemeral events.

### System Flow

1. `WebSocketHandshakeHandler` validates path/query and membership.
2. `ChannelWebSocketFrameHandler`:
- routes `type=message` to `MessageService.sendMessage`,
- routes `typing/presence` to `ChannelWebSocketRegistry.broadcast`.
3. `MessageService.sendMessage` persists message + outbox and returns quickly.
4. `AsyncProjectionWorker` processes outbox row, and `MessageProjectionProcessor` emits broadcast via `ChannelWebSocketRegistry`.

### Data Model

- Outbound persisted event payload: `MessageEvent(eventType, messageId, clientUserId, clientMessageId, channelId, message, imgUrl, createdAt)`.
- Registry state: per-channel `ChannelGroup` and runtime counters.

### Interfaces and Contracts

- Handshake: `GET /ws/channels?channelId=<id>&clientUserId=<id>`.
- Invalid handshake returns HTTP `400/403/404`.
- Inbound unsupported event type returns websocket error frame.
- Persisted message events are at-least-once via async worker semantics (client dedupe by `messageId` is expected).

### Dependencies

**Internal modules:**
- `websocket/WebSocketHandshakeHandler`
- `websocket/ChannelWebSocketFrameHandler`
- `websocket/ChannelWebSocketRegistry`
- `service/MessageServiceImpl`
- `service/AsyncProjectionWorker` + `MessageProjectionProcessor`

**External services/libraries:**
- Netty WebSocket stack.

### Failure Modes and Edge Cases

- Reconnect storms increase active connection churn and broadcast load.
- Projection retry may cause duplicate message events if prior delivery partially succeeded.
- Serialization failure drops outbound event and increments failure counter.

### Observability and Debugging

- Registry stats expose active connections/channels and broadcast attempt/failure counters.
- Frame handler logs payload/processing failures at WARN.
- Projection worker retry/dead-letter metrics are exposed in runtime stats.

### Risks and Notes

- Message fanout is no longer synchronous with send response; short visibility lag is expected under backlog.

Changes:

