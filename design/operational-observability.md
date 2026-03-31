## Operational Health and Runtime Stats

### Purpose

Expose operational readiness and low-latency/reliability signals for the asynchronous projection architecture.

### Scope

**In scope:**
- Health probes (`/health/live`, `/healthz`, `/health/ready`, `/readyz`).
- Runtime metrics endpoint (`/api/ops/stats`).
- Message read/send latency snapshots, projection queue health, cache health, inbox read stats, websocket broadcast counters.

**Out of scope:**
- External metrics backend (Prometheus/Otel).
- Distributed tracing.

### Primary User Flow

1. Platform checks liveness/readiness endpoints.
2. Operator inspects `/api/ops/stats` during latency or consistency incidents.
3. Operator uses backlog/retry/dead-letter and cache health signals to tune projection behavior.

### System Flow

1. `ApiRouter.readyHealth` performs DB probe (`select 1`) and reports runtime thread sizing.
2. `ApiRouter.runtimeStats` returns:
- `message` (`MessageRuntimeStats`),
- `inbox` (`InboxRuntimeStats`),
- `websocket` (`WebSocketRegistryStats`),
- `runtime` thread config summary.
3. `MessageRuntimeStats` includes:
- hot/db read-path counters,
- send/history latency p95/p99,
- projection worker stats (`pendingBacklog`, `retry`, `deadLetter`, lag snapshot),
- projection cache stats (Redis enabled/available/errors/fallback reads).

### Data Model

- In-memory counters and latency sample windows (`LatencyTracker`).
- Outbox status counts read from MySQL through `MessageOutboxRepository`.

### Interfaces and Contracts

- `GET /health/live`, `GET /healthz` => `200 {"status":"UP"}`.
- `GET /health/ready`, `GET /readyz` => `200/503` based on DB readiness.
- `GET /api/ops/stats` => aggregated JSON runtime snapshot.

### Dependencies

**Internal modules:**
- `http/ApiRouter`
- `service/MessageServiceImpl`, `InboxServiceImpl`, `AsyncProjectionWorker`
- `service/ProjectionCacheStore`
- `websocket/ChannelWebSocketRegistry`

**External services/libraries:**
- MySQL (for outbox status counts).

### Failure Modes and Edge Cases

- Stats queries can fail if DB is unavailable (same readiness root cause).
- In-memory latency windows reset on process restart.
- Redis health is runtime-checked and may fluctuate.

### Observability and Debugging

- Use `message.projection.pendingBacklog/retry/deadLetter` to identify projection pressure.
- Use `message.projectionCache.redisErrors` and `localFallbackReads` to diagnose cache degradation.
- Use `inbox.cacheHit/dbFallback` to assess projection effectiveness.

### Risks and Notes

- Percentiles are sampled from in-memory windows, not full-historical telemetry.
- No persistent metrics storage is included in this iteration.

Changes:

