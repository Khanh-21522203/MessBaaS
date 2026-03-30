## Async Event Projection Pipeline

### Purpose

Provide a durable post-commit event pipeline so inbox/unread/fanout projections run asynchronously without increasing send-path latency, while preserving reliability after MySQL commits.

### Scope

**In scope:**
- Persist internal projection events durably after authoritative message writes.
- Run background workers that claim events, project to Redis read models, and fan out websocket payloads.
- Implement retry/backoff and dead-letter handling for failed projection work.
- Support replay-safe processing with idempotent projection behavior.

**Out of scope:**
- Exactly-once delivery guarantees.
- Cross-region/distributed broker infrastructure rollout.
- External public APIs for queue management.

### Primary User Flow

1. Client sends a message through HTTP or WebSocket.
2. Service commits message to MySQL and returns success.
3. Internal event is persisted and picked up by projection workers.
4. Workers update inbox/unread caches and trigger websocket fanout asynchronously.
5. If projection fails, worker retries with backoff; eventually dead-letters after max attempts.

### System Flow

1. Entry point: `src/main/java/com/java_mess/java_mess/service/MessageServiceImpl.java:sendMessage`.
2. Message persistence path in `src/main/java/com/java_mess/java_mess/repository/MessageRepository.java` writes authoritative message row and durable projection event (outbox) in one atomic unit.
3. New worker runtime (wired from `src/main/java/com/java_mess/java_mess/MessBaaSServer.java`) polls claimable events from a new outbox repository.
4. Worker runs projection handlers (inbox/unread cache updaters + websocket fanout publisher) and marks event as completed, scheduled-retry, or dead-letter.
5. Operational stats endpoint exposes backlog, lag, retry, and dead-letter counters.

```
POST /api/messages/{channelId}
  -> MessageServiceImpl.sendMessage
      -> MessageRepository.save + outbox write (atomic)
      -> return 200 to client

Projection worker loop
  -> claim pending outbox rows
  -> project inbox/unread + websocket fanout
  -> ack success OR schedule retry OR dead-letter
```

### Data Model

- `messageOutbox` (new table via Flyway):
- `id (BIGINT PK AUTO_INCREMENT)`, `eventType (VARCHAR)`, `aggregateId (VARCHAR)`, `payload (JSON/TEXT)`.
- `status (VARCHAR)` where values include `pending`, `in_progress`, `retry`, `dead_letter`, `done`.
- `attemptCount (INT)`, `nextRetryAt (TIMESTAMP)`, `lastError (TEXT)`, `createdAt`, `updatedAt`.
- Indexes for polling and retry: `(status, nextRetryAt, id)` and `(aggregateId, eventType)`.
- Projection handlers must be idempotent for duplicate/replayed events.

### Interfaces and Contracts

- Internal contract: message send success is gated only on authoritative MySQL commit, not on projection completion.
- Internal repository contract (new): claim batch, acknowledge success, schedule retry with backoff, dead-letter after attempt budget.
- Internal worker contract: process events in bounded batches with per-batch timeout and retry policy.
- No direct external HTTP API in first iteration; observability remains through `/api/ops/stats`.

### Dependencies

**Internal modules:**
- `service/MessageServiceImpl` - emits projection work after message persistence.
- `repository/MessageRepository` - authoritative persistence boundary.
- `websocket/ChannelWebSocketRegistry` - async fanout consumer target.
- `service/ReadStateServiceImpl` and new inbox projection service - read-model update targets.
- `MessBaaSServer` - worker lifecycle and wiring.

**External services/libraries:**
- MySQL - durable source-of-truth and outbox persistence.
- Redis - projection/read-model targets.

### Failure Modes and Edge Cases

- Outbox insert failure in write transaction: send request fails and no partial success is returned.
- Worker crash after claim and before ack: lease timeout/retry returns event to pending processing.
- Duplicate event processing due to retries: projections must remain idempotent.
- Projector lag spikes: backlog grows; reads may remain temporarily stale but degrade to MySQL.
- Poison event repeatedly failing: moved to dead-letter after max attempts for operator intervention.

### Observability and Debugging

- Track counters: `projection.pending`, `projection.inProgress`, `projection.retry`, `projection.deadLetter`, `projection.success`.
- Track lag metric: `message_commit_to_projection_ms` percentile snapshots.
- Log structured projection failures with outbox ID, event type, attempt count, and error class.
- Debug start points: worker claim loop and ack/retry transitions in new outbox repository + worker modules.

### Risks and Notes

- Without atomic message+outbox persistence, committed messages can miss projection/fanout.
- Retry policy must avoid hot-looping on permanent failures.
- Batch sizes and polling interval directly affect lag/latency tradeoffs.

Changes:

> Suggested [Impact: High] [Effort: L]: Implement a durable outbox-driven async projection pipeline that is triggered by committed message writes, with bounded worker retries/backoff and dead-letter handling so reliability is preserved while keeping request latency low.
> Source: user request — design/update.md
> Approach: add outbox migration + repository methods under `src/main/resources/db/migration/` and `src/main/java/com/java_mess/java_mess/repository/`; emit outbox events from `MessageServiceImpl`/`MessageRepository` atomic write path; add worker lifecycle wiring in `MessBaaSServer`; connect projection handlers for inbox/unread caches and websocket fanout.
> Builds on: existing message send flow, websocket registry, and runtime stats endpoint.
> Constraints: MySQL source-of-truth guarantee; reliability-first behavior must tolerate Redis/projector outages.
> Edge cases: duplicate events, projector lag spikes, retry storms, dead-letter accumulation.
> Risk: incorrect retry/idempotency design can produce duplicate side effects or stalled backlogs.
