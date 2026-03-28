## Channel HTTP API

### Purpose

Create chat channels and resolve channel records by client reference ID or canonical channel ID.

### Scope

**In scope:**
- `POST /api/channels` channel creation.
- `GET /api/channels/{clientReferenceId}/by-reference-id` channel lookup by external ID.
- `GET /api/channels/{channelId}` channel lookup by persisted channel UUID.

**Out of scope:**
- Channel membership and permissions.
- Channel update/delete.
- Message send/read behavior.

### Primary User Flow

1. Client creates a channel with `name` and `clientReferenceId`.
2. Service rejects duplicate `clientReferenceId` values.
3. Service persists channel and returns UUID-backed channel object.
4. Client resolves channel either by external `clientReferenceId` or by channel UUID.

### System Flow

1. Entry point: `HttpApiHandler.channelRead0` -> `ApiRouter.route`.
2. `ApiRouter.createChannel` parses `CreateChannelRequest`, validates required fields.
3. `ChannelServiceImpl.createChannel` checks duplicate via `ChannelRepository.findByClientReferenceId`.
4. `ChannelServiceImpl` builds `Channel(createdAt=Instant.now())` and saves via `ChannelRepository.save`.
5. GET-by-reference path uses `ChannelServiceImpl.getChannelByReferenceId`.
6. GET-by-id path uses `ChannelServiceImpl.getChannelById`.
7. `ApiRouter` serializes `CreateChannelResponse` / `GetChannelResponse`.

### Data Model

- `CreateChannelRequest` fields: `name (String)`, `clientReferenceId (String)`.
- `Channel` fields: `id (String UUID)`, `name (String)`, `clientReferenceId (String)`, `createdAt (Instant)`.
- Table `channel` (`V1__init.sql`):
- `id VARCHAR(36) PRIMARY KEY`
- `name VARCHAR(255) NOT NULL`
- `clientReferenceId VARCHAR(255) NOT NULL UNIQUE`
- `createdAt TIMESTAMP DEFAULT CURRENT_TIMESTAMP`
- Invariant: `clientReferenceId` uniquely identifies one channel.

### Interfaces and Contracts

- `POST /api/channels`
- Body: `{"name":string,"clientReferenceId":string}`
- Success: `200` with `{"channel":{...}}`
- Errors: `400` invalid body/blank fields, `409` duplicate `clientReferenceId`.
- `GET /api/channels/{clientReferenceId}/by-reference-id`
- Success: `200` with `{"channel":{...}}`
- Errors: `404` if not found.
- `GET /api/channels/{channelId}`
- Success: `200` with `{"channel":{...}}`
- Errors: `404` if not found.

### Dependencies

**Internal modules:**
- `http/ApiRouter`, `service/ChannelServiceImpl`, `repository/ChannelRepository`.

**External services/libraries:**
- Jackson for JSON mapping.
- MySQL JDBC via `DataSource`.

### Failure Modes and Edge Cases

- Blank `name` or `clientReferenceId`: `400` from `RequestValidator`.
- Duplicate channel reference: `ChannelExistedException` -> `409`.
- Missing channel on either GET route: `ChannelNotFoundException` -> `404`.
- Repository SQL errors surface as `IllegalStateException` -> `500`.

### Observability and Debugging

- `ChannelServiceImpl` logs create and lookup operations at INFO.
- Failed requests logged in `ApiRouter.route` at WARN.
- No endpoint-level metrics; check DB row and `ApiRouter` warning logs when debugging.

### Risks and Notes

- No auth or tenant partitioning; all callers see the same channel namespace.
- Duplicate prevention is done in both service pre-check and DB unique key; concurrent create races rely on DB constraint.
- API does not expose list/search channels.

Changes:

