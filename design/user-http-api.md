## User HTTP API

### Purpose

Create users and fetch users by external client identifier through Netty HTTP routes.

### Scope

**In scope:**
- `POST /api/users` user creation.
- `GET /api/users/{clientUserId}/by-client-user-id` user lookup.
- Request field validation and HTTP error mapping for user operations.

**Out of scope:**
- Authentication or authorization.
- User update/delete operations.
- WebSocket behavior.

### Primary User Flow

1. Client sends `POST /api/users` with `name`, `clientUserId`, `profileImgUrl`.
2. API validates required fields and checks uniqueness by `clientUserId`.
3. API persists user row in MySQL and returns JSON user payload.
4. Client can later fetch the same record via `GET /api/users/{clientUserId}/by-client-user-id`.

### System Flow

1. Entry point: `src/main/java/com/java_mess/java_mess/http/HttpApiHandler.java:channelRead0`.
2. `HttpApiHandler` forwards non-WebSocket requests to `ApiRouter.route`.
3. `ApiRouter.createUser` parses JSON body into `CreateUserRequest` and validates fields with `RequestValidator.requireNonBlank`.
4. `UserServiceImpl.createUser` checks duplicate via `UserRepository.findByClientUserId`, then builds `User`.
5. `UserRepository.save` inserts into table ``user`` and sets generated UUID + created time on model.
6. `ApiRouter` wraps response in `CreateUserResponse` and serializes JSON.
7. `ApiRouter.getUser` fetches with `UserServiceImpl.getUserByClientId` and returns `GetUserResponse`.

### Data Model

- `CreateUserRequest` fields: `name (String)`, `clientUserId (String)`, `profileImgUrl (String)`.
- `User` fields: `id (String UUID)`, `clientUserId (String)`, `name (String)`, `profileImgUrl (String)`, `createdAt (Instant)`.
- Table ``user`` (`src/main/resources/db/migration/V1__init.sql`):
- `id VARCHAR(36) PRIMARY KEY`
- `clientUserId VARCHAR(255) NOT NULL UNIQUE`
- `name VARCHAR(255)`
- `profileImgUrl TEXT`
- `createdAt TIMESTAMP DEFAULT CURRENT_TIMESTAMP`
- Persistence invariant: service enforces uniqueness pre-check and DB unique constraint enforces final guarantee.

### Interfaces and Contracts

- `POST /api/users`
- Body: `{"name":string,"clientUserId":string,"profileImgUrl":string}`
- Success: `200` with `{"user":{...}}`
- Errors: `400` invalid JSON/blank field, `409` existing `clientUserId`, `500` unexpected failure.
- `GET /api/users/{clientUserId}/by-client-user-id`
- Success: `200` with `{"user":{...}}`
- Errors: `404` when user not found.

### Dependencies

**Internal modules:**
- `http/ApiRouter`, `http/RequestValidator`, `service/UserServiceImpl`, `repository/UserRepository`.

**External services/libraries:**
- Jackson `ObjectMapper` for JSON decode/encode.
- MySQL via JDBC `DataSource`.

### Failure Modes and Edge Cases

- Empty request body: `ApiRouter.readBody` throws `IllegalArgumentException("Request body is required")` -> `400`.
- Missing/blank required fields: `RequestValidator` throws `IllegalArgumentException("<field> is required")` -> `400`.
- Duplicate `clientUserId`: `ClientUserIdExistedException` -> `409`.
- Unknown user on GET: `UserNotFoundException` -> `404`.
- SQL exception in repository is wrapped as `IllegalStateException` -> `500`.

### Observability and Debugging

- `UserServiceImpl.createUser` logs request and duplicate-check result at INFO.
- `ApiRouter.route` logs failed user requests at WARN with path + method + stacktrace.
- No metrics or request IDs; debugging starts at `ApiRouter.route` catch block.

### Risks and Notes

- No auth; any caller can create/read users.
- No server-side normalization beyond blank checks (e.g., whitespace trimming rules are not enforced uniformly).
- Validation requires `profileImgUrl`, so text-only profiles are not accepted.

Changes:

