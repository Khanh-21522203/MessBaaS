## Runtime Bootstrap and Wiring

### Purpose

Boot MessBaaS with MySQL as the authoritative store, optionally attach Redis acceleration, start async projection workers, and expose HTTP/WebSocket surfaces through Netty.

### Scope

**In scope:**
- Load `application.properties` into `AppConfig`.
- Initialize Hikari datasource and run Flyway migrations.
- Wire repositories/services/handlers, including outbox projection worker and inbox service.
- Optionally create Redis client (`JedisPooled`) and continue in degraded mode when Redis is unavailable.
- Enable Redis-backed distributed websocket fanout when `deployment.mode != single-node`.
- Start incremental projection reconciliation worker with persisted checkpoint.
- Start Netty pipeline and register shutdown hooks.

**Out of scope:**
- Endpoint-level validation and status-code mapping.
- SQL query internals inside repository methods.
- Detailed projection business rules.

### Primary User Flow

1. Operator starts MySQL (and optionally Redis).
2. Operator runs `./gradlew run`.
3. Server loads config, runs migrations, wires services, and starts projection + reconciliation workers.
4. Netty binds to `server.port` and serves HTTP + WebSocket traffic.
5. On shutdown, projection/reconciliation workers, websocket fanout subscriber, Redis client, and datasource are closed.

### System Flow

1. Entry point: `src/main/java/com/java_mess/java_mess/MessBaaSServer.java:main`.
2. `AppConfigLoader.load` parses server/db/message/redis/projection/inbox settings.
3. `createDataSource` builds Hikari pool and `runMigrations` executes Flyway (`V1..V6`).
4. Runtime wiring creates:
- `MessageOutboxRepository`, `MessageRepository`, membership/read-state repositories.
- `ProjectionCacheStore` (Redis-backed when available, local fallback otherwise).
- `MessageProjectionProcessor` + `AsyncProjectionWorker`.
- `ProjectionReconcileStateRepository` + `ProjectionReconcileWorker`.
- `MessageServiceImpl`, `ReadStateServiceImpl`, `InboxServiceImpl`, router/handlers.
5. `AsyncProjectionWorker.start()` begins polling outbox events.
6. `ProjectionReconcileWorker.start()` begins periodic bounded repair scans from SQL.
7. In multi-node mode with Redis available, `ChannelWebSocketRegistry.startDistributedFanout(...)` starts pub/sub subscription loop.
8. `NettyServer.start` builds pipeline: `HttpApiHandler` -> `WebSocketHandshakeHandler` -> `WebSocketServerProtocolHandler` -> `ChannelWebSocketFrameHandler`.
9. Shutdown hook closes workers, websocket registry fanout, Redis client (if present), and datasource.

### Data Model

- `AppConfig` now includes:
- Core runtime: `port`, `bossThreads`, `workerThreads`, `businessThreads`.
- DB: `dbUrl`, `dbUsername`, `dbPassword`, `dbDriverClassName`.
- Message cache: `hotBufferPerChannel`.
- Deployment/cache mode: `deploymentMode`, `localProjectionCacheEnabled`.
- Redis: `redisEnabled`, `redisHost`, `redisPort`, `redisTimeoutMillis`.
- Projection worker: `projectionPollMillis`, `projectionBatchSize`, `projectionMaxAttempts`, `projectionBaseBackoffMillis`, `projectionLeaseMillis`.
- Reconciliation knobs: `projectionReconcileBatchSize`, `projectionReconcileIntervalSeconds` (active background worker controls).
- Inbox API: `inboxDefaultLimit`, `inboxMaxLimit`.

### Interfaces and Contracts

- JVM process entry: `MessBaaSServer.main`.
- Startup contract: required DB config is fail-fast; Redis failure is non-fatal degraded mode.
- Projection contract: workers start automatically in-process (`AsyncProjectionWorker` + `ProjectionReconcileWorker`).

### Dependencies

**Internal modules:**
- `config/*`, `repository/*`, `service/*`, `http/*`, `websocket/*`, `server/NettyServer`.

**External services/libraries:**
- MySQL + Flyway (`org.flywaydb`) for schema and authoritative persistence.
- HikariCP for connection pooling.
- Netty for transport.
- Jedis for Redis cache acceleration.

### Failure Modes and Edge Cases

- Missing required DB config or migration failure: startup aborts.
- Redis enabled but unavailable: logged warning, server continues in degraded cache mode.
- Projection worker errors: retries are handled by outbox status transitions; process remains running.

### Observability and Debugging

- Startup/shutdown logs from `MessBaaSServer` and `NettyServer`.
- Startup logs include deployment mode and projection cache mode.
- Projection worker startup log (`pollMillis`, `batchSize`).
- Runtime stats endpoint: `GET /api/ops/stats`.
- Runtime stats includes reconciliation block when worker is wired.
- Health endpoints: `/health/live`, `/health/ready`, `/healthz`, `/readyz`.

### Risks and Notes

- Projection worker is single-threaded; backlog can grow under sustained spikes.
- Reconciliation worker is single-threaded; large historical backlog may take multiple intervals to catch up.
- Redis connectivity flaps increase fallback load on MySQL.
- Startup remains all-or-nothing for DB, but intentionally not for Redis.

Changes:
