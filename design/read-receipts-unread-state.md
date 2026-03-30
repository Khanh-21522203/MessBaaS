## Read Receipts and Unread State

### Purpose

Track per-user read progress per channel and expose unread counts so clients can resume conversations without manual message scanning.

### Scope

**In scope:**
- Persist per-channel read cursor for each user.
- Return unread message counts for a user/channel pair.
- Validate user/channel existence before read-state writes.
- Use message-ID cursor semantics for deterministic unread calculations.

**Out of scope:**
- Push notifications or mobile notification delivery.
- Multi-device merge conflict resolution beyond "latest read wins".
- Full notification preferences.

### Primary User Flow

1. Client opens a channel and renders recent history.
2. Client sends read-cursor update with the newest visible `message.id`.
3. Service stores/upserts the user/channel read cursor.
4. Client asks for unread counts when listing channels or re-opening app sessions.
5. Service returns unread count computed from message rows with IDs greater than the stored cursor.

### System Flow

1. Routes in `ApiRouter.route` handle:
- `PUT /api/channels/{channelId}/read-cursor`
- `GET /api/channels/{channelId}/unread-count?clientUserId=...`
2. `ApiRouter` validates payload/query using `RequestValidator`.
3. `ReadStateService` resolves `UserRepository.findByClientUserId` + `ChannelRepository.findById`.
4. `UserReadMessageRepository` upserts read cursor into `userReadMessage` with monotonic max behavior.
5. Unread count query is computed as `message.id > userReadMessage.lastReadMessageId`.

### Data Model

- `V4__user_read_message_id_cursor.sql` migrates `userReadMessage.lastReadMessage (TIMESTAMP)` to `lastReadMessageId (BIGINT)`.
- Table `userReadMessage` active shape:
- `channelId`, `userId`, `lastReadMessageId`, unique `(channelId, userId)`.
- Migration backfills `lastReadMessageId` from existing messages by channel and historical timestamp cursor.

### Interfaces and Contracts

- `PUT /api/channels/{channelId}/read-cursor`
- Body: `{"clientUserId":string,"lastReadMessageId":number}`
- Success: `200` with stored cursor payload.
- Errors: `400` invalid payload, `404` unknown user/channel.
- `GET /api/channels/{channelId}/unread-count?clientUserId=<id>`
- Success: `200` with `{"unreadCount":number,"lastReadMessageId":number|null}`.
- Errors: `400` invalid query, `404` unknown user/channel.

### Dependencies

**Internal modules:**
- `http/ApiRouter`, `http/RequestValidator`.
- `repository/UserRepository`, `repository/ChannelRepository`, `repository/UserReadMessageRepository`.
- `service/ReadStateService`.

**External services/libraries:**
- MySQL via existing JDBC `DataSource`.

### Failure Modes and Edge Cases

- Missing read cursor for first-time user/channel returns unread count based on full channel history.
- Out-of-order cursor updates use monotonic max behavior to avoid unread regression.
- Cursor IDs ahead of latest channel message are clamped to latest existing message ID.
- Negative cursor IDs map to `400`.
- Repository failures map to `500` through existing router error handling.

### Observability and Debugging

- Read-state errors flow through existing `ApiRouter.route` warning logging.
- Validate behavior through read-cursor/unread-count HTTP routes and repository methods.

### Risks and Notes

- The ID-cursor migration changes read-state API payload fields from `lastReadMessageAt` to `lastReadMessageId`.
- Client integrations must use message IDs rather than client clocks for read-state updates.
- Read-state semantics now align with message paging/pivot APIs (`message.id`).

Changes:

> Suggested [Impact: High] [Effort: L]: Introduce async unread/read projection caches (`user:{userId}:reads`, `user:{userId}:unread`) updated from persisted message events so inbox/unread reads are memory-first, with periodic reconciliation against MySQL to bound drift.
> Source: user request — design/update.md
> Approach: keep `src/main/java/com/java_mess/java_mess/service/ReadStateServiceImpl.java` and `src/main/java/com/java_mess/java_mess/repository/UserReadMessageRepository.java` as authoritative cursor logic; add projection-worker updates keyed by user/channel/message; define reconciliation job that re-computes unread from MySQL and repairs cache drift when lag or retries occur.
> Builds on: existing message-ID cursor semantics and unread-count query contracts.
> Constraints: MySQL source-of-truth guarantee; performance-sensitive read path; tolerate eventual consistency in secondary cached unread views.
> Edge cases: projector lag spikes causing temporarily stale unread counts, duplicate projection events, cache drift after worker restart, cursor updates racing with new-message projections.
> Risk: if reconciliation cadence is too sparse, unread mismatch can persist long enough to break user trust.
