# ADR 0002 — Kafka keys, partitions and producer settings

- **Status:** Accepted
- **Date:** 2026-10-27

## Context

Events of a match must be applied in order (KICKOFF → GOAL → FULL_TIME), otherwise the score and
status are wrong. Kafka only guarantees ordering **within a partition**. Ingest (`POST /events`)
must answer quickly and must not lie: a `202 Accepted` means the event is stored.

## Decision

- **Topic:** `match-events`, **6 partitions**, created by the application (`NewTopic` bean);
  broker auto-creation disabled.
- **Key = `matchId`.** The default partitioner hashes the key (`murmur2(key) % partitions`), so all
  events of a match land in the same partition and are consumed in order.
- **Value = JSON** produced with the application's Jackson mapper (readable with console tools).
- **Producer:** `acks=all` + idempotence (default), `linger.ms=5`, `compression.type=lz4`.
- **Fail fast:** `POST /events` waits for the broker ack up to 5 s, then answers `503` + `Retry-After`.
  Producer timeouts are aligned: `max.block.ms=5000`, `request.timeout.ms=4000`,
  `delivery.timeout.ms=5000`.
- **Poison messages** (unreadable JSON) are logged and skipped so they never block a partition.

## Consequences

- Ordering per match is guaranteed; different matches are processed in parallel across partitions.
- **Changing the partition count remaps keys** (`% 6` → `% 12` moves matches to other partitions),
  which can break ordering during the transition. 6 partitions leaves headroom for this project.
- One very popular match is a "hot key" in a single partition. Acceptable: per-match event volume is
  low (a few events per minute); the heavy part is the SSE fan-out, which scales with pods.
- `acks=all` costs a little latency but avoids losing a goal on broker failover. On a single local
  broker it behaves like `acks=1`.
- `linger.ms=5` adds at most 5 ms per send and improves batching under load. Revisit on Day 6 with k6.
- Mixed-language producers must use the same partitioner: .NET Confluent.Kafka defaults to
  `consistent_random` (CRC32) and must be set to `Murmur2Random` to match the Java client.
- Skipped poison messages are only logged. Future work: a dead-letter topic.
