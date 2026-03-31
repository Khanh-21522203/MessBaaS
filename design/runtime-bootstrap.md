## Runtime Bootstrap and Wiring

### Purpose

Boot MessBaaS with MySQL as the authoritative store, optionally attach Redis acceleration, start async projection workers, and expose HTTP/WebSocket surfaces through Netty.

### Scope

**In scope:**
- Load `application.properties` into `AppConfig`.
- Initialize Hikari datasource and run Flyway migrations.
- Wire repositories/services/handlers, including outbox projection worker and inbox service.
- Optionally create Redis client (`JedisPooled`) and continue in degraded mode when Redis is unavailable.
- Start Netty pipeline and register shutdown hooks.

**Out of scope:**
- Endpoint-level validation and status-code mapping.
- SQL query internals inside repository methods.
- Detailed projection business rules.

### Primary User Flow

1. Operator starts MySQL (and optionally Redis).
2. Operator runs `./gradlew run`.
3. Server loads config, runs migrations, wires services, and starts projection worker.
4. Netty binds to `server.port` and serves HTTP + WebSocket traffic.
5. On shutdown, projection worker, Redis client, and datasource are closed.

### System Flow

1. Entry point: `src/main/java/com/java_mess/java_mess/MessBaaSServer.java:main`.
2. `AppConfigLoader.load` parses server/db/message/redis/projection/inbox settings.
3. `createDataSource` builds Hikari pool and `runMigrations` executes Flyway (`V1..V5`).
4. Runtime wiring creates:
- `MessageOutboxRepository`, `MessageRepository`, membership/read-state repositories.
- `ProjectionCacheStore` (Redis-backed when available, local fallback otherwise).
- `MessageProjectionProcessor` + `AsyncProjectionWorker`.
- `MessageServiceImpl`, `ReadStateServiceImpl`, `InboxServiceImpl`, router/handlers.
5. `AsyncProjectionWorker.start()` begins polling outbox events.
6. `NettyServer.start` builds pipeline: `HttpApiHandler` -> `WebSocketHandshakeHandler` -> `WebSocketServerProtocolHandler` -> `ChannelWebSocketFrameHandler`.
7. Shutdown hook closes worker, Redis client (if present), and datasource.

### Data Model

- `AppConfig` now includes:
- Core runtime: `port`, `bossThreads`, `workerThreads`, `businessThreads`.
- DB: `dbUrl`, `dbUsername`, `dbPassword`, `dbDriverClassName`.
- Message cache: `hotBufferPerChannel`.
- Redis: `redisEnabled`, `redisHost`, `redisPort`, `redisTimeoutMillis`.
- Projection worker: `projectionPollMillis`, `projectionBatchSize`, `projectionMaxAttempts`, `projectionBaseBackoffMillis`, `projectionLeaseMillis`.
- Inbox API: `inboxDefaultLimit`, `inboxMaxLimit`.

### Interfaces and Contracts

- JVM process entry: `MessBaaSServer.main`.
- Startup contract: required DB config is fail-fast; Redis failure is non-fatal degraded mode.
- Projection contract: worker starts automatically in-process and consumes DB outbox events.

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
- Projection worker startup log (`pollMillis`, `batchSize`).
- Runtime stats endpoint: `GET /api/ops/stats`.
- Health endpoints: `/health/live`, `/health/ready`, `/healthz`, `/readyz`.

### Risks and Notes

- Projection worker is single-threaded; backlog can grow under sustained spikes.
- Redis connectivity flaps increase fallback load on MySQL.
- Startup remains all-or-nothing for DB, but intentionally not for Redis.

Changes:

