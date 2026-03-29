## Message HTTP API

### Purpose

Accept message writes for a channel and retrieve message history windows around a pivot ID.

### Scope

**In scope:**
- `POST /api/messages/{channelId}` for message creation.
- `GET /api/messages/{channelId}` with `clientUserId`, `pivotId`, `prevLimit`, `nextLimit` query parameters.
- Validation/error mapping for message HTTP requests.
- Read-path composition across hot buffer + database repository.

**Out of scope:**
- WebSocket handshake framing.
- Message edit/delete operations.
- Read-receipt persistence (`userReadMessage`) behavior.

### Primary User Flow

1. Client posts a message body (`clientUserId`, `message`, `imgUrl`) to a specific `channelId`.
2. Send payload requires `clientMessageId` and at least one content field (`message` or `imgUrl`).
3. Service validates user + channel + membership, persists/deduplicates message, and returns stored message JSON.
4. Client requests history windows with `clientUserId/pivotId/prevLimit/nextLimit`.
5. Service validates channel existence + membership and returns most-recent or before/after windows, using in-memory hot cache first then DB fallback.

### System Flow

1. Entry point: `HttpApiHandler.channelRead0` -> `ApiRouter.route`.
2. `ApiRouter.sendMessage` validates payload fields and calls `MessageServiceImpl.sendMessage`.
3. `MessageServiceImpl.sendMessage` checks user + channel + membership, inserts/loads message via `MessageRepository.save` using `(channelId, clientMessageId)`, appends to `ChannelMessageHotStore`, broadcasts enriched `MessageEvent`.
4. `ApiRouter.listMessages` requires `clientUserId` and parses query params with `RequestValidator.requireLong/requireInt/requireNonNegative`.
5. `MessageServiceImpl.listMessages` branches:
- `pivotId == 0`: latest messages (`hotStore.latest` first, then `MessageRepository.findLatestMessages` or `listMessagesBeforeId` to fill gaps).
- `pivotId > 0`: combine `before` and `after` windows using hot store first then DB (`listMessagesBeforeId`, `listMessagesAfterId`).
6. Router returns `SendMessageResponse` or `ListMessageResponse` JSON.

```
POST /api/messages/{channelId}
  -> ApiRouter.sendMessage
      -> MessageServiceImpl.sendMessage
          -> UserRepository.findByClientUserId
          -> ChannelRepository.findById
          -> MessageRepository.save
          -> ChannelMessageHotStore.append
          -> ChannelWebSocketRegistry.broadcast

GET /api/messages/{channelId}?pivotId=&prevLimit=&nextLimit=
  -> ApiRouter.listMessages
      -> MessageServiceImpl.listMessages
          -> hot store window lookup
          -> DB fallback when hot window is insufficient
```

### Data Model

- `SendMessageRequest`: `clientUserId (String)`, `clientMessageId (String)`, `message (String?)`, `imgUrl (String?)`.
- `ListMessageRequest`: `channelId (String)`, `clientUserId (String)`, `pivotId (long)`, `prevLimit (int)`, `nextLimit (int)`.
- `Message` model fields: `id (Long auto-increment)`, `channel (Channel)`, `user (User)`, `clientMessageId (String)`, `message (String)`, `imgUrl (String?)`, `isDeleted (Boolean)`, `createdAt (Instant)`, `updatedAt (Instant)`.
- Table `message` (`V1__init.sql` + `V2__message_contract.sql`):
- `id BIGINT AUTO_INCREMENT PRIMARY KEY`
- `channelId VARCHAR(36) NOT NULL` FK -> `channel(id)`
- `userId VARCHAR(36) NOT NULL` FK -> ``user``(id)
- `clientMessageId VARCHAR(128) NOT NULL` with unique `(channelId, clientMessageId)`
- `message TEXT NOT NULL`, `imgUrl VARCHAR(2000) NULL`
- `isDeleted TINYINT(1) NOT NULL`
- `createdAt`, `updatedAt`
- Index: `idx_message_channel_id_id(channelId, id)` for history scans.

### Interfaces and Contracts

- `POST /api/messages/{channelId}`
- Body: `{"clientUserId":string,"clientMessageId":string,"message"?:string,"imgUrl"?:string}`
- Success: `200` with `{"message":{...}}`
- Errors: `400` invalid payload, `403` membership denied, `404` user/channel missing, `409` conflicting `clientMessageId` payload reuse.
- `GET /api/messages/{channelId}?clientUserId=<id>&pivotId=<long>&prevLimit=<int>&nextLimit=<int>`
- Success: `200` with `{"messages":[...]}`
- Errors: `400` missing/invalid query params or negative limits, `403` membership denied, `404` channel missing.
- Query semantics:
- `pivotId == 0` uses latest-message mode and ignores `nextLimit`.
- `pivotId > 0` returns up to `prevLimit` older messages (descending IDs) plus up to `nextLimit` newer messages (ascending IDs).

### Dependencies

**Internal modules:**
- `http/ApiRouter`, `http/RequestValidator`.
- `service/MessageServiceImpl`, `service/ChannelMessageHotStore`.
- `repository/MessageRepository`, `repository/UserRepository`, `repository/ChannelRepository`.
- `websocket/ChannelWebSocketRegistry` for fanout on write.

**External services/libraries:**
- Jackson for JSON body serialization.
- MySQL via JDBC data source.

### Failure Modes and Edge Cases

- Blank `clientUserId` or `clientMessageId` -> `400`.
- Both `message` and `imgUrl` blank -> `400`.
- Max-length violations on `clientMessageId`, `message`, `imgUrl` -> `400`.
- `pivotId`, `prevLimit`, `nextLimit` parse errors -> `400`.
- Negative `prevLimit` or `nextLimit` -> `400`.
- Unknown user or channel when sending -> `404`.
- Unknown channel on list -> `404` (no silent empty-history fallback).
- Membership check failures on send/list -> `403`.
- `pivotId == 0` and `prevLimit <= 0` returns empty list.
- Repository failures become `IllegalStateException` and map to `500`.

### Observability and Debugging

- Global request failures logged in `ApiRouter.route` at WARN with path/method.
- Message send success is not logged in `MessageServiceImpl`; websocket handler logs only debug when message is accepted from WS path.
- No metrics for cache hit/miss or DB fallback frequency.

### Risks and Notes

- Response ordering for combined before/after windows is split by direction (older descending then newer ascending), not globally sorted.
- `imgUrl` is optional; at least one of text/image content is required.
- No pagination cursors besides numeric message ID pivot.

Changes:
