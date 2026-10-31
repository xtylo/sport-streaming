# Load test results

Measured on **Day 6** (2026-10-31) with k6 against **one instance limited to the resources of one Kubernetes pod** (1 vCPU, 768 MiB), so the numbers describe the capacity of a single pod.
Raw k6 summaries are written to `loadtest/results/*.json` (git-ignored).

## Environment and method

| Item | Value |
| --- | --- |
| Host | Developer laptop (Windows + WSL2 + Docker Desktop), dev container with Docker-in-Docker |
| Service | `streaming-service` container (`docker compose --profile app`), `cpus: 1`, `memory: 768M`, Java 21, Spring Boot 4.1.2, OTel Java agent attached |
| Dependencies | Single Kafka broker (KRaft) and `grafana/otel-lgtm` on the same host |
| Load generator | k6 2.3.0 (`grafana/k6:2.3.0`) on the same host, `--network host` |
| Model | **Open model** (`constant-arrival-rate` / `ramping-arrival-rate`): requests start at a fixed rate whether or not the server keeps up, avoiding coordinated omission |
| Data | 20 seeded matches; `GET /stream` returns all of them with up to 20 recent events each (~76 KB of JSON) |
| Warm-up | 30 s at 50 req/s, excluded from the results |

Profiles (`loadtest/k6.js`):

- **baseline** — 300 `GET /stream`/s + 20 `POST /events`/s for 3 min.
- **stress** — `GET /stream` ramping 200 → 500 → 1000 → 2000 → 3000 → 4000 req/s (1 min each) + 20 `POST /events`/s.

> Caveat: k6, Kafka, LGTM and the service share one laptop. Two identical stress runs differed noticeably (GET p50 298 ms vs 90 ms), so only large differences are treated as significant. On EKS the generator runs elsewhere and numbers will differ.

## Baseline (normal load)

300 reads/s + 20 writes/s, original implementation. Three runs:

| Route | p50 | p95 | p99 | Errors |
| --- | --- | --- | --- | --- |
| `GET /stream` | 1.3–1.4 ms | 1.8–2.2 ms | 3.7–17.5 ms | 0% |
| `POST /events` | 7.0–7.7 ms | 7.8–10.0 ms | 9.2–25.5 ms | 0% |
| Event delivery (POST → SSE push), from the `streaming.event.delivery_latency` metric | ≈ 7 ms | ≈ 10 ms | **≈ 10 ms** | — |

CPU ≈ 30–50% of the pod. **All targets met** (p95 < 200 ms, p99 < 300 ms, delivery p99 < 200 ms, errors < 1%).

## Stress and optimizations

Each row is one stress run (5.5 min, ramp to 4000 req/s). Throughput is the average served over the run.

| # | Configuration | Throughput | Bytes / response | GET p50 | GET p99 | POST p95 | POST p99 | Ingest errors | Dropped iterations |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | Original (serialize per request) | 808 req/s | 74.9 KB | 2,456 ms | 5,257 ms | 5,648 ms | 7,739 ms | **0.93 %** | 254,677 |
| 2a | **Pre-rendered snapshot cache** | 1,470 req/s | 75.2 KB | 298 ms | 2,412 ms | 1,076 ms | 1,285 ms | 0 % | 35,403 |
| 2b | Same as 2a (repeat) | 1,479 req/s | 75.2 KB | 90 ms | 1,942 ms | 1,055 ms | 1,302 ms | 0 % | 33,866 |
| 3 | 2 + virtual threads | 1,393 req/s | 75.2 KB | 89 ms | 1,955 ms | **2,518 ms** | **3,315 ms** | 0 % | 60,388 |
| 4 | **2 + pre-compressed gzip** (final) | **1,484 req/s** | **16.1 KB** | 66 ms | 1,873 ms | 1,036 ms | 1,376 ms | 0 % | 30,994 |

Data transferred during the run: 20.1 GB (#1), 36.7 GB (#2a), **7.9 GB (#4)**.

## Findings

1. **The original bottleneck was JSON serialization.** Every `GET /stream` serialized ~76 KB from scratch. Pre-rendering the snapshot once per state change (≈20/s instead of ≈1000/s) gave **+82% throughput** and removed the ingest errors. See [ADR 0003](adr/0003-pre-rendered-cacheable-snapshot.md).
2. **Reads starved writes.** Before the cache, saturated reads left the Kafka producer without CPU: 0.93% of `POST /events` failed with `503` and delivery p99 reached 6.8 s. After the cache: 0% errors, delivery p99 ≈ 1 s even at saturation.
3. **Virtual threads did not help a CPU-bound service.** Throughput −5%, and the `POST` p95/p99 got 2–2.5× worse: Tomcat's 200-thread pool had been acting as implicit admission control; without it, thousands of reads compete for the CPU with the writes. Kept **off**; re-enable only with an explicit concurrency limit (bulkhead).
4. **gzip cut bandwidth by 78% but not CPU.** Pre-compressed once per change (4.7× smaller: UUID event ids compress poorly). Throughput stayed at ≈1,480 req/s with the CPU at 100%: the remaining limit is the fixed per-request cost (HTTP parsing, MVC dispatch, OTel instrumentation of every request).
5. **Heap was never the problem** (tenured gen 130–240 MiB of ≈576 MiB max), and the JVM CPU metric was more reliable than eyeballing `docker stats`.

## Capacity of one pod (1 vCPU)

| | Value |
| --- | --- |
| Within SLO (p99 < 300 ms) | **Verified at 300 req/s** (+20 writes/s) with large headroom (p99 ≤ 17.5 ms, CPU 30–50%) |
| Maximum served under overload | **≈ 1,480 req/s** of `GET /stream` at 100% CPU (latency in seconds, but 0% errors) |
| Knee of the curve | Between 300 and ≈1,500 req/s; not isolated (the stress ramp has no per-step breakdown) — see next steps |

Rough sizing: at ~1,000 req/s per pod with headroom, 10,000 snapshot req/s need ≈10 pods — or far fewer with a CDN in front (`Cache-Control: max-age=1` + `ETag` make the snapshot cacheable), which is the realistic design for millions of viewers.

## Next steps (not done)

- Step test at fixed rates (500, 750, 1000, 1250 req/s) to pin the knee of the curve.
- Trace sampling (e.g. `OTEL_TRACES_SAMPLER=parentbased_traceidratio`, 10%) to reduce per-request overhead; measure.
- Thinner `GET /stream` (score/status only, history in `/stream/{matchId}`).
- `linger.ms=0` vs `5` (deprioritized: `POST` p50 is already ≈8 ms).
- SSE fan-out test with thousands of connected clients (k6 needs the xk6-sse extension).
- Repeat on EKS with the load generator outside the cluster (Day 8).
