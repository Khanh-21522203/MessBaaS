## Read Receipts and Unread State

### Purpose

Track authoritative read cursors in MySQL and expose unread counts through cache-first projection reads with DB reconciliation fallback.

### Scope

**In scope:**
- Read cursor updates (`lastReadMessageId` semantics).
- Unread count retrieval.
- Projection cache for read cursor + unread values.
- Projection-worker refresh after message events.

**Out of scope:**
- Push notifications.
- Device conflict policies beyond monotonic cursor updates.

### Primary User Flow

1. Client sends read cursor update.
2. Service upserts authoritative cursor in MySQL and updates read/unread cache.
3. Client requests unread count.
4. Service serves unread from cache when present, otherwise recomputes from MySQL and repairs cache.

### System Flow

1. `ApiRouter.updateReadCursor` validates payload and calls `ReadStateServiceImpl.updateReadCursor`.
2. `UserReadMessageRepository.upsertReadCursor` enforces monotonic/clamped cursor behavior.
3. `ReadStateServiceImpl` writes cached read cursor and unread count via `ProjectionCacheStore`.
4. `ApiRouter.getUnreadCount` calls `ReadStateServiceImpl.getUnreadCount`.
5. On message projection, `MessageProjectionProcessor` recalculates per-user unread and updates cache with message-ID version guard.
6. `ReadStateServiceImpl.getUnreadCount` compares cached cursor vs DB cursor and increments projection drift metrics when mismatched.

### Data Model

- Authoritative table: `userReadMessage(channelId, userId, lastReadMessageId, ...)`.
- Cache keys:
- `user:{userId}:reads` (`HSET channelId -> lastReadMessageId`)
- `user:{userId}:unread` (`HSET channelId -> unreadCount`)

### Interfaces and Contracts

- `PUT /api/channels/{channelId}/read-cursor`
- Body: `{"clientUserId": "...", "lastReadMessageId": <number>}`
- `GET /api/channels/{channelId}/unread-count?clientUserId=...`
- Response includes `lastReadMessageId` and `unreadCount`.

### Dependencies

**Internal modules:**
- `service/ReadStateServiceImpl`
- `repository/UserReadMessageRepository`
- `service/ProjectionCacheStore`
- `service/MessageProjectionProcessor`

**External services/libraries:**
- MySQL (authoritative counts/cursors).
- Redis (optional projection cache).

### Failure Modes and Edge Cases

- Negative cursor => `400`.
- Unknown user/channel => `404`.
- Cache miss/stale => DB recompute + cache repair.
- Redis unavailable => service remains DB-backed.

### Observability and Debugging

- Read-state failures surface through API router warnings.
- Projection cache health and fallback behavior visible in ops stats.
- Drift and unread-repair counters are exposed through projection cache runtime stats.

### Risks and Notes

- Unread view is eventually consistent between message commit and projection update.
- MySQL remains the source of truth for reconciliation.

Changes:
