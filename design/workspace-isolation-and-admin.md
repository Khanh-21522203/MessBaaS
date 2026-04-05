## Workspace Isolation and Admin Controls

### Purpose

Add first-class workspace boundaries so channels, members, and message visibility are isolated per team, with admin controls for membership lifecycle.

### Scope

**In scope:**
- Workspace create/get/list APIs.
- Workspace membership with role (`ADMIN`, `MEMBER`).
- Workspace-scoped channel ownership and queries.
- Workspace-level authorization checks before channel/message operations.

**Out of scope:**
- Org-wide SSO and SCIM provisioning.
- Cross-workspace shared channels.
- Fine-grained role matrices beyond admin/member.

### Primary User Flow

1. Admin creates a workspace.
2. Admin adds users to workspace and assigns roles.
3. Members create/list channels only inside their workspace.
4. Message and WebSocket operations are rejected when caller is outside the workspace.

### System Flow

1. `ApiRouter` adds workspace routes and passes requests to `WorkspaceService` and `WorkspaceMembershipService`.
2. `ChannelServiceImpl.createChannel` and `ChannelRepository.listChannels` become workspace-scoped.
3. `ChannelMembershipServiceImpl.assertMember` first validates workspace membership, then channel membership.
4. `WebSocketHandshakeHandler` validates that `channelId` belongs to the caller's workspace before upgrading.

### Data Model

- New table: `workspace(id, name, createdAt, updatedAt)`.
- New table: `workspaceMember(id, workspaceId, userId, role, createdAt, updatedAt)` with unique `(workspaceId, userId)`.
- `channel` table adds `workspaceId` FK and unique `(workspaceId, clientReferenceId)`.
- Optional indexes:
- `workspaceMember(userId, workspaceId)` for membership checks.
- `channel(workspaceId, createdAt desc)` for channel discovery.

### Interfaces and Contracts

- `POST /api/workspaces`
- `GET /api/workspaces?clientUserId=...`
- `POST /api/workspaces/{workspaceId}/members`
- `PATCH /api/workspaces/{workspaceId}/members/{clientUserId}`
- Existing channel routes become workspace-scoped (path or required query param).

### Dependencies

**Internal modules:**
- `http/ApiRouter`
- `service/ChannelServiceImpl`
- `service/ChannelMembershipServiceImpl`
- `repository/ChannelRepository`
- `repository/ChannelMemberRepository`

**External services/libraries:**
- Existing MySQL/Flyway stack.

### Failure Modes and Edge Cases

- User added twice to same workspace => `409`.
- Non-admin tries membership mutation => `403`.
- Channel lookup across workspace boundary => `404` or `403` (pick one and keep contract stable).
- Existing channels without workspace assignment during migration => backfill path required.

### Observability and Debugging

- Add counters in `/api/ops/stats` for workspace auth denials and admin mutations.
- Log workspace ID and caller user ID on role-change operations.

### Risks and Notes

- This is a foundational migration that touches many route signatures.
- Rollout should include compatibility mode for existing channel endpoints during transition.

Changes:
> Suggested [Priority: High | Impact: High | Feasibility: Medium]: add workspace isolation as a foundational boundary before DM/thread/file features. Implement Flyway migration for `workspace`, `workspaceMember`, and `channel.workspaceId`; backfill existing channels into a default workspace.
> Blocked [High]: this requires choosing identity and authorization authority first. Current runtime has no authentication context and all routes trust caller-supplied `clientUserId` (`http/ApiRouter`, `service/ChannelMembershipServiceImpl`), so workspace role enforcement is not safe yet.
> Options:
>   (1) Soft partition only: add `workspaceId` columns and route parameters but keep caller-supplied identity. Fastest migration, but no secure admin/member enforcement.
>   (2) Secure workspace model: implement auth identity first (JWT/session), then workspace membership/role checks and channel scoping. More work, but matches intended isolation and admin control.
> Recommendation: option (2), because workspace isolation without trusted identity creates a false security boundary.
> If you confirm option (2), implementation order is clear: auth boundary -> workspace schema -> workspace-scoped channel/message routes.
> Reply: 2
> Blocked [High]: auth design artifact was removed (`design/jwt-authentication-and-session.md` no longer exists), so option (2) has no active implementation contract for identity issuance/verification. Implementing workspace isolation now would either silently downgrade to spoofable `clientUserId` authz or require re-defining auth from scratch in this thread.
> Options:
>   (1) Re-open an auth design file and implement token/session identity first, then proceed with secure workspace isolation.
>   (2) Switch this workspace item to soft partitioning (former option 1) and explicitly accept non-secure caller-claimed identity for MVP.
> Recommendation: option (2) only if this environment is trusted/internal and security is explicitly out of MVP scope; otherwise choose (1).
> Reply: _______
> Suggested: introduce `WorkspaceService` + `WorkspaceMembershipService`, then update `ApiRouter`, `ChannelServiceImpl`, and `ChannelMembershipServiceImpl` so every channel/message operation resolves through workspace membership checks.
> Blocked [High]: depends on the unresolved identity boundary in the blocker above; service-level workspace authz cannot be trusted until caller identity is server-derived.
> Options:
>   (1) Build services now with caller-supplied identity (fast but insecure).
>   (2) Implement after JWT identity is in place (secure and consistent).
> Recommendation: option (2) to avoid shipping access-control code that can be bypassed.
> Reply: 2
> Blocked [High]: same dependency remains unresolved because auth design/contract is absent in this cycle. Service wiring cannot be completed safely without either (a) server-derived identity or (b) explicit acceptance of spoofable identity.
> Options:
>   (1) Pause this item until auth contract is restored and implemented.
>   (2) Downgrade to insecure MVP mode with caller-claimed identity and document risk.
> Recommendation: option (1) for correctness; choose (2) only for short-lived internal demos.
> Reply: _______
> Suggested: keep contract migration safe by supporting legacy channel endpoints temporarily, but require `workspaceId` in all new endpoints and WebSocket handshake validation.
> Blocked [Med]: requires a compatibility-window decision for legacy routes.
> Options:
>   (1) Single release with dual routes + deprecation log warnings.
>   (2) Immediate hard cutover (remove legacy routes now).
> Recommendation: option (1) to avoid breaking existing clients and websocket handshake flows abruptly.
> Reply: 1
> Blocked [Med]: endpoint migration cannot start until the parent workspace identity model is chosen (secure auth-first vs soft partition). Route shape depends on that decision and would be reworked otherwise.
> Options:
>   (1) Defer route migration until workspace identity model is finalized.
>   (2) Proceed now with soft-partition route changes and no trusted auth boundary.
> Recommendation: option (1) to avoid churn; if you want immediate route changes, explicitly confirm option (2) from the first blocker.
> Reply: _______
