package com.streaming.api;

import com.streaming.StreamingService;
import com.streaming.domain.MatchState;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Collection;

/**
 * Delivery endpoints for clients.
 * <ul>
 *   <li>GET /stream            - snapshot of all live matches (what loadtest/k6.js hits)</li>
 *   <li>GET /stream/{matchId}  - snapshot of one match (404 if unknown)</li>
 *   <li>GET /stream/live?matchId=... - Server-Sent Events: pushes each new event as it happens</li>
 * </ul>
 */
@RestController
@RequestMapping("/stream")
public class StreamController {

    private final StreamingService streamingService;

    public StreamController(StreamingService streamingService) {
        this.streamingService = streamingService;
    }

    @GetMapping
    public Collection<MatchState> snapshot() {
        return streamingService.snapshot();
    }

    @GetMapping(path = "/live", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter live(@RequestParam @NotBlank @Size(max = 64) String matchId) {
        return streamingService.subscribe(matchId);
    }

    @GetMapping("/{matchId}")
    public ResponseEntity<MatchState> match(@PathVariable String matchId) {
        // 200 with the state, or 404 when the Optional is empty.
        return ResponseEntity.of(streamingService.snapshot(matchId));
    }
}
