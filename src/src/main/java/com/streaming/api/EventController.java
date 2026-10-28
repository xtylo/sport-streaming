package com.streaming.api;

import com.streaming.MatchEventPublisher;
import com.streaming.domain.MatchEvent;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ingest endpoint: receives match events from producers (scorers, feeds, admin tools)
 * and publishes them to the event bus (Kafka). Delivery to SSE clients happens
 * asynchronously in the Kafka consumer.
 */
@RestController
@RequestMapping("/events")
public class EventController {

    private final MatchEventPublisher publisher;

    public EventController(MatchEventPublisher publisher) {
        this.publisher = publisher;
    }

    @PostMapping
    public ResponseEntity<MatchEvent> publish(@Valid @RequestBody MatchEvent event) {
        publisher.publish(event);
        // 202 Accepted: stored in Kafka, delivered to subscribers asynchronously.
        return ResponseEntity.accepted().body(event);
    }
}
