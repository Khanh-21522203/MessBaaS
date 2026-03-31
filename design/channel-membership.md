## Channel Membership and Access Control

### Purpose

Enforce channel-level authorization for message send/read/WebSocket flows with a cache-first membership check that falls back to MySQL for correctness.

### Scope

**In scope:**
- Membership add/remove/list HTTP routes.
- `assertMember` enforcement for HTTP + WebSocket paths.
- Redis/local membership cache acceleration.
- DB fallback on cache misses or stale state.

**Out of scope:**
- Token-based authentication.
- Role hierarchy beyond membership.
- Invite workflows.

### Primary User Flow

1. Client adds/removes channel member via HTTP.
2. Membership cache is updated write-through.
3. Send/read/handshake checks hit cache first.
4. On cache miss, service verifies membership in DB and rehydrates cache.

### System Flow

1. `ApiRouter` routes member operations to `ChannelMembershipServiceImpl`.
2. `addMember/removeMember` mutate `ChannelMemberRepository` and update `ProjectionCacheStore`.
3. `listMembers` returns DB roster and warms cache entries.
4. `assertMember`:
- resolve channel + user from repositories,
- attempt cached lookup (`ProjectionCacheStore.isMemberCached`),
- fallback to `ChannelMemberRepository.isMember` if needed,
- deny with `ChannelAccessDeniedException` when not a member.

### Data Model

- Authoritative table: `channelMember(channelId, userId, createdAt, updatedAt)` unique `(channelId, userId)`.
- Cache key: `channel:{channelId}:members` (user IDs).

### Interfaces and Contracts

- `POST /api/channels/{channelId}/members`
- `DELETE /api/channels/{channelId}/members/{clientUserId}`
- `GET /api/channels/{channelId}/members`
- Membership failures return `403`.

### Dependencies

**Internal modules:**
- `service/ChannelMembershipServiceImpl`
- `repository/ChannelMemberRepository`, `ChannelRepository`, `UserRepository`
- `service/ProjectionCacheStore`
- `websocket/WebSocketHandshakeHandler`, `service/MessageServiceImpl`

**External services/libraries:**
- MySQL (authoritative).
- Redis (optional cache acceleration).

### Failure Modes and Edge Cases

- Duplicate add => `409`.
- Remove non-member => `404`.
- Cache false-negative/stale key => DB fallback preserves correctness.
- Redis unavailable => DB-backed checks continue.

### Observability and Debugging

- Authorization errors appear in router/transport logs.
- Cache-health counters are visible via projection cache runtime stats.

### Risks and Notes

- Membership cache is an optimization only; correctness depends on DB fallback remaining intact.

Changes:

