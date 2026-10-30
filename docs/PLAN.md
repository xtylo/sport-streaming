# Sport Event Streaming Platform — 8-Day Execution Plan

> **Budget:** 8 sessions, **≤ 60 minutes each** (8 hours total).
> **Base:** the repository structure provided in `plataforma-de-streaming-de-eventos.zip`.
> **Stack:** Java 21 (LTS) · Spring Boot · Apache Kafka · Kubernetes (kind locally, AWS EKS in the cloud) · Terraform · OpenTelemetry · k6.
> **Language rule:** everything in the repo (code, comments, commits, docs) is written in **English**.

---

## 1. Goal and realistic scope

The original README describes a platform for live sports events serving "10M concurrent users".
In 8 hours the goal is a **working, observable, load-tested vertical slice** that demonstrates the architecture and can scale horizontally — not an actual 10M-user deployment.

**What "streaming" means in this project:** live **event data** (scores, play-by-play, match state), not video. Video delivery at that scale is a CDN/HLS problem and is out of scope.

### Definition of Done (end of Day 8)

- [x] Spring Boot service that **ingests** match events (REST → Kafka) and **delivers** them to clients (Kafka → SSE + snapshot endpoint).
- [x] Unit tests + one Kafka integration test (Testcontainers) passing in CI-ready form (`mvn verify`).
- [x] Traces, metrics and logs visible in Grafana via OpenTelemetry.
- [ ] Runs locally with `docker compose up` and on a local Kubernetes cluster (kind) with HPA.
- [ ] k6 load test executed with **measured** p95/p99, error rate and saturation point recorded in the README.
- [ ] Terraform for AWS (VPC + EKS + ECR) applied once, smoke-tested, and **destroyed** the same session.
- [ ] README fully in English, with honest, measured results.

---

## 2. Target architecture

```plaintext
 [Clients: browser / k6]
          |
          v
 [AWS ALB  (AWS Load Balancer Controller / Ingress)]      local: kind + port-forward
          |
          v
 [EKS: streaming-service pods (Spring Boot), HPA 2..N]
     |                         ^
     | produce                 | consume (consumer group per pod → fan-out to SSE clients)
     v                         |
 [Kafka topic: match-events  (key = matchId, 6 partitions)]
          |
          v
 [OpenTelemetry Java agent → OTel Collector → Grafana (Tempo / Prometheus / Loki)]
```

### Request flows

| Flow | Endpoint | Description |
| --- | --- | --- |
| Ingest | `POST /events` | Validates a `MatchEvent` and publishes it to Kafka (key = `matchId`, preserves ordering per match). |
| Snapshot | `GET /stream` | Returns the latest in-memory state for all live matches (JSON). Fast, cacheable — this is what `loadtest/k6.js` hits. |
| Live | `GET /stream/live?matchId=...` | Server-Sent Events (SSE). Pushes each new event as it arrives from Kafka. |
| Health | `GET /actuator/health/{liveness,readiness}` | Kubernetes probes. |

### Design decisions (short ADRs, recorded in `docs/adr/` as you go)

1. **SSE over WebSockets** — one-way server→client push, plain HTTP, works through ALB, trivial to load-test.
2. **Each pod uses its own consumer group** for the fan-out consumer, so every pod sees every event and can push it to its own connected clients. (Classic competing consumers would split events across pods — wrong for broadcast.) *Implemented as a random group id per startup + `auto.offset.reset=earliest`, so a restarted pod replays the topic and rebuilds its in-memory state — see `docs/adr/0001`.*
3. **Kafka key = `matchId`** — ordering guaranteed per match; partitions give producer/broker parallelism.
4. **ELB + API Gateway from the original diagram is simplified to a single ALB.** API Gateway in front of EKS adds latency and cost without adding value for this slice; mention it as a future auth/rate-limit layer.
5. **Kafka runs in-cluster (single broker, KRaft) for the cloud demo**; Amazon MSK is documented in Terraform as an optional stretch (it takes 20–30 min to provision and is expensive for a 1-hour session).
6. **OpenTelemetry Java agent (zero-code instrumentation)** instead of manual SDK wiring — maximum signal for minimum time.

---

## 3. Repository structure (zip base + additions)

Files marked `(zip)` come from the original scaffold; `(new)` are added during the plan.

```plaintext
sport-streaming/
├── .devcontainer/
│   └── devcontainer.json            (zip)  fixed on Day 1
├── docs/
│   ├── PLAN.md                      (new)  this file
│   ├── RESULTS.md                   (new)  load-test results, Day 6 & 8
│   └── adr/                         (new)  short architecture decision records
├── loadtest/
│   └── k6.js                        (zip)  parameterized on Day 6
├── src/                             ← Maven project root (Dockerfile expects target/ here)
│   ├── Dockerfile                   (zip)  multi-stage on Day 5
│   ├── pom.xml                      (new)  Day 1
│   ├── infra/                       (zip)
│   │   ├── main.tf                  (zip)  rewritten on Day 7
│   │   ├── variables.tf             (new)
│   │   └── outputs.tf               (new)
│   ├── k8s/                         (new)  Day 5
│   │   ├── kafka.yaml
│   │   ├── deployment.yaml
│   │   ├── service.yaml
│   │   ├── hpa.yaml
│   │   └── ingress.yaml             (Day 8, ALB)
│   └── src/
│       ├── main/java/com/streaming/
│       │   ├── StreamingApplication.java        (new)
│       │   ├── StreamingService.java            (zip)  implemented Day 2–3
│       │   ├── api/EventController.java         (new)
│       │   ├── api/StreamController.java        (new)
│       │   ├── domain/MatchEvent.java           (new)  Java record
│       │   ├── domain/MatchState.java           (new)
│       │   └── kafka/MatchEventProducer.java    (new)
│       │       kafka/MatchEventConsumer.java    (new)
│       ├── main/resources/application.properties (zip)
│       └── test/java/com/streaming/
│           ├── StreamingServiceTest.java        (new)  from README example
│           └── KafkaIntegrationTest.java        (new)
├── docker-compose.yml               (zip)  rewritten Day 3–4
└── README.md                        (zip)  translated Day 1, results Day 8
```

### Known issues in the zip scaffold (fix on Day 1)

| File | Issue | Fix |
| --- | --- | --- |
| `src/Dockerfile` | First line `// ...` is not a valid Dockerfile comment (build fails). `openjdk:11-jre-slim` is deprecated/unmaintained. | Use `#` comments; `eclipse-temurin:21-jre` (multi-stage on Day 5). |
| `application.properties` | `//` is not a comment in `.properties` (parsed as a key). | Use `#`. |
| `StreamingService.java` | No `package` declaration; Spanish comments. | Add `package com.streaming;`, translate comments. |
| `src/` | No `pom.xml`, no `@SpringBootApplication` class. | Generate with Spring Initializr into `src/`. |
| `infra/main.tf` | `aws_kafka_cluster` is not a real resource (it's `aws_msk_cluster`); EKS resource has no config (VPC, roles, node group). | Rewrite with `terraform-aws-modules/vpc` + `terraform-aws-modules/eks` on Day 7. |
| `docker-compose.yml` | `version:` is obsolete. EKS and MSK emulation in LocalStack are **not** in the free tier (requires a paid plan/auth token). Port 9092 on LocalStack does not give you a real broker. | Use an `apache/kafka` (KRaft) container + `grafana/otel-lgtm` for local dev; Kubernetes via kind. LocalStack only optional for S3/ECR-style checks. |
| `.devcontainer/devcontainer.json` | References a `Dockerfile` that does not exist; `extensions`/`settings` are the deprecated top-level format; JDK 11. | Use `mcr.microsoft.com/devcontainers/java:21` image + features (Maven, Docker-in-Docker, kubectl/helm, Terraform, AWS CLI) under `customizations.vscode`. |
| `README.md` | Spanish; README says `cd infra` but folder is `src/infra`; metrics table (p99 < 200 ms, 10 min) disagrees with `k6.js` thresholds (p95 < 200, p99 < 300, 9 min). | Translate; fix paths; align on one set of targets (see §6). |

---

## 4. .NET → Java/AWS cheat sheet

Your 14 years of .NET/Azure transfer almost one-to-one; use this to move fast.

| .NET / Azure | Java / AWS equivalent |
| --- | --- |
| `.csproj` + NuGet | `pom.xml` + Maven Central |
| `dotnet new webapi` | start.spring.io (Spring Initializr) |
| `Program.cs` / `WebApplication` | `@SpringBootApplication` class + `SpringApplication.run` |
| `appsettings.json` + env vars | `application.properties` / `application.yml` + env vars (`SPRING_KAFKA_BOOTSTRAP_SERVERS`) |
| `[ApiController]` / `ControllerBase` | `@RestController` |
| Built-in DI (`AddSingleton`) | Spring DI (`@Service`, `@Component`, constructor injection) |
| `record` | Java `record` |
| `BackgroundService` consuming Confluent.Kafka | `@KafkaListener` (Spring Kafka) |
| `IProducer<K,V>` | `KafkaTemplate<K,V>` |
| `IAsyncEnumerable` / SignalR push | `SseEmitter` (or WebFlux `Flux`) |
| xUnit + FluentAssertions | JUnit 5 + AssertJ |
| Testcontainers for .NET | Testcontainers for Java (same concept) |
| `System.Diagnostics.ActivitySource` / OTel .NET | OpenTelemetry Java agent / Micrometer |
| Health checks (`MapHealthChecks`) | Spring Boot Actuator |
| AKS | EKS |
| Azure Event Hubs (Kafka endpoint) | Amazon MSK |
| ACR | ECR |
| Azure Load Balancer / App Gateway | NLB / ALB |

---

## 5. Day-by-day plan

Every day follows the same rhythm: **5 min review → ~45 min build → ~10 min verify + commit + notes**.
If a day overruns, cut the item marked *(stretch)* — never the commit.

### Prerequisites (do once, before Day 1, ~20 min outside the budget if needed)

- Docker Desktop with WSL2 backend (Windows).
- VS Code + Dev Containers extension (recommended: all tooling lives in the container — no JDK/Maven/Terraform install on Windows).
- A GitHub repo for `sport-streaming`.
- AWS account with an IAM user/role for the Day 8 deployment and a **Budget alarm at $10**.

---

### Day 1 — Bootstrap, fix scaffold, architecture (60 min)

**Goal:** a compiling Spring Boot app inside the zip structure, in English.

| Min | Task |
| --- | --- |
| 0–10 | `git init`, add `.gitignore` (Java/Maven/Terraform/IDE), commit the zip as-is (`chore: initial scaffold from template`). |
| 10–20 | Fix `.devcontainer/devcontainer.json` (Java 21 image + features) and reopen in container. |
| 20–35 | Spring Initializr → Maven, Java 21, latest stable Spring Boot, deps: **Web, Actuator, Validation, Spring for Apache Kafka**. Merge `pom.xml` + `StreamingApplication.java` into `src/`. Add `package` to `StreamingService.java`. |
| 35–45 | Fix `//` comments in `Dockerfile` and `application.properties`; translate all code comments. |
| 45–55 | Translate `README.md` to English, fix `cd src/infra` paths, paste the architecture from §2. Write `docs/adr/0001-sse-and-fanout-consumer-groups.md` (5 lines). |
| 55–60 | `cd src && mvn -q verify` → green. Commit `feat: bootstrap Spring Boot service`. |

**Done when:** `mvn spring-boot:run` starts on `:8080`; `/actuator/health` returns `UP`.
**Learn:** Maven lifecycle, Spring Boot auto-configuration.

---

### Day 2 — Domain, REST + SSE endpoints, unit tests (60 min)

**Goal:** the service works end-to-end **without Kafka** (in-memory), so Kafka on Day 3 is a pure plumbing change.

| Min | Task |
| --- | --- |
| 0–10 | `MatchEvent` record (`matchId`, `type` [GOAL, CARD, KICKOFF, END…], `minute`, `payload`, `timestamp`) with Bean Validation. `MatchState` (score, last events). |
| 10–25 | Implement `StreamingService`: `ConcurrentHashMap<String, MatchState>`, `onEventReceived(MatchEvent)` updates state and notifies subscribers; `subscribe(matchId)` returns an `SseEmitter` (track emitters in a `CopyOnWriteArrayList`, remove on completion/timeout). |
| 25–40 | `EventController` (`POST /events` → temporarily calls the service directly) and `StreamController` (`GET /stream` snapshot, `GET /stream/live` SSE). |
| 40–55 | JUnit 5 tests: README's `StreamingServiceTest`, state update on GOAL, emitter receives event, validation rejects bad payload (`@WebMvcTest`). |
| 55–60 | Manual check with `curl -N localhost:8080/stream/live?matchId=m1` + a POST. Commit. |

**Done when:** posting an event shows up instantly in an open `curl -N` SSE session; tests green.
**Learn:** Spring MVC, `SseEmitter`, JUnit 5/AssertJ, `@WebMvcTest`.
*(stretch)* enable virtual threads: `spring.threads.virtual.enabled=true`. → *Moved to Day 6 so it can be measured with k6.*

---

### Day 3 — Kafka locally: producer, consumer, integration test (60 min)

**Goal:** `POST /events` → Kafka → consumer → SSE clients.

| Min | Task |
| --- | --- |
| 0–10 | Rewrite `docker-compose.yml`: `kafka` service using `apache/kafka` (KRaft, single node, ports 9092), remove `version:`. Keep LocalStack commented out as optional. |
| 10–25 | `MatchEventProducer` with `KafkaTemplate<String, MatchEvent>` (JSON serializer, key = `matchId`). `EventController` now publishes instead of calling the service. Topic bean: `match-events`, 6 partitions. |
| 25–35 | `MatchEventConsumer` with `@KafkaListener(topics = "match-events", groupId = "streaming-#{T(java.util.UUID).randomUUID()}")` → `streamingService.onEventReceived(...)` (unique group per instance = broadcast + state rebuilt on restart, see ADR 0001). |
| 35–50 | `KafkaIntegrationTest` using Testcontainers Kafka + `@ServiceConnection`: POST an event, assert `GET /stream` reflects it (Awaitility). |
| 50–60 | Producer tuning: `acks=all` (+ idempotence), `linger.ms=5`, `compression.type=lz4`, fail-fast timeouts (`max.block.ms`/`delivery.timeout.ms` = 5 s → `503`); consumer `auto.offset.reset=earliest`. Write ADR 0002 (keys/partitions/producer). Commit. |

**Done when:** `docker compose up kafka` + app running → SSE receives events through Kafka; integration test green.
**Learn:** Kafka topics/partitions/consumer groups, Spring Kafka, Testcontainers.

---

### Day 4 — Observability with OpenTelemetry (60 min)

**Goal:** see a trace from `POST /events` → Kafka produce → Kafka consume in Grafana.

| Min | Task |
| --- | --- |
| 0–10 | Add `grafana/otel-lgtm` to `docker-compose.yml` (all-in-one OTel Collector + Tempo + Prometheus + Loki + Grafana; ports 3000, 4317, 4318). |
| 10–25 | Download the **OpenTelemetry Java agent** into the image/run config; run with `-javaagent:opentelemetry-javaagent.jar`, `OTEL_SERVICE_NAME=streaming-service`, `OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317`. |
| 25–40 | Custom metrics via OTel API or Micrometer: `streaming.active_sse_clients` (gauge), `streaming.event_delivery_latency_ms` (histogram = now − event.timestamp). |
| 40–55 | Grafana: verify the distributed trace in Tempo; build a small dashboard (RPS, p95/p99 latency, active SSE clients, Kafka consumer lag). Export the dashboard JSON to `docs/grafana-dashboard.json`. |
| 55–60 | Commit. Screenshot of the trace for the README. |

**Done when:** one trace spans HTTP → Kafka producer → Kafka consumer; dashboard shows live metrics.
**Learn:** OTel signals (traces/metrics/logs), context propagation over Kafka headers, OTLP.

---

### Day 5 — Containerize + Kubernetes locally (kind) (60 min)

**Goal:** the whole system runs on a local Kubernetes cluster with autoscaling.

| Min | Task |
| --- | --- |
| 0–15 | Multi-stage `src/Dockerfile`: `maven:3-eclipse-temurin-21` build → `eclipse-temurin:21-jre` runtime, non-root user, OTel agent copied in, `JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -javaagent:/app/otel.jar"`. `docker compose` gets an `app` service. |
| 15–25 | `kind create cluster --name streaming`; `kind load docker-image streaming-service:dev`. Install metrics-server. |
| 25–45 | `src/k8s/`: `kafka.yaml` (single-broker KRaft StatefulSet + Service), `deployment.yaml` (2 replicas, requests/limits, liveness/readiness on Actuator, `HOSTNAME` from pod name, env for Kafka + OTLP), `service.yaml`, `hpa.yaml` (CPU 60%, 2–10 replicas). |
| 45–55 | `kubectl apply -f src/k8s/`, `kubectl port-forward svc/streaming-service 8080:80`, smoke test POST + SSE. Verify SSE works when connected to **either** pod (fan-out check). |
| 55–60 | Commit. |

**Done when:** 2 pods running, both deliver events to their SSE clients; `kubectl get hpa` shows targets.
**Learn:** Deployments, Services, probes, HPA, resource limits — same concepts as AKS.

---

### Day 6 — Load testing and tuning (60 min)

**Goal:** real numbers: throughput, p95/p99, error rate, saturation point.

| Min | Task |
| --- | --- |
| 0–10 | Parameterize `loadtest/k6.js`: `BASE_URL` env var, scenarios `snapshot` (GET `/stream`) and `ingest` (POST `/events`); scale stages for a laptop (e.g. 100 → 1,000 VUs). Run k6 via `grafana/k6` Docker image. |
| 10–25 | Baseline run against kind/compose. Watch Grafana during the run. |
| 25–45 | One tuning iteration (pick based on what the dashboard shows): virtual threads, Tomcat max connections, HPA thresholds, JVM heap %, Kafka `linger.ms`/batch size, more partitions. Re-run. |
| 45–55 | Ramp until p99 crosses the threshold → that is your **saturation point**. Fill in `docs/RESULTS.md` (before/after table, hardware used). |
| 55–60 | Commit. |

**Done when:** `docs/RESULTS.md` has measured RPS, p50/p95/p99, error rate and saturation point.
**Learn:** load profiles, Little's law, reading latency percentiles, k6 thresholds.

---

### Day 7 — Terraform for AWS (plan only) (60 min)

**Goal:** reviewed, validated IaC — nothing applied yet (no cost today).

| Min | Task |
| --- | --- |
| 0–10 | AWS CLI login (`aws configure sso` or access keys), confirm Budget alarm exists. |
| 10–35 | Rewrite `src/infra/main.tf`: `terraform-aws-modules/vpc/aws` (2 AZs, **single NAT gateway**), `terraform-aws-modules/eks/aws` (managed node group, 2× `t3.medium`, optionally SPOT), `aws_ecr_repository`. `variables.tf` (region `us-west-2`, cluster name), `outputs.tf` (cluster name, ECR URL). |
| 35–45 | Add MSK as an **optional, disabled** block (`count = var.enable_msk ? 1 : 0`, `aws_msk_cluster` or MSK Serverless) — documented, not applied. |
| 45–55 | `terraform init && terraform fmt && terraform validate && terraform plan -out tfplan`. Review the plan resource count. |
| 55–60 | Write the Day 8 runbook in `docs/RESULTS.md` (exact commands). Commit (never commit `*.tfstate` or `tfplan`). |

**Done when:** `terraform plan` succeeds with a reviewed list of resources.
**Learn:** Terraform modules, EKS building blocks (VPC, IAM roles, node groups, access entries).

---

### Day 8 — Deploy to EKS, smoke test, destroy, wrap-up (60 min)

**Goal:** prove it runs on AWS, then tear it all down in the same session.

| Min | Task |
| --- | --- |
| 0–20 | `terraform apply tfplan` (EKS takes ~12–18 min). **While it provisions:** `docker build` + push to ECR; prepare `ingress.yaml` (or use a `LoadBalancer` Service if no time for the AWS Load Balancer Controller). |
| 20–25 | `aws eks update-kubeconfig --name <cluster>`; update image in `deployment.yaml` to the ECR URL. |
| 25–35 | `kubectl apply -f src/k8s/`; wait for pods; smoke test POST + SSE via the load balancer DNS. |
| 35–40 | Short k6 run (2–3 min) from your machine against the LB; record results. Screenshot `kubectl get pods,hpa`. |
| 40–55 | **`kubectl delete -f src/k8s/`** (deletes the LB first, otherwise VPC destroy can hang) → **`terraform destroy`**. Confirm in the AWS console that EKS, NAT gateway, LB and EBS volumes are gone. |
| 55–60 | Final README update: measured results (local + cloud), screenshots, takeaways, "next steps" (MSK, API Gateway auth, multi-region, CDN). Commit + tag `v1.0`. |

**Done when:** README shows real cloud results and the AWS bill stops growing.
**Fallback:** if `apply` is slow and you hit minute 40, skip the k6 run — **always** leave the last 15 minutes for destroy.

---

## 6. Target metrics (aligned)

Local hardware will not reach 10,000 RPS; record what you actually measure and extrapolate per pod.

| Metric | Target | How to measure |
| --- | --- | --- |
| Snapshot throughput (`GET /stream`) | Max sustained RPS per pod, recorded | k6 + Grafana |
| Latency | p95 < 200 ms, p99 < 300 ms (matches `k6.js` thresholds) | k6 summary + OTel histogram |
| Event delivery latency (produce → SSE) | p99 < 200 ms | `streaming.event_delivery_latency_ms` |
| Error rate | < 1% (k6 threshold), stretch < 0.1% | k6 `http_req_failed` |
| Saturation point | Recorded RPS where p99 > 300 ms | Ramp test on Day 6 |
| Horizontal scaling | HPA scales out under load and back in | `kubectl get hpa -w` during k6 |

> **Portfolio tip:** keep the README's "Impact" section honest — e.g. *"Load-tested to X RPS per pod with p99 Y ms; scales horizontally via HPA; architecture designed for millions of concurrent viewers"* reads better in interviews than an unmeasured "10M users" claim.

---

## 7. Cost and risk management

| Risk | Mitigation |
| --- | --- |
| AWS cost (EKS control plane ~$0.10/h, NAT gateway, nodes, LB) | Apply and destroy in the same session (Day 8); single NAT; small/spot nodes; $10 budget alarm. Expected Day 8 cost: **~$1–2**. |
| `terraform destroy` hangs on VPC | Delete Kubernetes `LoadBalancer`/Ingress resources **before** destroy. |
| LocalStack EKS/MSK not available in free tier | Use kind + `apache/kafka` locally (already the plan). |
| Learning curve (Java, Kafka, EKS) | Use the .NET cheat sheet (§4); time-box research to 10 min, then copy a known-good example. |
| Session overruns 60 min | Drop the *(stretch)* item; push leftovers to the next day's first 10 min; Day 8 destroy is non-negotiable. |
| Laptop can't generate enough load | Measure per-pod capacity and extrapolate; run k6 from the cloud only if needed. |
| Secrets in git | `.gitignore` for `*.tfstate*`, `tfplan`, `.env`, `.localstack/`; AWS creds only via CLI profile. |

### Lessons learned (Days 1–5)

| Problem | Symptom | Fix |
| --- | --- | --- |
| Docker Desktop **Resource Saver** | Engine stops after 5 min idle; Dev Containers hangs on "failed to connect to the docker API" | Settings → Resources → Advanced → disable Resource Saver; wake the engine with `docker desktop restart` |
| `devcontainers/java:21` is based on **Debian trixie** | `docker-in-docker` feature fails: *moby not supported on trixie* | Feature option `"moby": false` (installs Docker CE) |
| Docker Desktop **WSL integration** off | Container fails to start: *distro-services/ubuntu.sock: no such file* (VS Code mounts the Wayland socket from Ubuntu) | Settings → Resources → WSL integration → enable Ubuntu (or `dev.containers.mountWaylandSocket: false`) |
| `JAVA_HOME` in WSL points to a Windows JDK | `mvnw`: *JAVA_HOME is not defined correctly* | Work inside the dev container (or install a Linux JDK in WSL) |
| Files written by another Windows user | Maven: *Operation not permitted* copying resources | Recreate the files as your user; `mvn clean` |
| `docker compose down` | All events gone, empty snapshots | No volume for Kafka data: use `stop`/`start` to keep events |
| Producer `max.block.ms` default (60 s) | `POST /events` hangs 60 s when Kafka is down | `max.block.ms=5000`, aligned with the send timeout |
| `KafkaTemplate.send()` throws synchronously when the send already failed | `500` instead of `503` | Catch `KafkaException` (Spring and Apache) → `EventPublishException` → `503` + `Retry-After` |
| `Ctrl+Z` in the terminal | App suspended, still holding port 8080 | Stop with `Ctrl+C`; clean up with `jobs` / `kill %N` |
| App started before the OTel agent was added | Tempo search returns 0 traces | Restart the app; check `target/otel/opentelemetry-javaagent.jar` and the agent version line in the log |
| `@Scheduled` methods are instrumented | A trace every 15 s for the SSE heartbeat (noise) | `OTEL_INSTRUMENTATION_SPRING_SCHEDULING_ENABLED=false` |
| Startup replay counted as delivery latency | p50 ≈ 17 ms but p95/p99 pinned at 10 s (top histogram bucket) | Record latency only for events created after startup; count replays in `streaming.events.replayed`. Always look at p95/p99, not just averages |
| Stacked time series with sparse series | Wrong color/series appears to carry all the traffic | Use `stacking: none` for per-type rates |
| Docker Desktop not running after a reboot | Dev Containers hangs at "failed to connect to the docker API" | `docker desktop start`; enable *Start Docker Desktop when you sign in* |
| Docker Desktop WSL integration with Ubuntu 26.04 stops | "WSL integration with distro 'Ubuntu' unexpectedly stopped" | Restart it / `wsl --shutdown`; if it repeats, disable the integration and set `dev.containers.mountWaylandSocket: false` + `dev.containers.executeInWSL: false` |
| Service named `kafka` in Kubernetes | Image reads injected `KAFKA_PORT=tcp://…` as broker config | `enableServiceLinks: false` |
| App starts before Kafka in the cluster | Topic never created (only created on startup) | `initContainer` that waits for `kafka:9092` |
| Deleted pod shows `Error` | JVM exits with 143 (SIGTERM) after graceful shutdown; open SSE connections delay it | Expected; the replacement pod is the one to watch |
| HPA on a single kind node | Scales 2 → 6 instantly but CPU stays > target | Pods share the same node CPU; on EKS add node autoscaling (Cluster Autoscaler / Karpenter). For SSE, scale on `streaming_sse_active_clients` (KEDA / Prometheus Adapter) rather than CPU |

---

## 8. Progress tracker

| Day | Focus | Status | Notes |
| --- | --- | --- | --- |
| 1 | Bootstrap & scaffold fixes | ✅ | Spring Boot 4.1.2 / Java 21; dev container fixed (see Lessons learned); README translated after Day 3. |
| 2 | Domain, REST/SSE, unit tests | ✅ | `MatchEvent`/`MatchState` records, `StreamingService` (state + SSE + heartbeat), Problem Details; 26 tests. Virtual threads moved to Day 6 (measure before/after). |
| 3 | Kafka producer/consumer + integration test | ✅ | Port/adapter publisher, fan-out consumer with state replay, poison-message skip, Testcontainers, fail-fast `503`; ADR 0001–0002; 33 tests. |
| 4 | OpenTelemetry + Grafana | ✅ | `grafana/otel-lgtm` 0.30.0 in compose; OTel Java agent 2.31.1 (downloaded by Maven, attached by `spring-boot:run`); end-to-end trace `POST /events → publish → process`; custom metrics (SSE clients, delivery latency, delivered/replayed); dashboard as code in `docs/grafana-dashboard.json`. First local numbers: delivery p50 ≈ 10 ms, p99 ≈ 25 ms. |
| 5 | Docker + kind + HPA | ✅ | Multi-stage Dockerfile with Spring Boot layers (app layer ≈ 41 kB, image 180 MB compressed), compose profile `app`; kind cluster + metrics-server; manifests in `src/k8s` (Kafka StatefulSet + PVC, Deployment with startup/readiness/liveness probes, non-root + read-only FS, NodePort Service, HPA). Verified: fan-out across pods, pod kill → state rebuilt from Kafka, HPA 2 → 6 → 2 under in-cluster load. |
| 6 | k6 load test + tuning | ☐ | |
| 7 | Terraform (plan only) | ☐ | |
| 8 | EKS deploy, smoke, destroy, README | ☐ | |

---

## 9. Learning resources (read only what the day needs)

- Spring Initializr — https://start.spring.io
- Spring for Apache Kafka — https://docs.spring.io/spring-kafka/reference/
- Apache Kafka docs — https://kafka.apache.org/documentation/
- Testcontainers for Java (Kafka module) — https://java.testcontainers.org/modules/kafka/
- OpenTelemetry Java agent — https://opentelemetry.io/docs/zero-code/java/agent/
- grafana/otel-lgtm — https://github.com/grafana/docker-otel-lgtm
- kind — https://kind.sigs.k8s.io/
- k6 docs — https://grafana.com/docs/k6/latest/
- Terraform AWS EKS module — https://registry.terraform.io/modules/terraform-aws-modules/eks/aws/latest
- EKS Best Practices — https://docs.aws.amazon.com/eks/latest/best-practices/introduction.html
