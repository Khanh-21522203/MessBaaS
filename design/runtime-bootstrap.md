## Runtime Bootstrap and Wiring

### Purpose

Boot the MessBaaS process, load runtime configuration, initialize database access/migrations, wire services/handlers, and start the Netty server.

### Scope

**In scope:**
- Load `application.properties` from classpath via `AppConfigLoader.load`.
- Build `HikariDataSource` from `AppConfig`.
- Run Flyway migrations before serving traffic.
- Construct repositories, services, HTTP router/handler, WebSocket handlers, and `NettyServer`.
- Configure Netty boss/worker/business thread groups and HTTP/WebSocket pipeline.
- Define local runtime bootstrap contract through `build.gradle` (`application.mainClass`) and `docker-compose.yaml` MySQL service bootstrap.

**Out of scope:**
- Endpoint-specific request validation and response mapping.
- SQL statement behavior inside repository classes.
- Message caching and fanout semantics.

### Primary User Flow

1. Operator starts local MySQL with `docker-compose.yaml` (database name seeded by `docker/mysql/init.sql`).
2. Operator runs `./gradlew run` (main class `com.java_mess.java_mess.MessBaaSServer` from `build.gradle`).
3. Server reads static config from `src/main/resources/application.properties`.
4. Process creates DB pool, applies Flyway migrations, wires app components.
5. Netty binds to configured `server.port` and accepts HTTP + WebSocket connections.

### System Flow

1. Entry point: `src/main/java/com/java_mess/java_mess/MessBaaSServer.java:main`.
2. `AppConfigLoader.load` reads `application.properties` and calls `AppConfig.from`.
3. `createDataSource` builds `HikariConfig` (`db.url`, `db.username`, `db.password`, `db.driverClassName`) and returns `HikariDataSource`. Pool sizing is derived from business thread count: `maximumPoolSize = resolveBusinessThreads * 2`, `minimumIdle = max(2, resolveBusinessThreads)`, `autoCommit = true`.
4. `runMigrations` executes `Flyway.configure().dataSource(...).migrate()`.
5. Main wires repositories (`UserRepository`, `ChannelRepository`, `MessageRepository`, `ChannelMemberRepository`, `UserReadMessageRepository`), services, router/handlers, then constructs `NettyServer`.
6. `NettyServer.start` configures pipeline: `HttpServerCodec` -> `HttpObjectAggregator` -> `ChunkedWriteHandler` -> `HttpApiHandler` (business group) -> `WebSocketHandshakeHandler` (business group) -> `WebSocketServerProtocolHandler("/ws/channels")` -> `ChannelWebSocketFrameHandler` (business group).
7. `build.gradle` `application.mainClass` binds process launch to `MessBaaSServer` for `./gradlew run`.
8. Shutdown hook closes datasource when JVM exits.

```
Process start
  -> MessBaaSServer.main
      -> AppConfigLoader.load
      -> HikariDataSource + Flyway migrate
      -> repositories/services/handlers wiring
      -> NettyServer.start
          -> bind(port)
          -> serve HTTP + WebSocket traffic
```

### Data Model

- `AppConfig` (`src/main/java/com/java_mess/java_mess/config/AppConfig.java`) fields:
- `port (int)` default `8082`
- `bossThreads (int)` default `1`
- `workerThreads (int)` default `0` (resolved to `availableProcessors * 2` in `NettyServer`)
- `businessThreads (int)` default `0` (resolved to at least `4`)
- `dbUrl (String)`, `dbUsername (String)`, `dbPassword (String)`, `dbDriverClassName (String)` are required
- `hotBufferPerChannel (int)` default `2048`
- Persistence for this feature is indirect: config values drive runtime wiring; no table owned by this feature.

### Interfaces and Contracts

- Process interface: JVM main `MessBaaSServer`.
- Runtime contract: startup fails fast with `IllegalStateException` when required config keys are missing.
- Transport contract: Netty pipeline serves HTTP and WebSocket on one port configured by `server.port`.

### Dependencies

**Internal modules:**
- `config/AppConfig*` for configuration loading and validation.
- `http/*` and `websocket/*` handlers for transport behavior.
- `repository/*` and `service/*` for business + persistence wiring.
- Root runtime config files: `build.gradle`, `settings.gradle`, `docker-compose.yaml`, `docker/mysql/init.sql`.

**External services/libraries:**
- HikariCP (`com.zaxxer.hikari`) for JDBC connection pooling.
- Flyway (`org.flywaydb`) for schema migration.
- Netty (`io.netty`) for server runtime and protocol handling.
- MySQL JDBC driver for DB connectivity.

### Failure Modes and Edge Cases

- Missing classpath config file: `AppConfigLoader` throws `IllegalStateException("application.properties not found on classpath")`.
- Missing required DB property: `AppConfig.requiredProperty` throws `IllegalStateException("Missing required config: ...")`.
- DB unavailable or migration failure: startup fails before `NettyServer.start` binds port.
- If `businessThreads <= 0` in config, runtime auto-scales thread count; there is no warning log for this fallback.

### Observability and Debugging

- Startup log at `NettyServer.start`: `Starting Netty server port=... workerThreads=... businessThreads=...`.
- Shutdown log at JVM hook in `MessBaaSServer`: `Shutting down MessBaaS`.
- Operational probes are available from startup: `GET /healthz`, `GET /readyz`, plus `/health/live` and `/health/ready`.

### Risks and Notes

- Startup is all-or-nothing; partial initialization is not supported.
- Thread counts are runtime-derived when config is zero, which can vary across hosts.
- `docker/mysql/init.sql` only creates the database; schema creation still depends on Flyway execution during app startup.

Changes:

> Suggested [Impact: High] [Effort: L]: Add Redis + async projection runtime bootstrap so MessBaaS starts in MySQL-authoritative mode, enables Redis acceleration when available, and continues serving in a clearly-defined degraded mode when Redis/projector workers are unhealthy.
> Source: user request — design/update.md
> Approach: extend `src/main/java/com/java_mess/java_mess/config/AppConfig.java` and `src/main/java/com/java_mess/java_mess/config/AppConfigLoader.java` with Redis/worker config (`redis.*`, projection retry/backoff, inbox hot-window limits); in `src/main/java/com/java_mess/java_mess/MessBaaSServer.java` wire Redis client lifecycle, worker executors, and projection pipeline dependencies; keep `ApiRouter` readiness logic MySQL-gated while exposing Redis/worker status as degraded operational signals instead of hard startup failure.
> Builds on: existing startup wiring in `MessBaaSServer`, current health/readiness endpoints, and existing hot-buffer configuration contract.
> Constraints: MySQL must remain source of truth; reliability-first behavior must keep send/history available even when Redis or workers are down.
> Edge cases: Redis unavailable at startup, Redis reconnect churn after startup, projection worker crash-loop, optional Redis config omitted.
> Risk: if readiness semantics are ambiguous, deployments may accidentally treat Redis as mandatory and cause avoidable outages.
