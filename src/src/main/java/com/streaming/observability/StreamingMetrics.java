package com.streaming.observability;

import com.streaming.StreamingService;
import com.streaming.domain.MatchEvent;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Business metrics, recorded with the OpenTelemetry API
 * (same concepts as System.Diagnostics.Metrics in .NET: Meter, Counter, Histogram, ObservableGauge).
 * <p>
 * At runtime the OTel Java agent provides the SDK behind {@link GlobalOpenTelemetry} and exports
 * these metrics via OTLP. Without the agent (unit tests) the API is a no-op.
 * <p>
 * Prometheus names (after OTLP -> Prometheus conversion):
 * <ul>
 *   <li>streaming_sse_active_clients</li>
 *   <li>streaming_event_delivery_latency_milliseconds_bucket / _sum / _count</li>
 *   <li>streaming_events_delivered_total</li>
 *   <li>streaming_events_replayed_total</li>
 * </ul>
 */
@Component
public class StreamingMetrics {

    private static final AttributeKey<String> EVENT_TYPE = AttributeKey.stringKey("event.type");

    private final DoubleHistogram deliveryLatency;
    private final LongCounter eventsDelivered;
    private final LongCounter eventsReplayed;

    /**
     * Events created before this instance started are a replay of the topic (state rebuild on
     * startup, see ADR 0001), not live deliveries: their "latency" would be minutes or hours.
     */
    private final Instant startedAt = Instant.now();

    public StreamingMetrics(StreamingService streamingService) {
        Meter meter = GlobalOpenTelemetry.getMeter("com.streaming");

        // Gauge: read on every export (every 10 s locally), no bookkeeping needed.
        meter.gaugeBuilder("streaming.sse.active_clients")
                .setDescription("SSE clients currently connected to this instance")
                .setUnit("{client}")
                .ofLongs()
                .buildWithCallback(measurement -> measurement.record(streamingService.activeSubscribers()));

        // Histogram in ms: the default OTel buckets (0, 5, 10, 25, 50, 75, 100, 250, ...) fit milliseconds.
        deliveryLatency = meter.histogramBuilder("streaming.event.delivery_latency")
                .setDescription("Time from event creation (POST /events) until it is pushed to SSE clients")
                .setUnit("ms")
                .build();

        eventsDelivered = meter.counterBuilder("streaming.events.delivered")
                .setDescription("Match events applied and pushed to SSE clients")
                .setUnit("{event}")
                .build();

        eventsReplayed = meter.counterBuilder("streaming.events.replayed")
                .setDescription("Events re-applied from Kafka on startup to rebuild the match state")
                .setUnit("{event}")
                .build();
    }

    /** Call after the event has been applied and broadcast to the SSE clients of this instance. */
    public void recordDelivered(MatchEvent event) {
        Attributes attributes = Attributes.of(EVENT_TYPE, event.type().name());

        if (event.timestamp().isBefore(startedAt)) {
            // Startup replay: count it, but keep it out of the live delivery latency.
            eventsReplayed.add(1, attributes);
            return;
        }

        // event.timestamp() is set by the server on POST unless the client sends one;
        // clamp to 0 so client clock skew never produces negative latencies.
        long latencyMs = Math.max(0, Duration.between(event.timestamp(), Instant.now()).toMillis());

        deliveryLatency.record(latencyMs, attributes);
        eventsDelivered.add(1, attributes);
    }
}
