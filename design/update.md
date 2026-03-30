# Low-Latency MessBaaS (Firebase-Inspired Data + Storage)

## Problem

Current MessBaaS already has a hot in-memory channel buffer, but the critical path still depends heavily on synchronous MySQL reads/writes and request-time composition. This can push p95/p99 latency up as load grows.

The goal is to keep the API simple while making the read path mostly precomputed and memory-first, similar to Firebase-style denormalized models.

## Goals

- Keep send latency low and predictable under burst load.
- Keep channel history and inbox reads mostly memory-backed.
- Avoid runtime joins for hot APIs.
- Preserve correctness (idempotency, ordering per channel, membership checks).

## Non-Goals

- Perfect global ordering across all channels.
- Full migration to Firebase products.
- Exactly-once delivery semantics over WebSocket.

## Design Principles

1. Source-of-truth log + denormalized read models.
2. Precompute on write, not compose on read.
3. Keep hot state in Redis/in-memory; fall back to MySQL.
4. Use small indexed records for hot queries.
5. Accept eventual consistency for secondary views (inbox/unread).

## Proposed Data Structures

### 1) Channel Message Log (authoritative)

- Storage: MySQL `messages` (append-only by channel).
- Key fields: `channel_id`, `message_id`, `sender_id`, `client_message_id`, `content`, `created_at`.
- Indexes:
- `(channel_id, message_id DESC)` for history windows.
- `(channel_id, client_message_id)` unique for idempotent sends.

### 2) Channel Hot Buffer (fast recent history)

- Storage: Redis `LIST` or `ZSET` per channel: `channel:{channelId}:messages:hot`.
- Retention: last `N` messages per channel (for example 200-1000, configurable).
- Purpose: serve most recent message reads without hitting MySQL.

### 3) Membership Cache (fast authz checks)

- Storage: Redis `SET` per channel: `channel:{channelId}:members`.
- Source: MySQL `channel_memberships`.
- Update policy: write-through on membership change + periodic reconciliation.

### 4) User Inbox Projection (Firebase-style denormalized read model)

- Storage: Redis `ZSET` per user: `user:{userId}:inbox`.
- Score: latest activity timestamp/message id.
- Value payload (compact): `channelId:lastMessageId:lastSenderId:lastPreview:unreadCount`.
- Purpose: inbox list is a single key read, no join.

### 5) Read Cursor / Unread Counters

- Authoritative: MySQL `user_read_message`.
- Hot cache: Redis hash `user:{userId}:reads` and `user:{userId}:unread`.
- Strategy: update unread counters asynchronously from new-message events.

### 6) Presence State

- Storage: Redis with TTL heartbeat, e.g. `presence:user:{userId}`.
- Purpose: low-cost online/offline status and fanout hints.

## Write Path (Send Message)

1. Validate request + idempotency key (`client_message_id`).
2. Check channel membership from Redis set (DB fallback on miss).
3. Persist message to MySQL (authoritative commit).
4. Append message to Redis hot buffer for channel.
5. Publish message event to internal bus (or Redis stream/pubsub).
6. Background workers update user inbox projections and unread counters.
7. Broadcast to WebSocket subscribers.

This keeps correctness anchored in MySQL while offloading expensive per-user projection work from the synchronous path.

## Read Paths

### Channel History

1. Read from channel hot buffer.
2. If not enough records, fill remainder from MySQL using `(channel_id, message_id)` index.
3. Merge and return in expected order.

### User Inbox

1. Read `user:{userId}:inbox` projection.
2. Fetch compact channel metadata cache (if needed).
3. Return already-sorted conversations with unread counts.

No runtime join across messages/channels/read-cursor tables for this endpoint.

## Latency Targets

- Send API p95: < 80ms, p99: < 150ms.
- Channel latest-history read p95: < 40ms.
- Inbox read p95: < 30ms.
- WebSocket fanout enqueue p95: < 20ms.

## Rollout Plan

### Phase 1: Read-Path Caching

- Introduce Redis-backed membership cache and larger channel hot buffer.
- Keep existing MySQL schema and service contracts.

### Phase 2: Inbox Projection

- Add background projector from message events to `user:{userId}:inbox`.
- Add unread counter cache + reconciliation job.

### Phase 3: Async Fanout Hardening

- Move expensive fanout/projection work fully off request thread.
- Add retry + dead-letter handling for projection failures.

### Phase 4: Partitioning and Scale

- Partition channels by hash for worker ownership.
- Prepare DB sharding strategy for very large channel/message volumes.

## Why This Is "Firebase-Like"

- Denormalized user-centric read models.
- Write triggers produce read-optimized views.
- Hot document/collection style access patterns for inbox and presence.

Difference: we keep MySQL as authoritative storage for stronger control, lower vendor lock-in, and compatibility with current MessBaaS code.

## Risks and Mitigations

- Cache/projection drift:
- Mitigation: periodic reconciliation jobs from MySQL truth.
- Projection lag under spikes:
- Mitigation: bounded queues, backpressure, and per-channel partition workers.
- Redis memory pressure:
- Mitigation: strict per-key limits, TTL policies, and payload compaction.

## Success Metrics

- p95/p99 latency for send/history/inbox.
- Cache hit ratio on channel-history reads.
- Projection lag (message commit -> inbox visible).
- Unread counter mismatch rate after reconciliation.
- WebSocket delivery delay percentile.

Changes:
