# syntax=docker/dockerfile:1
#
# Multi-stage image for streaming-service.
# Build context: the src/ folder (Maven project root).
#
#   docker build -t streaming-service:dev src
#
# Stage 1 (build):   compile + package with the Maven wrapper (also downloads the OTel agent to target/otel)
# Stage 2 (extract): split the Spring Boot jar into layers (dependencies change rarely, app code often)
# Stage 3 (runtime): small JRE image, non-root user, OTel agent attached
#
# For production, pin base images by digest (e.g. eclipse-temurin:21-jre@sha256:...).

# ---------- 1. Build ----------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Maven wrapper + pom first: this layer is cached until pom.xml changes.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw

# BuildKit cache mount: the ~/.m2 repository survives between builds (like a NuGet cache).
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline

COPY src/ src/
# Tests run in CI / locally (they need Docker for Testcontainers), not inside the image build.
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -DskipTests package

# ---------- 2. Extract layers ----------
FROM eclipse-temurin:21-jre AS extract
WORKDIR /extract
COPY --from=build /workspace/target/streaming-service.jar application.jar
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

# ---------- 3. Runtime ----------
FROM eclipse-temurin:21-jre
WORKDIR /application

# Non-root user (Kubernetes best practice: runAsNonRoot).
RUN groupadd --system --gid 10001 app \
 && useradd --system --uid 10001 --gid app --no-create-home app

# Least-changing layers first, so a code change only rebuilds the last (small) layer.
COPY --from=extract /extract/extracted/dependencies/ ./
COPY --from=extract /extract/extracted/spring-boot-loader/ ./
COPY --from=extract /extract/extracted/snapshot-dependencies/ ./
COPY --from=extract /extract/extracted/application/ ./

# OpenTelemetry Java agent (downloaded by Maven in the build stage).
COPY --from=build /workspace/target/otel/opentelemetry-javaagent.jar /otel/opentelemetry-javaagent.jar

# JVM:
# - Heap = 65% of the container memory limit, leaving room for non-heap memory (metaspace,
#   code cache, thread stacks, network buffers, OTel agent) in small containers (< 1 GiB).
# - Serial GC explicitly. Measured on Day 6 (docs/RESULTS.md), same-day baseline on 1 CPU with a
#   ~250 MiB heap: G1 was worse than Serial (GET p99 57 vs 43 ms, max 398 vs 101 ms; POST p99
#   75 vs 47 ms): G1's concurrent work competes with requests for the single core. Set
#   explicitly so the GC does not change silently when the pod sees more CPUs (no CPU limit on
#   Kubernetes).
#   Revisit (G1/ZGC) if the pod gets >= 2 dedicated CPUs or a multi-GB heap.
# - The OTel Java agent.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65 -XX:+UseSerialGC -javaagent:/otel/opentelemetry-javaagent.jar"

# OTel defaults; the endpoint and environment are set by docker compose / Kubernetes.
ENV OTEL_SERVICE_NAME=streaming-service \
    OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf \
    OTEL_METRIC_EXPORT_INTERVAL=10000 \
    OTEL_INSTRUMENTATION_SPRING_SCHEDULING_ENABLED=false

USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "application.jar"]
