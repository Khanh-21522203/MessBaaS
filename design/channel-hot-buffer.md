## Channel Hot Buffer Cache

### Purpose

Serve recent channel history with low latency by combining Redis hot-window storage (when available), in-process fallback buffering, and fallback-driven hot-window repair from MySQL reads.

### Scope

**In scope:**
- Per-channel bounded message hot-window projection.
- Redis list projection key: `channel:{channelId}:messages:hot`.
- In-process fallback hot buffer (`ChannelMessageHotStore`).
- Deterministic `latest/before/after` read behavior.

**Out of scope:**
- Durable storage (handled by MySQL).
- Full cache warmup at startup.
- Cross-region cache replication.

### Primary User Flow

1. Message is committed to MySQL.
2. Async projector appends message to local hot store and Redis hot list.
3. History API reads Redis hot window first.
4. If Redis miss/partial, service falls back to local hot store, then MySQL.

### System Flow

1. `MessageProjectionProcessor.process` appends to `ChannelMessageHotStore`.
2. Same projection pass writes serialized message to Redis list via `ProjectionCacheStore.appendHotMessage` and trims by `hotBufferPerChannel`.
3. `MessageServiceImpl.latest/messagesBefore/messagesAfter` attempts Redis hot reads (`ProjectionCacheStore`), then local hot-store reads, then DB.
4. When DB fallback is used, `MessageServiceImpl` backfills the in-process hot store and records repair counters.

### Data Model

- Redis key: `channel:{channelId}:messages:hot` (`LPUSH` + `LTRIM`).
- Local state: `ChannelMessageHotStore.channels[channelId] -> TreeMap<messageId, Message>`.
- Retention: bounded by `message.hotBufferPerChannel`.
- Local hot cache can be disabled in multi-node mode via `cache.localProjection.enabled=false`.

### Interfaces and Contracts

- Local hot store API unchanged:
- `append`, `latest`, `before`, `after`, `snapshotStats`.
- Redis cache operations are best-effort acceleration; failures do not fail request flow.

### Dependencies

**Internal modules:**
- `service/ChannelMessageHotStore`
- `service/ProjectionCacheStore`
- `service/MessageServiceImpl`
- `service/MessageProjectionProcessor`

**External services/libraries:**
- Redis via Jedis (optional).

### Failure Modes and Edge Cases

- Redis unavailable: reads and projections use local/DB fallback.
- Redis eviction pressure: hot windows disappear and DB fallback rate rises.
- Out-of-order retries: DB fallback still preserves authoritative ordering.

### Observability and Debugging

- `ChannelHotStoreStats` for local in-memory hot-store behavior.
- `ProjectionCacheRuntimeStats` for Redis availability/errors/fallback reads.
- `ProjectionCacheRuntimeStats.hotRepairWrites` tracks local hot-window repair writes from DB fallback.
- `ProjectionCacheRuntimeStats.projectionDriftDetected` increments when duplicate IDs appear during hot/db merge.
- `MessageRuntimeStats` hot-only/hot-partial/db-only counters.

### Risks and Notes

- Redis hot-window reads currently scan a bounded window and filter by pivot; very large pivot offsets still rely on DB.
- Local and Redis hot stores are acceleration layers only, not source of truth.

Changes:
