package com.streaming.api;

import com.streaming.StreamingService;
import com.streaming.domain.MatchEvent;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ingest endpoint: receives match events from producers (scorers, feeds, admin tools).
 * <p>
 * Day 2: calls {@link StreamingService} directly.
 * Day 3: publishes to Kafka instead; the HTTP contract (202 + event) stays the same.
 */
@RestController
@RequestMapping("/events")
public class EventController {

    private final StreamingService streamingService;

    public EventController(StreamingService streamingService) {
        this.streamingService = streamingService;
    }

    @PostMapping
    public ResponseEntity<MatchEvent> publish(@Valid @RequestBody MatchEvent event) {
        streamingService.onEventReceived(event);
        // 202 Accepted: the event is accepted for delivery (asynchronous once Kafka is in place).
        return ResponseEntity.accepted().body(event);
    }
}
