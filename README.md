# Sport Event Streaming Platform

**Problem:** live sports events need a platform that delivers match updates without interruption to a very large number of concurrent viewers.

**Solution:** a streaming platform for live sports **event data** (scores, play-by-play, match state) built with Java / Spring Boot, Apache Kafka and Kubernetes (AWS EKS), observable end to end with OpenTelemetry. Events are ingested over HTTP, stored in Kafka and pushed to clients in real time with Server-Sent Events (SSE). The service scales horizontally: every instance receives every event and serves its own connected clients.

**Impact:** *to be measured.* Throughput per pod, p95/p99 latency, error rate and saturation point will be recorded here from k6 load tests (Day 6, local) and an EKS deployment (Day 8). See [Target metrics](#target-metrics).

**Technologies:** Java 21, Spring Boot 4, Apache Kafka (KRaft), Docker, Kubernetes (kind / AWS EKS), Terraform, OpenTelemetry, Grafana, k6, JUnit 5, Testcontainers.

**New technologies to learn:** Java, Spring Boot, AWS EKS, Kafka, OpenTelemetry.

> Scope: "streaming" here means live **event data**, not video. Video delivery at this scale is a CDN/HLS problem and is out of scope.
> The execution plan (8 sessions of up to 1 hour) is in [`docs/PLAN.md`](docs/PLAN.md); design decisions are in [`docs/adr/`](docs/adr/).

## Status

| Phase | Status |
| --- | --- |
| 1. Streaming architecture and microservice design | ✅ |
| 2. Spring Boot backend (ingest, snapshot, SSE) | ✅ |
| 3. Kafka for event delivery | ✅ |
| 4. OpenTelemetry observability | ⏳ |
| 5. Containers + local Kubernetes (kind) with autoscaling | ⏳ |
| 6. Load testing and tuning (k6) | ⏳ |
| 7–8. Terraform + deployment to AWS EKS | ⏳ |

## Architecture

```plaintext
 [Clients: browser / k6]
          |
          v
 [AWS ALB (AWS Load Balancer Controller / Ingress)]      local: port-forward / localhost
          |
          v
 [EKS: streaming-service pods (Spring Boot), HPA 2..N]
     |                         ^
     | produce                 | consume (one consumer group per pod -> fan-out to its SSE clients)
     v                         |
 [Kafka topic: match-events  (key = matchId, 6 partitions)]
          |
          v
 [OpenTelemetry Java agent -> OTel Collector -> Grafana (Tempo / Prometheus / Loki)]
```

- **Clients** subscribe to a match over SSE, or read snapshots over plain HTTP.
- **Load balancer (ALB)** spreads connections across pods. (The original design had ELB + API Gateway; it was simplified to a single ALB — API Gateway remains a future option for auth / rate limiting.)
- **Streaming service (Spring Boot)** validates and publishes events, consumes them back from Kafka, keeps the current state of every match in memory and pushes each event to its SSE clients.
- **Kafka** is the source of truth: key = `matchId` keeps the events of a match ordered; a restarted pod replays the topic to rebuild its state.
- **OpenTelemetry** collects traces, metrics and logs.

### API

| Method | Endpoint | Description |
| --- | --- | --- |
| `POST` | `/events` | Ingest a match event. `202` once stored in Kafka, `400` (Problem Details) if invalid, `503` + `Retry-After` if Kafka is unavailable. |
| `GET` | `/stream` | Snapshot of all known matches (JSON). |
| `GET` | `/stream/{matchId}` | Snapshot of one match, `404` if unknown. |
| `GET` | `/stream/live?matchId=…` | Server-Sent Events: a `snapshot` event on connect, then one `match-event` per new event; heartbeat every 15 s. |
| `GET` | `/actuator/health` | Health check (Kubernetes probes). |

Example event:

```json
{ "matchId": "m1", "type": "GOAL", "minute": 23, "team": "Tigres", "description": "Header from a corner" }
```

Event types: `KICKOFF`, `GOAL`, `YELLOW_CARD`, `RED_CARD`, `SUBSTITUTION`, `HALF_TIME`, `FULL_TIME` (goals, cards and substitutions require `team`).

## Repository structure

```plaintext
.devcontainer/      Dev container: Java 21, Maven, Docker-in-Docker, kubectl, kind, Terraform, AWS CLI
docs/               PLAN.md, ADRs
loadtest/k6.js      Load test
src/                Maven project (Spring Boot service)
  Dockerfile
  infra/            Terraform (AWS)
  src/main/java/com/streaming/
    api/            REST + SSE controllers, Problem Details handler
    domain/         MatchEvent, MatchState (immutable records)
    kafka/          Topic config, producer adapter, fan-out consumer
docker-compose.yml  Local Kafka (and, later, the observability stack)
```

## Local environment

Everything runs inside the **dev container** (VS Code → *Dev Containers: Reopen in Container*), so no JDK, Maven or Terraform is needed on the host.

> Docker Desktop on Windows: disable *Resource Saver* and enable *WSL integration* for your distro — see "Lessons learned" in `docs/PLAN.md`.

1. **Start Kafka**
   ```bash
   docker compose up -d
   docker compose ps          # wait for kafka (healthy)
   ```
2. **Run the service**
   ```bash
   cd src
   ./mvnw spring-boot:run
   ```
3. **Try it**
   ```bash
   # terminal A: follow a match live
   curl -N "localhost:8080/stream/live?matchId=m1"

   # terminal B: publish events
   curl -s -X POST localhost:8080/events -H "Content-Type: application/json" \
     -d '{"matchId":"m1","type":"KICKOFF","minute":0}'
   curl -s -X POST localhost:8080/events -H "Content-Type: application/json" \
     -d '{"matchId":"m1","type":"GOAL","minute":23,"team":"Tigres"}'

   # snapshot
   curl -s localhost:8080/stream/m1
   ```
4. **Fan-out across instances:** start a second instance with `./mvnw spring-boot:run -Dspring-boot.run.arguments=--server.port=8081`; an event posted to one instance reaches SSE clients connected to the other.

> `docker compose stop/start` keeps the events; `docker compose down` deletes them (no volume for Kafka data).

## Tests

```bash
cd src
./mvnw -B verify
```

| Level | What | Tools |
| --- | --- | --- |
| Unit | Match state rules, validation, streaming service, Kafka adapter failure paths | JUnit 5, AssertJ, Mockito |
| Web slice | HTTP contract: 202/400/404/503, Problem Details, SSE delivery | `@WebMvcTest`, MockMvc |
| Integration | `POST` → real Kafka → consumer → snapshot; poison messages are skipped | Testcontainers (`apache/kafka`), Awaitility |

Integration tests start their own Kafka container — `docker compose` is not needed to run the test suite.

## Cloud deployment (AWS)

Planned for Days 7–8 (see `docs/PLAN.md`): Terraform with the `terraform-aws-modules` VPC and EKS modules plus ECR, applied, smoke-tested and **destroyed in the same session** to keep the cost around $1–2.

```bash
cd src/infra
terraform init
terraform plan -out tfplan
terraform apply tfplan
# ... deploy, smoke test, load test ...
kubectl delete -f ../k8s/      # delete load balancers first
terraform destroy
```

Go-live checklist: EKS cluster active, Kafka healthy, pods ready, load test and monitoring reviewed before sending real traffic.

## Load testing

- **Tool:** k6 (`loadtest/k6.js`, run with the `grafana/k6` Docker image).
- **Scenario:** ramp-up of concurrent virtual users against `GET /stream`, plus an ingest scenario on `POST /events`.
- **Run:**
  ```bash
  docker run --rm -i --network host grafana/k6 run - < loadtest/k6.js
  ```
- **Observe:** Grafana dashboards (OpenTelemetry metrics and traces) during the run.

## Target metrics

| Metric | Target | How to measure | Result |
| --- | --- | --- | --- |
| Snapshot throughput (`GET /stream`) | Max sustained RPS per pod | k6 + Grafana | *pending* |
| Latency | p95 < 200 ms, p99 < 300 ms | k6 summary + OTel histogram | *pending* |
| Event delivery latency (produce → SSE) | p99 < 200 ms | `streaming.event_delivery_latency_ms` | *pending* |
| Error rate | < 1% (stretch < 0.1%) | k6 `http_req_failed` | *pending* |
| Saturation point | RPS where p99 > 300 ms | Ramp test | *pending* |
| Horizontal scaling | HPA scales out and back in | `kubectl get hpa -w` | *pending* |

## Technical challenges

### Low latency in real-time delivery
**Why it is hard:** a goal must reach every viewer within milliseconds, regardless of how many are connected.
**Approach:** SSE over plain HTTP, events pushed as soon as they are consumed; Kafka producer tuned for latency (`linger.ms=5`, `lz4`); delivery latency measured as a metric.

### Ordering and consistency
**Why it is hard:** Kafka only guarantees order within a partition, and pods restart.
**Approach:** key = `matchId` (all events of a match in one partition); immutable match state rebuilt by replaying the topic on startup. See [ADR 0002](docs/adr/0002-kafka-keys-partitions-and-producer-settings.md).

### Horizontal scalability
**Why it is hard:** each client is connected to a single pod, but every pod must deliver every event.
**Approach:** one consumer group per pod (broadcast), stateless HTTP layer, Kubernetes HPA. See [ADR 0001](docs/adr/0001-sse-and-fanout-consumer-groups.md).

### Failure handling
**Why it is hard:** a slow or unavailable broker must not freeze the API or lose events silently.
**Approach:** `acks=all` + idempotent producer, 5 s fail-fast timeouts with `503` + `Retry-After`, poison messages skipped instead of blocking a partition.

### Observability
**Why it is hard:** with many distributed components, it is easy to lose visibility.
**Approach:** OpenTelemetry Java agent for traces across HTTP and Kafka, custom metrics, Grafana dashboards.

## Takeaways

*To be completed at the end of the project with measured results.*

- Built a horizontally scalable event-streaming service with Spring Boot and Kafka.
- Applied low-latency delivery techniques (SSE, producer tuning, fail-fast).
- Used OpenTelemetry to monitor and optimize system performance.
- Deployed and operated the application on Kubernetes (kind locally, EKS on AWS).
- Integrated Kafka for real-time event delivery with ordering guarantees.

## Learning path

### Java
Java is a robust, widely used language, especially for backend systems.

1. **Java fundamentals** — syntax and object-oriented concepts.
2. **Spring Boot** — building web applications.
3. **Kafka integration** — producing and consuming messages with Apache Kafka.
4. **Deploying to AWS EKS** — packaging and deploying Java applications on Kubernetes.
5. **Monitoring with OpenTelemetry** — observability practices for Java applications.

Resources:
- [Dev.java – Learn Java](https://dev.java/learn/)
- [Spring Boot documentation](https://spring.io/projects/spring-boot)
- [Kafka documentation](https://kafka.apache.org/documentation/)
- [AWS EKS Workshop](https://eksworkshop.com/)
- [OpenTelemetry Java](https://opentelemetry.io/docs/languages/java/)

### AWS EKS
EKS runs containerized applications at scale, which is key for distributed systems.

1. **Kubernetes fundamentals** — core concepts.
2. **EKS setup** — creating and managing a cluster.
3. **Application deployment** — deploying applications to the cluster.
4. **Autoscaling and resource management** — HPA, requests/limits.
5. **Monitoring and logging** — observability on EKS.

Resources:
- [Kubernetes basics](https://kubernetes.io/docs/tutorials/kubernetes-basics/)
- [AWS EKS documentation](https://docs.aws.amazon.com/eks/latest/userguide/what-is-eks.html)
- [EKS Best Practices](https://docs.aws.amazon.com/eks/latest/best-practices/introduction.html)
