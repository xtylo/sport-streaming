package com.streaming;

import com.streaming.domain.MatchEvent;
import com.streaming.domain.MatchState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Core service for live sports event streaming.
 * <ul>
 *   <li>Keeps the latest {@link MatchState} per match in memory (served by GET /stream).</li>
 *   <li>Keeps the SSE subscribers per match and pushes every new event to them.</li>
 * </ul>
 * On Day 3 {@link #onEventReceived(MatchEvent)} will be called by the Kafka consumer
 * instead of the REST controller; nothing else in this class changes.
 */
@Service
public class StreamingService {

    private static final Logger log = LoggerFactory.getLogger(StreamingService.class);

    /** Clients (EventSource) reconnect automatically after a timeout. */
    static final Duration SSE_TIMEOUT = Duration.ofMinutes(30);

    private final Map<String, MatchState> states = new ConcurrentHashMap<>();
    private final Map<String, List<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    /** Applies an event to the match state and broadcasts it to the match subscribers. */
    public MatchState onEventReceived(MatchEvent event) {
        MatchState updated = states.compute(event.matchId(),
                (id, current) -> (current != null ? current : MatchState.initial(id)).apply(event));

        List<SseEmitter> emitters = subscribers.get(event.matchId());
        if (emitters != null) {
            for (SseEmitter emitter : emitters) {
                // A builder can't be reused across emitters, so build one per send.
                trySend(event.matchId(), emitter, SseEmitter.event()
                        .id(event.eventId().toString())
                        .name("match-event")
                        .data(event));
            }
        }
        return updated;
    }

    /** Snapshot of all known matches. */
    public Collection<MatchState> snapshot() {
        return List.copyOf(states.values());
    }

    /** Snapshot of a single match. */
    public Optional<MatchState> snapshot(String matchId) {
        return Optional.ofNullable(states.get(matchId));
    }

    /** Registers a new SSE client for a match and sends it the current state right away. */
    public SseEmitter subscribe(String matchId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT.toMillis());
        emitter.onCompletion(() -> unsubscribe(matchId, emitter));
        emitter.onTimeout(() -> {
            unsubscribe(matchId, emitter);
            emitter.complete();
        });
        emitter.onError(ex -> unsubscribe(matchId, emitter));

        subscribers.compute(matchId, (id, list) -> {
            List<SseEmitter> target = list != null ? list : new CopyOnWriteArrayList<>();
            target.add(emitter);
            return target;
        });

        MatchState current = states.get(matchId);
        if (current != null) {
            trySend(matchId, emitter, SseEmitter.event().name("snapshot").data(current));
        }
        return emitter;
    }

    /** Number of connected SSE clients (exposed as a metric on Day 4). */
    public int activeSubscribers() {
        return subscribers.values().stream().mapToInt(List::size).sum();
    }

    /**
     * Keeps idle connections alive through proxies/load balancers (ALB idle timeout is 60s)
     * and detects disconnected clients, which fail on send.
     */
    @Scheduled(fixedRateString = "${streaming.sse.heartbeat-ms:15000}")
    void heartbeat() {
        subscribers.forEach((matchId, emitters) ->
                emitters.forEach(emitter -> trySend(matchId, emitter, SseEmitter.event().comment("heartbeat"))));
    }

    private void trySend(String matchId, SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (IOException | IllegalStateException ex) {
            // Client went away (or emitter already completed): drop it.
            log.debug("Removing SSE subscriber for match {}: {}", matchId, ex.getMessage());
            unsubscribe(matchId, emitter);
            emitter.completeWithError(ex);
        }
    }

    private void unsubscribe(String matchId, SseEmitter emitter) {
        subscribers.computeIfPresent(matchId, (id, list) -> {
            list.remove(emitter);
            return list.isEmpty() ? null : list;
        });
    }
}
