## Direct Message Conversations

### Purpose

Add private one-to-one conversations between workspace members while reusing existing message durability, outbox projection, inbox, and unread infrastructure.

### Scope

**In scope:**
- Create/find DM conversation between two users in same workspace.
- Send/list DM messages.
- Reuse idempotent send and async projection pipeline.
- DM entries in inbox and unread counts.

**Out of scope:**
- Group DMs.
- Message edit/delete.
- End-to-end encryption.

### Primary User Flow

1. User opens DM list in a workspace.
2. User starts DM with another workspace member.
3. Either side sends messages and receives real-time updates.
4. Inbox/unread reflects DM activity.

### System Flow

1. `ApiRouter` adds DM routes and validates both users belong to same workspace.
2. `DmService` finds-or-creates conversation by normalized pair (`min(userId), max(userId)`).
3. Send path reuses `MessageServiceImpl` persistence/outbox semantics with DM target metadata.
4. `MessageProjectionProcessor` updates inbox/unread for both DM participants and emits DM websocket events.

### Data Model

- New table: `dmConversation(id, workspaceId, userLowId, userHighId, createdAt, updatedAt)` with unique `(workspaceId, userLowId, userHighId)`.
- `message` table adds nullable `dmConversationId` FK.
- Constraint: exactly one target is set per message (`channelId` xor `dmConversationId`).
- Indexes:
- `message(dmConversationId, id)` for DM history windows.
- `dmConversation(workspaceId, userLowId, userHighId)` unique lookup.

### Interfaces and Contracts

- `POST /api/workspaces/{workspaceId}/dms`
- `GET /api/workspaces/{workspaceId}/dms?clientUserId=...`
- `POST /api/dms/{dmId}/messages`
- `GET /api/dms/{dmId}/messages?clientUserId=&pivotId=&prevLimit=&nextLimit=`

### Dependencies

**Internal modules:**
- `http/ApiRouter`
- `service/MessageServiceImpl`
- `service/MessageProjectionProcessor`
- `service/InboxServiceImpl`
- `repository/MessageRepository`
- `repository/ChannelMemberRepository` (for shared membership checks)

**External services/libraries:**
- Existing MySQL/Flyway and optional Redis cache.

### Failure Modes and Edge Cases

- Attempt DM across workspace boundary => `403`.
- Self-DM create policy should be explicit (`400` or allow).
- Duplicate DM create race => returns existing conversation instead of duplicate.
- DM unread drift during projector lag => temporary eventual consistency.

### Observability and Debugging

- Add DM send/read counters and latency snapshots to runtime stats.
- Track DM projection backlog separately from channel messages if outbox mix grows.

### Risks and Notes

- Reusing the same `message` table keeps architecture compact but requires careful migration and query filtering.

Changes:
> Suggested [Priority: High | Impact: High | Feasibility: Medium]: implement one-to-one DM conversations using a new `dmConversation` table and extend `message` targeting to support `dmConversationId`.
> Blocked [High]: message-target modeling needs a schema decision before implementation in `db/migration/*`, `repository/MessageRepository`, and `service/MessageServiceImpl`.
> Options:
>   (1) Dual-target in existing `message` table (`channelId` nullable + `dmConversationId` nullable with XOR constraint). Reuses outbox/read paths but requires invasive migration and wide query branching.
>   (2) Separate `dmMessage` table + dedicated repository while reusing outbox projector abstractions. Cleaner isolation, less risk to channel read paths, but more code paths to maintain.
> Recommendation: option (2) for this codebase to avoid destabilizing mature channel history logic while DM behavior is introduced.
> If you confirm option (2), the remaining decisions are straightforward: add `dmConversation` + `dmMessage` tables and map DM routes to a dedicated service.
> Reply: 2
> Blocked [High]: private DM authz semantics are currently undefined after auth-doc removal, and implementing DM now would rely on caller-claimed `clientUserId` for privacy checks. This creates spoofable private-message reads/writes in `ApiRouter` and DM services.
> Options:
>   (1) Restore an authentication contract first and then implement DM as private identity-bound conversations.
>   (2) Proceed with DM in insecure internal mode (trust caller-provided `clientUserId`) and document that privacy is best-effort only.
> Recommendation: option (1) for any environment where DM privacy matters.
> Reply: _______
> Suggested: reuse existing `MessageRepository.save` + outbox worker path to avoid duplicate delivery pipelines; only add DM-specific authz checks and routes in `ApiRouter`/`MessageServiceImpl`.
> Blocked [Med]: depends on the storage model decision above.
> Options:
>   (1) If you choose dual-target `message` rows, reuse `MessageRepository.save` directly.
>   (2) If you choose dedicated `dmMessage`, create `DmMessageRepository` and reuse outbox processor contracts only.
> Recommendation: option (2) (dedicated repository) with shared projector contracts to reduce channel-path regression risk.
> Reply: 2
> Blocked [Med]: even with storage model chosen, outbox contract currently only supports channel-scoped events (`channelId` non-null assumptions in projector/websocket fanout). DM event shape extension must be decided first to avoid breaking existing channel projection paths.
> Options:
>   (1) Extend existing outbox schema/processor to polymorphic targets (`channel` vs `dm`) now.
>   (2) Add a separate DM outbox table/worker path and keep channel outbox untouched.
> Recommendation: option (2) for safer incremental rollout in this codebase.
> Reply: _______
> Suggested: update inbox projection logic in `MessageProjectionProcessor` and `InboxServiceImpl` so DM activity appears beside channels with consistent unread behavior.
> Blocked [Low]: depends on deciding inbox ordering policy for mixed channel/DM streams.
> Options:
>   (1) Single unified inbox ordered by `lastMessageId`.
>   (2) Separate channel and DM inbox sections.
> Recommendation: option (1) for MVP simplicity and minimal API surface changes.
> Reply: 1
> Blocked [Low]: inbox projection merge for DM cannot be implemented until DM event storage/outbox route is finalized in the blocker above.
> Options:
>   (1) Defer inbox DM merge until DM write path exists.
>   (2) Pre-create inbox schema placeholders without producer wiring.
> Recommendation: option (1); placeholder-only changes add dead fields without value.
> Reply: _______
