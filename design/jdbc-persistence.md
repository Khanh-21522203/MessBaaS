## JDBC Persistence and Schema

### Purpose

Provide authoritative MySQL persistence for chat entities and durable outbox-backed async projection scheduling through raw JDBC repositories.

### Scope

**In scope:**
- Flyway migrations `V1..V6`.
- JDBC repositories for users/channels/messages/membership/read-state/outbox.
- Atomic message + outbox write contract.
- Outbox claim/retry/dead-letter state transitions.
- Incremental projection reconciliation state/checkpoint persistence.

**Out of scope:**
- ORM usage.
- Distributed transactions outside MySQL.
- External message broker integration.

### Primary User Flow

1. Send request hits `MessageRepository.save`.
2. Repository inserts message row and corresponding outbox row in one DB transaction.
3. Async worker claims outbox rows and marks `DONE/RETRY/DEAD_LETTER`.
4. Read APIs continue to use MySQL as source of truth.
5. Reconciliation worker periodically scans messages after checkpoint and reapplies projection updates.

### System Flow

1. `MessBaaSServer.runMigrations` applies schema including `V5__message_outbox.sql` and `V6__projection_reconcile_state.sql`.
2. `MessageRepository.save`:
- starts transaction,
- inserts `message`,
- inserts `messageOutbox`,
- commits.
3. `MessageOutboxRepository.claimBatch` uses `FOR UPDATE SKIP LOCKED` to reserve work.
4. Worker updates outbox status via `markDone/markRetry/markDeadLetter`.
5. `ProjectionReconcileWorker` loads checkpoint from `projectionReconcileState`, scans `message` rows with `id > checkpoint` in bounded batches, replays projection updates, then persists new checkpoint.

### Data Model

- Existing authoritative tables: `user`, `channel`, `message`, `channelMember`, `userReadMessage`.
- New table: `messageOutbox`
- key fields: `messageId`, `channelId`, `senderUserId`, `senderClientUserId`, `clientMessageId`, `messageBody`, `imgUrl`, `messageCreatedAt`.
- control fields: `status`, `attemptCount`, `nextAttemptAt`, `lockedUntil`, `lastError`, `processedAt`.
- indexes on status/attempt scan and message reference.
- New table: `projectionReconcileState(scope, checkpoint, updatedAt)` for persisted incremental watermark.

### Interfaces and Contracts

- `MessageRepository.save` preserves idempotency contract on `(channelId, clientMessageId)`.
- Outbox persistence is part of the same transaction as message insert.
- `MessageOutboxRepository` contracts:
- `claimBatch`, `markDone`, `markRetry`, `markDeadLetter`, status/backlog counters.
- `MessageRepository.listMessagesForReconcileAfterId` supports bounded ascending scans for reconciliation.
- `ProjectionReconcileStateRepository` stores/retrieves per-scope checkpoints.

### Dependencies

**Internal modules:**
- `repository/MessageRepository`
- `repository/MessageOutboxRepository`
- `repository/ProjectionReconcileStateRepository`
- `service/AsyncProjectionWorker`
- `service/ProjectionReconcileWorker`

**External services/libraries:**
- MySQL JDBC.
- Flyway migrations.

### Failure Modes and Edge Cases

- Message insert duplicate with mismatched payload => `ClientMessageConflictException`.
- Outbox insert failure rolls back message transaction.
- Worker crash after claim relies on lease + retry semantics.
- Poison events move to `DEAD_LETTER` after max attempts.
- Reconciliation replay can reapply already-projected messages; projection logic must remain idempotent/monotonic.

### Observability and Debugging

- Outbox status/backlog counts surfaced via projection runtime stats.
- Reconciliation checkpoint/progress/failure counters surfaced in `/api/ops/stats.reconciliation`.
- Repository exceptions bubble to transport boundaries as `500` unless mapped domain conflicts.

### Risks and Notes

- Outbox worker is in-process; prolonged process outage delays projections until restart.
- No cross-process dedupe beyond idempotent projection/write semantics.

Changes:
