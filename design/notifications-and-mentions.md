## Notifications and Mentions

### Purpose

Provide in-app notification delivery for mentions, thread replies, and direct messages, with optional email fallback for offline users.

### Scope

**In scope:**
- Mention parsing for `@clientUserId` in message bodies.
- Notification persistence and read APIs.
- Real-time websocket notification events.
- Optional async email fallback when user is offline.

**Out of scope:**
- Mobile push integration.
- Complex notification preferences UI.
- Digest batching.

### Primary User Flow

1. User is mentioned or receives DM/thread reply.
2. Notification is created asynchronously after message commit.
3. User sees notification in-app and can mark it read.
4. If user is offline, fallback email can be sent.

### System Flow

1. `MessageProjectionProcessor.process` computes notification recipients (mentions, thread participants, DM peer).
2. Notification rows are persisted and optionally cached by user.
3. `ChannelWebSocketRegistry` emits user-scoped `notification.created` events.
4. Optional email task is queued when no active connection is detected for recipient.

### Data Model

- New table: `notification(id, userId, type, channelId, dmConversationId, messageId, isRead, createdAt, readAt)`.
- Optional table: `notificationOutbox` for retryable email tasks.
- Cache keys (optional):
- `user:{userId}:notifications` (sorted notification IDs)
- `user:{userId}:notificationUnreadCount`

### Interfaces and Contracts

- `GET /api/notifications?clientUserId=...&limit=...`
- `POST /api/notifications/{notificationId}/read`
- Optional: `GET /api/notifications/unread-count?clientUserId=...`

### Dependencies

**Internal modules:**
- `service/MessageProjectionProcessor`
- `service/InboxServiceImpl`
- `websocket/ChannelWebSocketRegistry`
- `http/ApiRouter`

**External services/libraries:**
- Existing MySQL/Flyway stack.
- Optional email provider for offline fallback.

### Failure Modes and Edge Cases

- Mention references unknown user => ignored or validation error (choose one contract).
- Duplicate notification during projection retry => enforce idempotent unique key by `(userId, type, messageId)`.
- Offline detection false negatives => user may receive both in-app and email notification.

### Observability and Debugging

- Add notification create/read counters and delivery latency percentiles.
- Track email fallback attempts/failures and dead-letter counts.

### Risks and Notes

- User-scoped websocket delivery may require extending `ChannelWebSocketRegistry` to index active sockets by user ID, not only by channel.
- Keep first version small: mentions + DM + thread-reply notifications only.

Changes:
> Suggested [Priority: High | Impact: Medium | Feasibility: Medium]: add an async notification subsystem on top of `MessageProjectionProcessor` so mentions/replies/DM events create durable user notifications without blocking send latency.
> Blocked [Med]: recipient model is underspecified before implementation in `service/MessageProjectionProcessor` and `websocket/ChannelWebSocketRegistry`.
> Options:
>   (1) In-app notifications only for channel mentions (no DM/thread integration yet). Smallest scope and aligns with current channel-only architecture.
>   (2) Full model (mentions + DM + thread reply) in one cycle. Higher impact but depends on unresolved DM/thread feature blockers.
> Recommendation: option (1) now, then extend to DM/thread once those feature blockers are resolved.
> If you reply `yes` to option (1), no additional architectural decisions are required for first implementation.
> Reply: 1
> Blocked [Med]: mention-recipient identity currently depends on caller-claimed `clientUserId` only. Without a server-verified identity boundary, notification provenance can be spoofed and user-targeted notifications are not trustworthy for non-internal usage.
> Options:
>   (1) Proceed with internal-only trust model and explicitly document spoofing risk.
>   (2) Restore/authenticate identity first, then implement mentions/notifications.
> Recommendation: option (2) for production-like environments; option (1) is acceptable only for trusted internal MVP.
> Reply: _______
> Suggested: expose `GET /api/notifications` and mark-read routes in `ApiRouter`, with idempotent notification inserts keyed by `(userId, type, messageId)` to survive projection retries.
> Blocked [Low]: depends on option (1) vs (2) above to finalize response schema fields (`channelId` only vs channel+dm+thread references).
> Options:
>   (1) Channel-mention schema only (minimal fields) for first release.
>   (2) Full polymorphic schema now (channel/dm/thread references).
> Recommendation: option (1) to keep `ApiRouter`/DTO changes small until DM/thread features exist.
> Reply: 1
> Blocked [Low]: API surface is tied to the trust-model decision above (internal-only vs authenticated identity). Route implementation is deferred until that decision is confirmed to avoid churn in ownership/authorization checks.
> Options:
>   (1) Implement now with internal-only trust model and lightweight `clientUserId` ownership checks.
>   (2) Wait for auth boundary and implement strict token-derived ownership checks.
> Recommendation: option (1) only if you explicitly accept internal-only trust for this cycle.
> Reply: _______
> Suggested: start email fallback as optional and queue-backed; keep in-app websocket notifications as the default MVP behavior.
> Blocked [Low]: requires provider and sender-policy decision not present in current runtime.
> Options:
>   (1) Defer email and ship in-app only.
>   (2) Add SMTP/provider integration now.
> Recommendation: option (1) for MVP to keep notification delivery deterministic and infra-light.
> Reply: 1
> Blocked [Low]: email remains deferred by choice, and in-app notification producer is still blocked by the identity/trust-model decision above.
> Options:
>   (1) Keep this deferred until the first two notification blockers are resolved.
>   (2) Implement email queue scaffolding now without active producers.
> Recommendation: option (1) to avoid unused delivery plumbing.
> Reply: _______
