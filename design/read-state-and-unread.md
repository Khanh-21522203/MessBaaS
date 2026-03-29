## Read State and Unread Tracking

### Purpose

Persist per-user read progress per channel and return unread counts aligned to message-ID pagination.

### Scope

**In scope:**
- Persist a per-channel, per-user read pointer as `lastReadMessageId`.
- Expose read-pointer write/read APIs.
- Compute unread counts from `message.id` windows.

**Out of scope:**
- Push notifications.
- Presence/typing indicators.
- Multi-device merge policies beyond monotonic max.

### Primary User Flow

1. User opens a channel and fetches history.
2. Client marks the latest visible message ID as read.
3. Backend stores the read pointer for `(channelId, userId)`.
4. Client requests unread count and receives count since the stored pointer.

### System Flow

1. `PUT /api/channels/{channelId}/read-cursor` receives `(clientUserId, lastReadMessageId)`.
2. Service validates user/channel and upserts cursor with monotonic max behavior.
3. `GET /api/channels/{channelId}/unread-count?clientUserId=...` resolves cursor and runs `count(message.id > lastReadMessageId)`.
4. Response returns `lastReadMessageId` and `unreadCount`.

### Data Model

- `V4__user_read_message_id_cursor.sql` replaced `userReadMessage.lastReadMessage` (timestamp) with `lastReadMessageId BIGINT`.
- Migration backfills historical rows by selecting max `message.id` per channel where `createdAt <= lastReadMessage`.
- Unique key `(channelId, userId)` remains the upsert key.

### Interfaces and Contracts

- `PUT /api/channels/{channelId}/read-cursor`
- Body: `{"clientUserId":string,"lastReadMessageId":number}`
- `GET /api/channels/{channelId}/unread-count?clientUserId=<id>`
- Response: `{"channelId":string,"clientUserId":string,"unreadCount":number,"lastReadMessageId":number|null}`

### Dependencies

**Internal modules:**
- `http/ApiRouter`
- `service/ReadStateServiceImpl`
- `repository/UserReadMessageRepository`
- `repository/UserRepository`
- `repository/ChannelRepository`

**External services/libraries:**
- Existing MySQL/JDBC/Flyway stack.

### Failure Modes and Edge Cases

- Unknown user/channel returns `404`.
- Negative cursor ID returns `400`.
- Cursor ahead of latest message is clamped to latest channel message ID.
- First-time reader (no row yet) returns unread count for full channel history.

### Observability and Debugging

- Router logs read-state errors via existing `ApiRouter.route` warning path.
- Read-state/unread behavior can be validated through HTTP routes and repository query paths.

### Risks and Notes

- This migration changes read-state API payload field names from `lastReadMessageAt` to `lastReadMessageId`.
- All clients should now send/read message IDs for read-state updates.

Changes:

