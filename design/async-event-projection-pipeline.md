## Async Event Projection Pipeline

### Purpose

Process post-commit message side effects asynchronously (hot cache projection, unread/inbox projection, websocket fanout) using a durable MySQL outbox.

### Scope

**In scope:**
- Durable outbox table and repository.
- Worker claim/lease/retry/dead-letter lifecycle.
- Projection processor for hot cache, membership cache warming, unread recalculation, inbox updates, and websocket broadcast.

**Out of scope:**
- Exactly-once delivery semantics.
- External queue/broker rollout.
- Multi-process worker coordination beyond DB row locking.

### Primary User Flow

1. Message send commits to MySQL and returns to client.
2. Outbox row is available for worker polling.
3. Worker claims and processes event.
4. On success: outbox row marked `DONE`.
5. On failure: row marked `RETRY` with exponential backoff, then `DEAD_LETTER` after max attempts.

### System Flow

1. `MessageRepository.save` writes `message` + `messageOutbox` in one transaction.
2. `AsyncProjectionWorker` polls `MessageOutboxRepository.claimBatch(...)`.
3. `MessageProjectionProcessor.process` performs:
- `ChannelMessageHotStore.append`,
- Redis hot cache append,
- WebSocket `message.created` broadcast,
- unread/read projection cache updates,
- inbox projection updates.
4. Worker updates terminal outbox status (`markDone`, `markRetry`, `markDeadLetter`).

### Data Model

- `messageOutbox` statuses: `PENDING`, `IN_PROGRESS`, `RETRY`, `DONE`, `DEAD_LETTER`.
- Retry control: `attemptCount`, `nextAttemptAt`, `lockedUntil`, `lastError`.
- Projection payload fields include channel/message/sender identity and message content.

### Interfaces and Contracts

- Internal contract: send success depends on authoritative DB commit, not projection completion.
- Worker contract: at-least-once projection execution with bounded retries.

### Dependencies

**Internal modules:**
- `repository/MessageOutboxRepository`
- `service/AsyncProjectionWorker`
- `service/MessageProjectionProcessor`
- `service/ProjectionCacheStore`
- `websocket/ChannelWebSocketRegistry`

**External services/libraries:**
- MySQL row locking (`FOR UPDATE SKIP LOCKED`).

### Failure Modes and Edge Cases

- Worker crash mid-batch => lease timeout + retry path.
- Projection exceptions => retry/dead-letter transitions.
- Partial processing may yield duplicate websocket events on retries.

### Observability and Debugging

- Projection runtime stats: backlog, in-progress, retry, dead-letter, processed/failed, lag p95/p99.
- Worker logs retry/dead-letter decisions with outbox/message identifiers.

### Risks and Notes

- Single-worker design is simple but can bottleneck under heavy spikes.
- Dead-letter handling is observable but not auto-replayed in this iteration.

Changes:

