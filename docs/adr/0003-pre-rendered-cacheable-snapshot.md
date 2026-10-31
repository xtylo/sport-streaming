# ADR 0003 — Pre-rendered, cacheable snapshot for `GET /stream`

- **Status:** Accepted
- **Date:** 2026-10-31

## Context

`GET /stream` returns the state of all live matches (~76 KB of JSON with 20 matches and their
recent events). It is read far more often than the state changes: ≈20 events/s versus hundreds to
thousands of reads/s. The Day 6 stress test showed one pod (1 vCPU) saturating at ≈800 req/s,
with the CPU spent serializing the same JSON over and over — and the saturated reads starving the
Kafka producer (0.93% of `POST /events` failed, delivery p99 6.8 s).

## Decision

Treat the snapshot as a **read model** (CQRS):

- `StreamingService` keeps a monotonic `version`, incremented after each applied event.
- `StreamSnapshotCache` renders the JSON **once per version** (lazy, lock-protected so concurrent
  requests don't render it in parallel) and keeps the bytes, a **gzip-compressed copy**, and a
  **content-hash ETag** for each representation.
- `GET /stream` serves those bytes with `ETag`, `Cache-Control: max-age=1, public` and
  `Vary: Accept-Encoding`; Spring answers `304 Not Modified` to a matching `If-None-Match`.

## Consequences

- Measured: throughput +82% (808 → ≈1,480 req/s per pod), 0% ingest errors at saturation,
  bytes transferred −78% with gzip (see `docs/RESULTS.md`).
- The snapshot can be up to one render behind (milliseconds) and, for clients/CDNs, up to 1 s old.
  Acceptable for a scoreboard; live events still arrive instantly over SSE.
- The ETag is a content hash, not the version: versions are per instance, hashes are equal only for
  equal content, so revalidation works no matter which pod answers.
- Each representation has its own ETag (`"…-gzip"`), as HTTP requires.
- A CDN can now absorb most reads (one origin request per second per edge), which is how this
  design scales to millions of viewers rather than by adding pods alone.
- Spring's on-the-fly compression (`server.compression.enabled`) is intentionally not used: it would
  compress on every request, spending the CPU this change saves.
