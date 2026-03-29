## Operational Health and Runtime Stats

### Purpose

Expose minimal operational probes and runtime counters so operators can verify service readiness and tune hot-buffer behavior.

### Scope

**In scope:**
- Add health endpoints for liveness/readiness.
- Expose in-process counters for hot-buffer usage and WebSocket connections.
- Keep implementation dependency-light (no new metrics backend required for first iteration).

**Out of scope:**
- Full tracing stack.
- Prometheus/OpenTelemetry rollout.
- Distributed-system SLO tooling.

### Primary User Flow

1. Deployment platform checks liveness/readiness endpoints.
2. Operator inspects runtime stats endpoint when debugging latency/history behavior.
3. Operator tunes `message.hotBufferPerChannel` using observed hit/miss/eviction patterns.

### System Flow

1. `ApiRouter` handles liveness/readiness aliases: `/health/live` and `/healthz`; `/health/ready` and `/readyz`.
2. Readiness runs a lightweight DB check (for example `select 1`) through existing datasource wiring.
3. `MessageServiceImpl` and `ChannelMessageHotStore` increment counters for cache hit/miss/fallback.
4. `ChannelWebSocketRegistry` tracks active connections and per-channel group counts.
5. `/api/ops/stats` returns aggregated JSON counters.

### Data Model

- No persistent schema required.
- In-memory counters can use `AtomicLong` and `ConcurrentMap<String, Integer>` snapshots.

### Interfaces and Contracts

- `GET /health/live` -> `200 {"status":"UP"}`
- `GET /healthz` -> `200 {"status":"UP"}`
- `GET /health/ready` -> `200/503` based on DB readiness.
- `GET /readyz` -> `200/503` based on DB readiness.
- `GET /api/ops/stats` -> counters for:
- `ws.activeConnections`, `ws.channels`.
- `hotBuffer.hit`, `hotBuffer.miss`, `hotBuffer.dbFallback`.
- Readiness and stats responses include effective runtime thread sizing (`bossThreads`, `workerThreads`, `businessThreads`).

### Dependencies

**Internal modules:**
- `http/ApiRouter`.
- `service/MessageServiceImpl`, `service/ChannelMessageHotStore`.
- `websocket/ChannelWebSocketRegistry`.
- Runtime datasource wiring in `MessBaaSServer`.

**External services/libraries:**
- Existing Netty/JDBC stack only.

### Failure Modes and Edge Cases

- Readiness endpoint should fail closed (`503`) when DB check throws.
- Stats endpoint must avoid expensive per-request recomputation under high QPS.

### Observability and Debugging

- Health and stats give quick diagnostics without scanning logs only.
- Use DEBUG logs sparingly around cache fallback for sampled requests.

### Risks and Notes

- In-memory counters reset on restart; acceptable for first operational baseline.
- Avoid broad telemetry frameworks until concrete scaling pressure appears.

Changes:

