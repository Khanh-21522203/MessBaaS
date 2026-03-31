## Message HTTP API

### Purpose

Accept idempotent message writes and serve channel history windows, while keeping send latency MySQL-commit-first and moving fanout/projection work off the request path.

### Scope

**In scope:**
- `POST /api/messages/{channelId}` send route.
- `GET /api/messages/{channelId}` history route (`clientUserId`, `pivotId`, `prevLimit`, `nextLimit`).
- Membership-gated access control and request validation.
- Cache-first history reads with deterministic DB fallback.

**Out of scope:**
- Message edit/delete.
- Delivery acknowledgements over WebSocket.
- Push notification delivery.

### Primary User Flow

1. Client sends message payload with `clientMessageId`.
2. Service validates user/channel/membership and writes message to MySQL.
3. Message is returned immediately after authoritative persistence (no synchronous fanout/projection dependency).
4. Background worker projects message to hot cache, inbox/unread cache, and WebSocket fanout.
5. History reads return cache-first windows with DB gap fill.

### System Flow

1. `ApiRouter.sendMessage` validates payload and calls `MessageServiceImpl.sendMessage`.
2. `MessageServiceImpl.sendMessage` checks membership and calls `MessageRepository.save`.
3. `MessageRepository.save` writes message row and outbox row atomically in one transaction.
4. `AsyncProjectionWorker` claims outbox rows and executes `MessageProjectionProcessor`.
5. `ApiRouter.listMessages` calls `MessageServiceImpl.listMessages`, which uses:
- Redis hot window via `ProjectionCacheStore` (when available),
- in-process `ChannelMessageHotStore`,
- MySQL repository fallback.

```
POST /api/messages/{channelId}
  -> MessageServiceImpl.sendMessage
      -> MessageRepository.save (message + outbox, atomic)
      -> return 200

AsyncProjectionWorker
  -> MessageProjectionProcessor
      -> hot cache + unread/inbox projection + websocket broadcast
```

### Data Model

- Send DTO: `clientUserId`, `clientMessageId`, `message`, `imgUrl`.
- History DTO: `channelId`, `clientUserId`, `pivotId`, `prevLimit`, `nextLimit`.
- `message` table is authoritative.
- `messageOutbox` table stores post-commit projection tasks.

### Interfaces and Contracts

- `POST /api/messages/{channelId}`
- Success: persisted message payload.
- Errors: `400` invalid input, `403` membership denied, `404` user/channel not found, `409` idempotency conflict.
- `GET /api/messages/{channelId}?clientUserId=&pivotId=&prevLimit=&nextLimit=`
- Success: history window list.
- Query semantics preserved:
- `pivotId == 0` => latest mode (`prevLimit` only).
- `pivotId > 0` => before(desc) + after(asc) windows.

### Dependencies

**Internal modules:**
- `http/ApiRouter`, `service/MessageServiceImpl`, `repository/MessageRepository`.
- `service/ProjectionCacheStore`, `service/ChannelMessageHotStore`.
- `service/AsyncProjectionWorker` + outbox repository.

**External services/libraries:**
- MySQL/JDBC.
- Redis (optional acceleration path).

### Failure Modes and Edge Cases

- Duplicate `clientMessageId` with changed payload => `409`.
- Outbox backlog growth can delay projection visibility but does not invalidate committed send success.
- Redis unavailability yields cache misses and DB fallback.
- History window gaps are filled from MySQL preserving ordering contract.

### Observability and Debugging

- `MessageRuntimeStats` exposes:
- hot/db read-path counters,
- send/history latency p95/p99 snapshots,
- projection backlog/retry/dead-letter stats,
- projection cache health stats.
- Global request failures are logged by `ApiRouter.route`.

### Risks and Notes

- Because fanout/projection is asynchronous, users may briefly observe eventual consistency in inbox/unread/hot-cache views.
- Message durability remains anchored to MySQL commit success.

Changes:

