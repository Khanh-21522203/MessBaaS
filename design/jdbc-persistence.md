## JDBC Persistence and Schema

### Purpose

Define MySQL schema and raw JDBC repositories used by services to persist and query users, channels, and messages.

### Scope

**In scope:**
- Flyway schema migration `V1__init.sql`.
- `UserRepository`, `ChannelRepository`, `MessageRepository` SQL contracts and mapping.
- ID/timestamp assignment behavior in repository save methods.

**Out of scope:**
- Business validation logic (duplicate checks, HTTP status mapping).
- In-memory cache behavior.
- Background jobs or asynchronous persistence (none implemented).

### Primary User Flow

1. On startup, Flyway applies initial schema.
2. Services call repository methods for CRUD-like operations.
3. Repositories execute SQL through `DataSource` connections and map rows to model objects.
4. Repository exceptions are wrapped in `IllegalStateException` and propagated to caller.

### System Flow

1. Startup path: `MessBaaSServer.runMigrations` executes Flyway migration from `src/main/resources/db/migration`.
2. User operations call `UserRepository.findByClientUserId` and `UserRepository.save`.
3. Channel operations call `ChannelRepository.findByClientReferenceId`, `findById`, and `save`.
4. Message operations call `MessageRepository.save`, `findLatestMessages`, `listMessagesBeforeId`, and `listMessagesAfterId`.
5. Query methods map JDBC `ResultSet` rows into `User`, `Channel`, and `Message` models.

### Data Model

- Table ``user``:
- `id VARCHAR(36) PRIMARY KEY`
- `clientUserId VARCHAR(255) NOT NULL UNIQUE`
- `name VARCHAR(255)`
- `profileImgUrl TEXT`
- `createdAt TIMESTAMP DEFAULT CURRENT_TIMESTAMP`
- Table `channel`:
- `id VARCHAR(36) PRIMARY KEY`
- `name VARCHAR(255) NOT NULL`
- `clientReferenceId VARCHAR(255) NOT NULL UNIQUE`
- `createdAt TIMESTAMP DEFAULT CURRENT_TIMESTAMP`
- Table `message`:
- `id BIGINT AUTO_INCREMENT PRIMARY KEY`
- `channelId VARCHAR(36) NOT NULL` FK `channel(id)` ON DELETE CASCADE
- `userId VARCHAR(36) NOT NULL` FK ``user``(id) ON DELETE CASCADE
- `message TEXT NOT NULL`
- `imgUrl VARCHAR(2000) NOT NULL`
- `isDeleted TINYINT(1) NOT NULL`
- `createdAt TIMESTAMP DEFAULT CURRENT_TIMESTAMP`
- `updatedAt TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP`
- Index `idx_message_channel_id_id(channelId, id)` supports channel-window scans.
- Table `userReadMessage`:
- `id VARCHAR(36) PRIMARY KEY`
- `channelId`, `userId`, `lastReadMessage`, timestamps, unique `(channelId,userId)`
- No repository/service currently reads or writes `userReadMessage`; behavior is unknown from current code.

### Interfaces and Contracts

- `UserRepository.findByClientUserId(String)` -> `Optional<User>`.
- `UserRepository.save(User)`:
- Generates UUID when `user.id` is null.
- Uses `Instant.now()` when `user.createdAt` is null.
- `ChannelRepository.findByClientReferenceId(String)` / `findById(String)` -> `Optional<Channel>`.
- `ChannelRepository.save(Channel)` with similar UUID + timestamp defaults.
- `MessageRepository.save(Message)`:
- Inserts message row and reads auto-increment generated key.
- Uses `createdAt` default now and `updatedAt` default to `createdAt`.
- `MessageRepository.findLatestMessages(channelId, limit)` returns newest-first (`ORDER BY m.id DESC`).
- `MessageRepository.listMessagesBeforeId(id, channelId, limit)` returns older messages descending.
- `MessageRepository.listMessagesAfterId(id, channelId, limit)` returns newer messages ascending.

### Dependencies

**Internal modules:**
- Called by service layer classes under `service/*`.
- Uses model classes under `model/*`.

**External services/libraries:**
- JDBC (`java.sql.*`) and injected `javax.sql.DataSource`.
- MySQL server/schema created by migration.
- Flyway for migration lifecycle.

### Failure Modes and Edge Cases

- Any SQL error is wrapped as `IllegalStateException` with message like `Failed to save message` or `Failed to load channel`.
- `MessageRepository.save` throws `IllegalStateException("Failed to create message id")` if generated keys are unavailable.
- `MessageRepository.mapMessage` only sets `Channel.id` for query results; channel name/reference are not joined in history queries.
- No explicit transaction boundaries across multi-repository service operations.

### Observability and Debugging

- Repository layer does not log SQL statements or timing.
- Debugging starts by catching thrown `IllegalStateException` in upper layers (`ApiRouter.route`, websocket handler).
- Validate schema shape via `src/main/resources/db/migration/V1__init.sql` and startup Flyway output.

### Risks and Notes

- Repository methods are synchronous and can block business threads on slow DB I/O.
- Lack of transaction wrapping can expose partial-failure scenarios in future multi-step writes.
- `userReadMessage` table/model exist without active feature integration.

Changes:

