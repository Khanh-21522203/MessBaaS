## Channel Hot Buffer Cache

### Purpose

Maintain a bounded in-memory recent-message buffer per channel to reduce database reads for history queries.

### Scope

**In scope:**
- Append persisted messages into per-channel buffer.
- Query recent (`latest`), older (`before`), and newer (`after`) windows by message ID.
- Enforce per-channel size cap.
- Return defensive copies so callers cannot mutate cached state.

**Out of scope:**
- Persistence durability (cache is memory-only).
- Cache warmup from database on startup.
- Cross-node coherence (single-process only).

### Primary User Flow

1. Service persists a message in DB.
2. Service appends message to hot store for the message channel.
3. History reads request windows; service asks hot store first.
4. If hot store cannot satisfy requested count, service fills remaining window from DB queries.

### System Flow

1. `MessageServiceImpl.sendMessage` calls `channelMessageHotStore.append(message)` after `MessageRepository.save`.
2. `ChannelMessageHotStore.append` validates message has persisted `id` and channel ID, then routes to channel-specific `ChannelBuffer`.
3. `ChannelBuffer.append` inserts into `TreeMap<Long, Message>` and evicts oldest entries when size exceeds `perChannelLimit`.
4. Read APIs (`latest`, `before`, `after`) gather ordered windows and return copied `Message` objects.
5. `MessageServiceImpl.latestMessages/messagesBefore/messagesAfter` combine hot-store results with DB fallback from `MessageRepository` when needed.

### Data Model

- `ChannelMessageHotStore` state:
- `perChannelLimit (int)` configured from `message.hotBufferPerChannel`.
- `channels (ConcurrentMap<String, ChannelBuffer>)` keyed by `channelId`.
- `ChannelBuffer.messages (NavigableMap<Long, Message>)` stores messages by message ID.
- Persistence rules:
- No database table owned by this feature.
- Cache entries are process-local and cleared on restart.
- Message copies include nested `Channel` and `User` copies to isolate mutable state.

### Interfaces and Contracts

- `append(Message message)`
- Requires non-null message, message ID, channel ID; otherwise throws `IllegalArgumentException`.
- `latest(String channelId, int limit)` returns newest-first list (descending message IDs).
- `before(String channelId, long pivotId, int limit)` returns IDs `< pivotId` in descending order.
- `after(String channelId, long pivotId, int limit)` returns IDs `> pivotId` in ascending order.
- Non-positive limits return empty lists.

### Dependencies

**Internal modules:**
- `service/MessageServiceImpl` as caller for append and read-window use.
- `model/Message`, `model/Channel`, `model/User` for copy operations.

**External services/libraries:**
- Java concurrent collections (`ConcurrentHashMap`, synchronized blocks).
- No network/database dependencies.

### Failure Modes and Edge Cases

- Constructing with `perChannelLimit <= 0` throws `IllegalArgumentException("perChannelLimit must be positive")`.
- Appending message without persisted identifiers throws `IllegalArgumentException("message with persisted id and channel is required")`.
- Unknown channel ID for reads returns empty list.
- Hot-store partial results are expected and merged with DB fallback by `MessageServiceImpl`.

### Observability and Debugging

- `ChannelMessageHotStore` tracks runtime counters: `latestHit`, `beforeHit`, `afterHit`, `miss`, `eviction`, and `channelCount` via `snapshotStats()`.
- `MessageServiceImpl` tracks read-path counters (`hotOnly`, `hotPartialWithDbFallback`, `dbOnly`) and emits periodic INFO logs every 1000 sampled read paths.
- Runtime snapshots are exposed through `GET /api/ops/stats` from `ApiRouter`.
- Unit tests in `src/test/java/com/java_mess/java_mess/service/ChannelMessageHotStoreTest.java` validate ordering, bounds, defensive-copy behavior, and non-positive limit handling.
- Debug cache behavior by setting breakpoints in `ChannelBuffer.collect` and `MessageServiceImpl.latestMessages/messagesBefore/messagesAfter`.

### Risks and Notes

- Memory usage scales with number of active channels * `perChannelLimit`.
- Cache is not TTL-based; eviction is strictly size-based.
- Counters are process-local and reset on restart.

Changes:

> Suggested [Impact: High] [Effort: L]: Evolve the hot buffer from process-local only to a Redis-backed channel hot window (`channel:{channelId}:messages:hot`) with strict per-channel retention, while preserving MySQL fallback so reads remain correct when cache entries are missing or evicted.
> Source: user request — design/update.md
> Approach: keep `src/main/java/com/java_mess/java_mess/service/ChannelMessageHotStore.java` as an abstraction and add a Redis-backed implementation path used by `src/main/java/com/java_mess/java_mess/service/MessageServiceImpl.java`; define append/read/trim semantics equivalent to current `latest/before/after` behavior; retain DB fallback via `MessageRepository` for cache misses.
> Builds on: existing bounded hot-buffer read path and runtime stats counters.
> Constraints: performance-sensitive design; MySQL remains authoritative for correctness.
> Edge cases: Redis eviction pressure removing hot windows, cross-instance cache inconsistency, out-of-order cache writes under retries, cache unavailable at read time.
> Risk: key payload size and retention defaults can cause Redis memory pressure and destabilize latency if not bounded.
