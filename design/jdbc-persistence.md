## JDBC Persistence and Schema

### Purpose

Define MySQL schema and raw JDBC repositories used by services to persist and query users, channels, and messages.

### Scope

**In scope:**
- Flyway schema migrations `V1__init.sql`, `V2__message_contract.sql`, `V3__channel_member.sql`, and `V4__user_read_message_id_cursor.sql`.
- `UserRepository`, `ChannelRepository`, `MessageRepository`, `ChannelMemberRepository`, and `UserReadMessageRepository` SQL contracts and mapping.
- ID/timestamp assignment behavior in repository save methods.

**Out of scope:**
- Business validation logic (duplicate checks, HTTP status mapping).
- In-memory cache behavior.
- Background jobs or asynchronous persistence (none implemented).

### Primary User Flow

1. On startup, Flyway applies initial schema.
2. Services call repository methods for CRUD-like operations.
3. Repositories execute SQL through `DataSource` connections and map rows to model objects.
4. Constraint-collision SQL failures are translated to domain exceptions for stable HTTP status mapping.

### System Flow

1. Startup path: `MessBaaSServer.runMigrations` executes Flyway migration from `src/main/resources/db/migration`.
2. User operations call `UserRepository.findByClientUserId` and `UserRepository.save`.
3. Channel operations call `ChannelRepository.findByClientReferenceId`, `findById`, and `save`.
4. Message operations call `MessageRepository.save`, `findByChannelIdAndClientMessageId`, `findLatestMessages`, `listMessagesBeforeId`, and `listMessagesAfterId`.
5. Membership operations call `ChannelMemberRepository.addMember/removeMember/isMember/listMembers`.
6. Read-state operations call `UserReadMessageRepository.upsertReadCursor/findReadCursor/countUnreadMessages`.
7. Query methods map JDBC `ResultSet` rows into `User`, `Channel`, and `Message` models.

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
- `clientMessageId VARCHAR(128) NOT NULL`
- `message TEXT NOT NULL`
- `imgUrl VARCHAR(2000) NULL`
- `isDeleted TINYINT(1) NOT NULL`
- `createdAt TIMESTAMP DEFAULT CURRENT_TIMESTAMP`
- `updatedAt TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP`
- Index `idx_message_channel_id_id(channelId, id)` supports channel-window scans.
- Unique index `uq_message_channel_client_message(channelId, clientMessageId)` enables idempotent retries.
- Table `channelMember`:
- `id VARCHAR(36) PRIMARY KEY`
- `channelId`, `userId`, timestamps, unique `(channelId,userId)`
- Table `userReadMessage`:
- `id VARCHAR(36) PRIMARY KEY`
- `channelId`, `userId`, `lastReadMessageId`, timestamps, unique `(channelId,userId)`
- Active read-state storage is message-ID based (`lastReadMessageId BIGINT`) and is used by `UserReadMessageRepository`.

### Interfaces and Contracts

- `UserRepository.findByClientUserId(String)` -> `Optional<User>`.
- `UserRepository.save(User)`:
- Generates UUID when `user.id` is null.
- Uses `Instant.now()` when `user.createdAt` is null.
- `ChannelRepository.findByClientReferenceId(String)` / `findById(String)` -> `Optional<Channel>`.
- `ChannelRepository.save(Channel)` with similar UUID + timestamp defaults and duplicate-key translation to `ChannelExistedException`.
- `ChannelRepository.listChannels(beforeCreatedAt, limit)` for discovery windows.
- `MessageRepository.save(Message)`:
- Inserts message row and reads auto-increment generated key; duplicate `(channelId,clientMessageId)` returns existing persisted message for identical payload.
- Uses `createdAt` default now and `updatedAt` default to `createdAt`.
- `MessageRepository.findLatestMessages(channelId, limit)` returns newest-first (`ORDER BY m.id DESC`).
- `MessageRepository.listMessagesBeforeId(id, channelId, limit)` returns older messages descending.
- `MessageRepository.listMessagesAfterId(id, channelId, limit)` returns newer messages ascending.
- `ChannelMemberRepository` supports membership add/remove/check/list.
- `UserReadMessageRepository` supports cursor clamp + monotonic upsert and unread-count queries by `message.id`.

### Dependencies

**Internal modules:**
- Called by service layer classes under `service/*`.
- Uses model classes under `model/*`.

**External services/libraries:**
- JDBC (`java.sql.*`) and injected `javax.sql.DataSource`.
- MySQL server/schema created by migration.
- Flyway for migration lifecycle.

### Failure Modes and Edge Cases

- Duplicate-key SQL failures in user/channel writes map to `ClientUserIdExistedException` / `ChannelExistedException` instead of generic `500`.
- Duplicate `(channelId,clientMessageId)` with mismatched payload maps to `ClientMessageConflictException`.
- Non-collision SQL failures are wrapped as `IllegalStateException` with messages like `Failed to save message`.
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
- `V4__user_read_message_id_cursor.sql` backfills legacy timestamp cursors into message IDs; channels with no prior message before a legacy cursor backfill to `0`.

Changes:
