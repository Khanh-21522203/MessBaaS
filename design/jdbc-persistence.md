## JDBC Persistence and Schema

### Purpose

Provide authoritative MySQL persistence for chat entities and durable outbox-backed async projection scheduling through raw JDBC repositories.

### Scope

**In scope:**
- Flyway migrations `V1..V5`.
- JDBC repositories for users/channels/messages/membership/read-state/outbox.
- Atomic message + outbox write contract.
- Outbox claim/retry/dead-letter state transitions.

**Out of scope:**
- ORM usage.
- Distributed transactions outside MySQL.
- External message broker integration.

### Primary User Flow

1. Send request hits `MessageRepository.save`.
2. Repository inserts message row and corresponding outbox row in one DB transaction.
3. Async worker claims outbox rows and marks `DONE/RETRY/DEAD_LETTER`.
4. Read APIs continue to use MySQL as source of truth.

### System Flow

1. `MessBaaSServer.runMigrations` applies schema including `V5__message_outbox.sql`.
2. `MessageRepository.save`:
- starts transaction,
- inserts `message`,
- inserts `messageOutbox`,
- commits.
3. `MessageOutboxRepository.claimBatch` uses `FOR UPDATE SKIP LOCKED` to reserve work.
4. Worker updates outbox status via `markDone/markRetry/markDeadLetter`.

### Data Model

- Existing authoritative tables: `user`, `channel`, `message`, `channelMember`, `userReadMessage`.
- New table: `messageOutbox`
- key fields: `messageId`, `channelId`, `senderUserId`, `senderClientUserId`, `clientMessageId`, `messageBody`, `imgUrl`, `messageCreatedAt`.
- control fields: `status`, `attemptCount`, `nextAttemptAt`, `lockedUntil`, `lastError`, `processedAt`.
- indexes on status/attempt scan and message reference.

### Interfaces and Contracts

- `MessageRepository.save` preserves idempotency contract on `(channelId, clientMessageId)`.
- Outbox persistence is part of the same transaction as message insert.
- `MessageOutboxRepository` contracts:
- `claimBatch`, `markDone`, `markRetry`, `markDeadLetter`, status/backlog counters.

### Dependencies

**Internal modules:**
- `repository/MessageRepository`
- `repository/MessageOutboxRepository`
- `service/AsyncProjectionWorker`

**External services/libraries:**
- MySQL JDBC.
- Flyway migrations.

### Failure Modes and Edge Cases

- Message insert duplicate with mismatched payload => `ClientMessageConflictException`.
- Outbox insert failure rolls back message transaction.
- Worker crash after claim relies on lease + retry semantics.
- Poison events move to `DEAD_LETTER` after max attempts.

### Observability and Debugging

- Outbox status/backlog counts surfaced via projection runtime stats.
- Repository exceptions bubble to transport boundaries as `500` unless mapped domain conflicts.

### Risks and Notes

- Outbox worker is in-process; prolonged process outage delays projections until restart.
- No cross-process dedupe beyond idempotent projection/write semantics.

Changes:

