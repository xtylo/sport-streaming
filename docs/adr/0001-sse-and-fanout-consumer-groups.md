# ADR 0001 — SSE delivery and fan-out with one consumer group per instance

- **Status:** Accepted
- **Date:** 2026-10-27

## Context

Fans need live match events (goals, cards, kickoff…) pushed to them with low latency.
The flow is almost entirely one-way: server → client. The service runs as several
instances (pods) behind a load balancer, and each client is connected to exactly one of them.

## Decision

1. **Deliver with Server-Sent Events (SSE)** over plain HTTP (`GET /stream/live?matchId=…`),
   not WebSockets.
2. **Each instance consumes the whole topic with its own consumer group**
   (`streaming-<random UUID>`, generated at startup), and pushes events to the SSE clients
   connected to it.
3. **The consumer starts from the earliest offset**, so a new or restarted instance replays
   the topic and rebuilds its in-memory match state before serving clients.

## Consequences

- SSE gives automatic reconnection (`EventSource` + `Last-Event-ID`), works through ALB/proxies
  and is trivial to load-test. A 15 s heartbeat keeps idle connections alive (ALB idle timeout: 60 s).
- A shared consumer group would split partitions across pods (competing consumers), so each pod
  would only see part of the matches — wrong for broadcast. Per-instance groups make every pod
  see every event.
- Kafka is the source of truth; the in-memory state is a projection (event-sourcing style).
  Startup time grows with topic retention. Mitigation if needed: log compaction or periodic snapshots.
- Random group ids leave empty groups behind on the broker; they hold no offsets and expire.
- Rejected: WebSockets (bidirectional, not needed), polling (latency and load), SignalR-style hubs
  (heavier, not required for one-way push).
