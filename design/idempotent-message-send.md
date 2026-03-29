## Idempotent Message Send and Reconnect-Safe Events

### Purpose

Prevent duplicate message writes during client retries and provide stable event identity for reconnect reconciliation.

### Scope

**In scope:**
- Add client-supplied idempotency key for message send.
- Enforce uniqueness per channel at persistence layer.
- Return canonical stored message for duplicate retries.
- Include persisted identity metadata in outbound WebSocket events.

**Out of scope:**
- Exactly-once delivery guarantees across distributed nodes.
- Global ordering beyond existing per-channel message ID ordering.

### Primary User Flow

1. Client sends message with `clientMessageId`.
2. If timeout/retry occurs, client resends the same payload/key.
3. Backend returns the same stored message instead of inserting duplicate rows.
4. WebSocket event includes `messageId` and `createdAt` for deterministic merge with history fetch.

### System Flow

1. HTTP and WS write paths continue to converge in `MessageServiceImpl.sendMessage`.
2. Service passes `clientMessageId` to repository save-or-load logic.
3. Repository uses unique key `(channelId, clientMessageId)` to dedupe retries.
4. Broadcast payload is built from persisted `Message`, not raw request fields.

### Data Model

- Add `clientMessageId VARCHAR(128) NOT NULL` to `message`.
- Add unique index `(channelId, clientMessageId)`.
- `MessageEvent` includes `messageId`, `createdAt`, and optional `eventType`.

### Interfaces and Contracts

- `POST /api/messages/{channelId}` and WS text payload add `clientMessageId`.
- Duplicate key reuse returns `200` with existing message payload.
- Event payload example:
- `{"eventType":"message.created","channelId":"...","messageId":123,"createdAt":"...","clientUserId":"...","message":"...","imgUrl":"..."}`

### Dependencies

**Internal modules:**
- `dto/message/SendMessageRequest`, `dto/message/MessageEvent`.
- `service/MessageServiceImpl`.
- `repository/MessageRepository`.

**External services/libraries:**
- Existing MySQL/JDBC/Flyway.

### Failure Modes and Edge Cases

- Missing `clientMessageId` returns `400`.
- Reused `clientMessageId` with mismatched payload returns deterministic conflict error (`409`) via `ClientMessageConflictException`.

### Observability and Debugging

- Add counters/log fields for dedupe hits vs fresh inserts.
- Include `clientMessageId` in warning logs for failed sends.

### Risks and Notes

- Requires schema migration and request contract update in both HTTP and WS clients.
- Keep first iteration scoped to per-channel dedupe to avoid over-abstracting idempotency storage.

Changes:

