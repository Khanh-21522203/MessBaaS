## Channel Membership and Access Control

### Purpose

Restrict channel reads/writes/subscriptions to explicit channel members instead of global open access by channel ID.

### Scope

**In scope:**
- Persist channel membership entries.
- Add APIs to join/leave/list channel members.
- Enforce membership checks on HTTP message send/history and WebSocket subscribe/send paths.

**Out of scope:**
- Full authentication/identity provider integration.
- Role/permission hierarchy beyond basic membership.
- Private invite workflows.

### Primary User Flow

1. Channel creator adds members by `clientUserId`.
2. Member subscribes via WebSocket and sends messages.
3. Non-member attempts to subscribe/send/read and receives authorization error.
4. Member list endpoint supports channel roster UIs.

### System Flow

1. Membership routes in `ApiRouter` call channel-membership service methods.
2. Membership service validates user/channel existence and writes to membership repository.
3. `MessageServiceImpl.sendMessage/listMessages` calls membership service guard before DB/cache operations.
4. `WebSocketHandshakeHandler` validates `channelId` and membership before allowing upgrade continuation.

### Data Model

- Table `channelMember` (`V3__channel_member.sql`):
- `id VARCHAR(36) PRIMARY KEY`
- `channelId VARCHAR(36) NOT NULL` FK `channel(id)` `ON DELETE CASCADE`
- `userId VARCHAR(36) NOT NULL` FK ``user``(id) `ON DELETE CASCADE`
- `createdAt`, `updatedAt`
- Unique key `(channelId, userId)` enforces membership uniqueness.

### Interfaces and Contracts

- `POST /api/channels/{channelId}/members` with `{"clientUserId":string}`
- `DELETE /api/channels/{channelId}/members/{clientUserId}`
- `GET /api/channels/{channelId}/members`
- WebSocket handshake contract now requires `clientUserId` query param:
- `GET /ws/channels?channelId=<id>&clientUserId=<id>`
- Membership failures return `403`.

### Dependencies

**Internal modules:**
- `http/ApiRouter` route expansion.
- `service/MessageServiceImpl` enforcement.
- `websocket/WebSocketHandshakeHandler` enforcement.
- New membership repository/service.

**External services/libraries:**
- Existing JDBC/MySQL/Flyway.

### Failure Modes and Edge Cases

- Duplicate member add returns `409`.
- Removing non-member returns `404`.
- Existing open channels require migration strategy for initial member seeding.

### Observability and Debugging

- Log membership add/remove and denied access decisions with channel/user identifiers.
- Track denied handshake count by channel to detect scraping/misuse.

### Risks and Notes

- This is a schema + transport behavior change; clients must handle `403` on previously open routes.
- Membership identity is based on `clientUserId` (no token-based auth yet), so spoof resistance still depends on trusted client identity source.

Changes:

