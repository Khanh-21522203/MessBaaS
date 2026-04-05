## User Inbox Projection

### Purpose

Serve user conversation lists from a denormalized projection cache with MySQL fallback, reducing latency for inbox reads.

### Scope

**In scope:**
- Inbox API route: `GET /api/inbox?clientUserId=...&limit=...`.
- Projection cache entries per user/channel.
- Redis-backed inbox ordering/value storage with local fallback.
- DB fallback path to rebuild inbox entries on cache miss.

**Out of scope:**
- Cursor-based inbox pagination (current API is limit-based).
- Push notifications.
- Search/filter capabilities.

### Primary User Flow

1. Message is projected asynchronously and updates each member's inbox entry.
2. Client requests `/api/inbox`.
3. Service returns cached conversations when available.
4. On miss, service derives entries from MySQL and backfills cache.

### System Flow

1. `ApiRouter.listInbox` validates `clientUserId` + `limit` and calls `InboxServiceImpl.listInbox`.
2. `InboxServiceImpl` resolves user and attempts `ProjectionCacheStore.listInboxEntries`.
3. If cache miss:
- load channel memberships,
- fetch latest message per channel,
- compute unread counts from read cursor/message tables,
- store rebuilt entries in projection cache.
4. `MessageProjectionProcessor` also updates inbox entries during normal outbox processing.
5. Projection writes use monotonic guards to avoid older retry events regressing inbox order or unread values.

### Data Model

- API item model: `InboxEntry(channelId, lastMessageId, lastSenderClientUserId, lastPreview, unreadCount, updatedAt)`.
- Redis keys (when enabled):
- `user:{userId}:inbox:order` (`ZSET` score=`lastMessageId`, member=`channelId`)
- `user:{userId}:inbox:value` (`HASH` field=`channelId`, value=serialized `InboxEntry`)
- `user:{userId}:unread:version` (`HASH` field=`channelId`, value=`sourceMessageId`) for replay-safe unread updates.
- Node-local projection maps can be disabled via `cache.localProjection.enabled`.

### Interfaces and Contracts

- `GET /api/inbox?clientUserId=<id>&limit=<n>`
- Success: `200 {"conversations":[...]}`.
- Errors: `400` invalid query, `404` unknown user.
- Limit is bounded by `inbox.maxLimit`.

### Dependencies

**Internal modules:**
- `service/InboxServiceImpl`
- `service/ProjectionCacheStore`
- `repository/ChannelMemberRepository`, `MessageRepository`, `UserReadMessageRepository`, `UserRepository`
- `http/ApiRouter`

**External services/libraries:**
- Redis (optional).
- MySQL authoritative fallback source.

### Failure Modes and Edge Cases

- Redis unavailable => local/DB fallback path used.
- Projection lag => inbox can temporarily trail newest message until worker catches up.
- Empty cache for active user => DB rebuild path populates projection.

### Observability and Debugging

- `InboxRuntimeStats`: cache hits, DB fallbacks, latency snapshot.
- `InboxRuntimeStats.cacheHitRatio` exposes projection effectiveness.
- Combined with projection/cache stats from `/api/ops/stats`.

### Risks and Notes

- Current fallback strategy can perform multiple DB lookups per channel membership.
- Inbox consistency is eventual between commit and projection completion.

Changes:
