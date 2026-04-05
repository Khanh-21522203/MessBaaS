## Distributed JSON-Tree Read Model

### Purpose

Define a Firebase-like denormalized JSON-tree projection layer for low-latency chat reads, while preserving SQL as authoritative durable storage.

### Scope

**In scope:**
- Canonical JSON-tree key model for channel recent history, membership, inbox, unread, and read cursors.
- Projection update flow from durable SQL outbox events.
- Multi-node deployment behavior (shared cache + optional node-local cache).
- Rebuild and reconciliation rules when cache state is stale/missing.

**Out of scope:**
- Replacing SQL as system of record.
- Full-text search indexing.
- Exact-once processing guarantees.

### Primary User Flow

1. Message write commits in SQL.
2. Async projector consumes outbox event.
3. JSON-tree projection nodes are updated in distributed cache.
4. Read APIs use JSON-tree projection first and only fall back to SQL on misses/partial windows.
5. Fallback reads backfill projection nodes.

### System Flow

1. `MessageRepository.save` commits message + outbox in one transaction.
2. `AsyncProjectionWorker` claims events and calls `MessageProjectionProcessor`.
3. Projector updates JSON-tree paths in `ProjectionCacheStore`:
- `channels/{channelId}/recent/{messageId}` (bounded window)
- `channels/{channelId}/members/{userId}`
- `users/{userId}/inbox/{channelId}`
- `users/{userId}/unread/{channelId}`
- `users/{userId}/reads/{channelId}`
4. `MessageServiceImpl` and `InboxServiceImpl` read projection first, fallback to SQL, then repair projection.

### Data Model

- Durable source: MySQL tables (`message`, `channelMember`, `userReadMessage`, outbox).
- Projection model in cache:
- `channel:{channelId}:messages:hot` (ordered recent window)
- `channel:{channelId}:members` (membership set)
- `user:{userId}:inbox:order`, `user:{userId}:inbox:value`
- `user:{userId}:unread`, `user:{userId}:reads`
- Projection ordering key: monotonic `messageId`.

### Interfaces and Contracts

- No external API contract break is required for first rollout.
- Internal contract: projection is best-effort and eventually consistent; SQL remains correctness fallback.

### Dependencies

**Internal modules:**
- `service/ProjectionCacheStore`
- `service/MessageProjectionProcessor`
- `service/MessageServiceImpl`
- `service/InboxServiceImpl`
- `service/ReadStateServiceImpl`

**External services/libraries:**
- Redis (distributed shared read model).
- MySQL (authoritative persistence).

### Failure Modes and Edge Cases

- Projector lag causes temporarily stale inbox/unread/read snapshots.
- Redis key eviction or node restart causes partial cache misses.
- Replayed events may apply more than once; updates must be idempotent by message identity.
- Out-of-order event processing must not regress read cursors or unread counts.

### Observability and Debugging

- Track projection lag (`commit -> visible in projection`) percentiles.
- Track cache hit ratio by API (`history`, `inbox`, `unread`).
- Track drift indicators (`SQL value != projection value`) and reconciliation repair counts.
- Track per-keyspace Redis error/fallback counters.

### Risks and Notes

- JSON-tree model improves read latency but introduces consistency lag under backlog.
- Multi-node correctness depends on treating node-local cache as non-authoritative and disposable.

Changes:
