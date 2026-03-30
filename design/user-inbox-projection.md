## User Inbox Projection

### Purpose

Provide a denormalized, user-centric inbox read model so conversation list queries are served from Redis in sorted order with low latency, while MySQL remains authoritative.

### Scope

**In scope:**
- Maintain Redis inbox projection per user keyed by latest channel activity.
- Store compact last-message preview and unread metadata per channel entry.
- Serve inbox list reads from projection with MySQL/degraded fallback behavior.
- Define reconciliation behavior to repair projection drift.

**Out of scope:**
- Push notification delivery.
- Rich full-text search over message content.
- Cross-tenant/global inbox federation.

### Primary User Flow

1. User sends or receives new channel activity.
2. Projection worker updates the user's inbox entry for that channel in Redis.
3. Client requests inbox list and receives pre-sorted channels with unread counts.
4. If projection is stale/unavailable, service falls back to MySQL-derived response path.

### System Flow

1. Entry point: projection event consumed from async pipeline after message commit.
2. New inbox projection service updates `user:{userId}:inbox` with latest activity score and compact payload.
3. HTTP read route in `src/main/java/com/java_mess/java_mess/http/ApiRouter.java` resolves inbox view for `clientUserId`.
4. Read service checks Redis first, then falls back to MySQL when cache miss/stale/degraded state is detected.
5. Reconciliation worker periodically compares projected entries vs authoritative MySQL state and repairs mismatches.

```
Committed message event
  -> Async projection worker
      -> update Redis user inbox ZSET + payload

GET /api/inbox?clientUserId=...
  -> inbox service: Redis first
      -> [hit] return sorted inbox
      -> [miss/stale] derive from MySQL and optionally backfill cache
```

### Data Model

- Redis key: `user:{userId}:inbox` as `ZSET`.
- Score: monotonic latest activity signal (`messageId` or event timestamp with deterministic tie-breaker).
- Member payload (compact serialized object/string):
- `channelId`, `lastMessageId`, `lastSenderId`, `lastPreview`, `unreadCount`, `updatedAt`.
- Optional companion hash per user for richer metadata if payload size grows.
- Authoritative sources: MySQL `message`, `channelMember`, `userReadMessage`.

### Interfaces and Contracts

- Proposed read endpoint: `GET /api/inbox?clientUserId=<id>&limit=<n>&cursor=<opaque|optional>`.
- Success payload: ordered conversation summaries with unread count and last message preview.
- Error contract: `400` invalid query, `404` unknown user, `500` when both Redis and MySQL fallback fail.
- Consistency contract: inbox is eventually consistent; send-path success does not require projection completion.

### Dependencies

**Internal modules:**
- `http/ApiRouter` - route wiring and response mapping.
- `service/ReadStateServiceImpl` - unread semantics alignment.
- `service/ChannelMembershipServiceImpl` - channel visibility constraints.
- `repository/MessageRepository` and `repository/UserReadMessageRepository` - fallback/reconciliation sources.
- Async projection pipeline feature - event ingestion and retries.

**External services/libraries:**
- Redis - primary projection store for inbox reads.
- MySQL - authoritative fallback and reconciliation source.

### Failure Modes and Edge Cases

- Duplicate projection events: latest-write-wins semantics keeps inbox stable.
- Projector lag spikes: inbox temporarily stale; fallback path remains correct.
- Cache drift from partial failures: reconciliation repairs derived fields.
- Redis eviction pressure drops user inbox keys: service falls back to MySQL and rehydrates cache opportunistically.
- Reconnect storms create rapid update churn: coalesce updates by channel/user and preserve ordering determinism.

### Observability and Debugging

- Track metrics: `inbox.read.redisHit`, `inbox.read.dbFallback`, `inbox.projectionLagMs`, `inbox.driftRepairCount`.
- Record mismatch telemetry during reconciliation (expected vs projected unread/lastMessageId).
- Add sampled logs for fallback reasons (cache miss, stale version, redis error) to aid incident triage.

### Risks and Notes

- Overly large payloads per inbox entry can increase Redis memory and CPU costs.
- Inconsistent score calculation can cause unstable ordering across clients.
- Reconciliation cadence must balance correctness against DB load.

Changes:

> Suggested [Impact: High] [Effort: L]: Implement a Redis-backed user inbox projection and read API that serves sorted conversation summaries with unread counts from memory-first paths, with MySQL fallback/reconciliation to preserve reliability.
> Source: user request — design/update.md
> Approach: add inbox projection service + DTOs and route wiring in `ApiRouter`; update async projection worker handlers to maintain `user:{userId}:inbox`; add fallback query path using existing repositories; include reconciliation job and drift metrics in ops stats.
> Builds on: existing message persistence, channel membership model, read-cursor/unread semantics, and async projection pipeline.
> Constraints: MySQL remains source of truth; performance-sensitive inbox reads; reliability-first degraded mode when Redis is unavailable.
> Edge cases: duplicate events, projection lag, cache drift, reconnect storms, Redis eviction pressure.
> Risk: if ordering or unread semantics diverge between projection and fallback, clients will see inconsistent inbox state.
