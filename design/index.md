# MessBaaS — Design Index

MessBaaS is a single-node Java 21 chat backend on Netty + JDBC/MySQL with idempotent message sends, membership-gated HTTP/WebSocket access, async outbox-driven projections, and cache-accelerated history/inbox/unread reads.

## Mental Map

```
┌─ Runtime Bootstrap and Wiring ────────────────────────────────────────────────────────────┐
│ Owns: config loading, DB/Redis bootstrap, Flyway, service wiring, worker lifecycle       │
│ Entry: src/main/java/com/java_mess/java_mess/MessBaaSServer.java                         │
│ Key:   src/main/java/com/java_mess/java_mess/server/NettyServer.java                     │
│ Uses:  JDBC Persistence, Async Event Projection Pipeline, Message HTTP API, Inbox         │
└────────────────────────────────────────────────────────────────────────────────────────────┘

┌─ Message HTTP API ─────────────────────────────────────────────────────────────────────────┐
│ Owns: idempotent send + pivot-window history routes                                       │
│ Entry: src/main/java/com/java_mess/java_mess/http/ApiRouter.java                         │
│ Key:   src/main/java/com/java_mess/java_mess/service/MessageServiceImpl.java             │
│ Uses:  JDBC Persistence, Channel Membership, Channel Hot Buffer Cache, Async Projection   │
└────────────────────────────────────────────────────────────────────────────────────────────┘

┌─ Async Event Projection Pipeline ──────────────────────────────────────────────────────────┐
│ Owns: outbox polling, retry/backoff/dead-letter, post-commit projection execution         │
│ Entry: src/main/java/com/java_mess/java_mess/service/AsyncProjectionWorker.java          │
│ Key:   src/main/java/com/java_mess/java_mess/service/MessageProjectionProcessor.java      │
│ Uses:  JDBC Persistence, Channel Hot Buffer Cache, WebSocket Stream, Inbox Projection     │
└────────────────────────────────────────────────────────────────────────────────────────────┘

┌─ User Inbox Projection ────────────────────────────────────────────────────────────────────┐
│ Owns: `/api/inbox` cache-first reads and DB fallback rebuild                              │
│ Entry: src/main/java/com/java_mess/java_mess/http/ApiRouter.java                         │
│ Key:   src/main/java/com/java_mess/java_mess/service/InboxServiceImpl.java               │
│ Uses:  Async Projection Pipeline, JDBC Persistence, Read Receipts/Unread State            │
└────────────────────────────────────────────────────────────────────────────────────────────┘

┌─ WebSocket Channel Stream ─────────────────────────────────────────────────────────────────┐
│ Owns: membership-gated handshake, registry, inbound envelopes, outbound broadcast         │
│ Entry: src/main/java/com/java_mess/java_mess/websocket/WebSocketHandshakeHandler.java    │
│ Key:   src/main/java/com/java_mess/java_mess/websocket/ChannelWebSocketFrameHandler.java │
│ Uses:  Message HTTP API, Channel Membership, Async Projection Pipeline                    │
└────────────────────────────────────────────────────────────────────────────────────────────┘

┌─ Channel Hot Buffer Cache ─────────────────────────────────────────────────────────────────┐
│ Owns: in-memory hot window + Redis hot-list acceleration for history reads                │
│ Entry: src/main/java/com/java_mess/java_mess/service/ChannelMessageHotStore.java         │
│ Key:   src/main/java/com/java_mess/java_mess/service/ProjectionCacheStore.java           │
│ Uses:  Message HTTP API, Async Projection Pipeline                                        │
└────────────────────────────────────────────────────────────────────────────────────────────┘

┌─ Channel Membership and Access Control ────────────────────────────────────────────────────┐
│ Owns: member add/remove/list and assertMember checks with cache-first lookup              │
│ Entry: src/main/java/com/java_mess/java_mess/http/ApiRouter.java                         │
│ Key:   src/main/java/com/java_mess/java_mess/service/ChannelMembershipServiceImpl.java   │
│ Uses:  JDBC Persistence, Projection Cache                                                 │
└────────────────────────────────────────────────────────────────────────────────────────────┘

┌─ Read Receipts and Unread State ───────────────────────────────────────────────────────────┐
│ Owns: read-cursor persistence and unread-count projection-backed retrieval                │
│ Entry: src/main/java/com/java_mess/java_mess/http/ApiRouter.java                         │
│ Key:   src/main/java/com/java_mess/java_mess/service/ReadStateServiceImpl.java           │
│ Uses:  JDBC Persistence, Projection Cache                                                 │
└────────────────────────────────────────────────────────────────────────────────────────────┘

┌─ Operational Health and Runtime Stats ─────────────────────────────────────────────────────┐
│ Owns: liveness/readiness probes and `/api/ops/stats` runtime snapshots                    │
│ Entry: src/main/java/com/java_mess/java_mess/http/ApiRouter.java                         │
│ Key:   src/main/java/com/java_mess/java_mess/service/MessageRuntimeStats.java            │
│ Uses:  Message API, Inbox Projection, Async Projection, WebSocket Registry                │
└────────────────────────────────────────────────────────────────────────────────────────────┘

┌─ JDBC Persistence and Schema ──────────────────────────────────────────────────────────────┐
│ Owns: Flyway schema and raw JDBC repositories (including outbox)                          │
│ Key:   src/main/java/com/java_mess/java_mess/repository/MessageRepository.java           │
│        src/main/java/com/java_mess/java_mess/repository/MessageOutboxRepository.java     │
└────────────────────────────────────────────────────────────────────────────────────────────┘
```

## Feature Matrix

| Feature | Description | File | Status |
|---------|-------------|------|--------|
| Runtime Bootstrap and Wiring | Process startup, config, DB/Redis wiring, worker lifecycle | [runtime-bootstrap.md](runtime-bootstrap.md) | Stable |
| User HTTP API | Create/fetch users by client user ID | [user-http-api.md](user-http-api.md) | Stable |
| Channel HTTP API | Create/list/get channels | [channel-http-api.md](channel-http-api.md) | Stable |
| Message HTTP API | Idempotent send and history windows | [message-http-api.md](message-http-api.md) | Stable |
| Idempotent Message Send and Reconnect-Safe Events | Client message idempotency behavior | [idempotent-message-send.md](idempotent-message-send.md) | Stable |
| Async Event Projection Pipeline | Durable outbox + retry/backoff projection worker | [async-event-projection-pipeline.md](async-event-projection-pipeline.md) | Stable |
| User Inbox Projection | Cache-first inbox read model and fallback | [user-inbox-projection.md](user-inbox-projection.md) | Stable |
| WebSocket Channel Stream | Membership-gated websocket ingress/egress | [websocket-channel-stream.md](websocket-channel-stream.md) | Stable |
| Channel Hot Buffer Cache | Local + Redis accelerated hot history windows | [channel-hot-buffer.md](channel-hot-buffer.md) | Stable |
| JDBC Persistence and Schema | Flyway migrations and JDBC repositories | [jdbc-persistence.md](jdbc-persistence.md) | Stable |
| Read Receipts and Unread State | Read cursor and unread computation/projection | [read-receipts-unread-state.md](read-receipts-unread-state.md) | Stable |
| Channel Membership and Access Control | Membership APIs and authorization checks | [channel-membership.md](channel-membership.md) | Stable |
| Operational Health and Runtime Stats | Health probes and runtime counters/latencies | [operational-observability.md](operational-observability.md) | Stable |

## Cross-Cutting Concerns

- Validation is centralized in `http/RequestValidator` and applied at HTTP/WebSocket boundaries.
- HTTP error mapping is centralized in `ApiRouter.statusFor`.
- Send path is MySQL-authoritative and no longer synchronously coupled to websocket fanout.
- Post-commit side effects run through outbox-driven async projection.
- Redis acceleration is optional; degraded fallback keeps DB-backed correctness.

## Notes

- Feature filenames are treated as stable keys by design tooling.
- `design/update.md` is a request scratchpad, not a runtime feature spec.
