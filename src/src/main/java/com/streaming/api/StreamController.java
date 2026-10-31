package com.streaming.api;

import com.streaming.StreamingService;
import com.streaming.domain.MatchState;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;

/**
 * Delivery endpoints for clients.
 * <ul>
 *   <li>GET /stream            - snapshot of all live matches (what loadtest/k6.js hits);
 *                                pre-rendered JSON + ETag, cacheable for 1 s by browsers/CDNs</li>
 *   <li>GET /stream/{matchId}  - snapshot of one match (404 if unknown)</li>
 *   <li>GET /stream/live?matchId=... - Server-Sent Events: pushes each new event as it happens</li>
 * </ul>
 */
@RestController
@RequestMapping("/stream")
public class StreamController {

    /** Live data: a browser/CDN may reuse the snapshot for 1 s, then must revalidate (ETag). */
    private static final CacheControl SNAPSHOT_CACHE = CacheControl.maxAge(Duration.ofSeconds(1)).cachePublic();

    private final StreamingService streamingService;
    private final StreamSnapshotCache snapshotCache;

    public StreamController(StreamingService streamingService, StreamSnapshotCache snapshotCache) {
        this.streamingService = streamingService;
        this.snapshotCache = snapshotCache;
    }

    /**
     * Returns the pre-rendered bytes: gzip-compressed if the client accepts it (browsers and k6 do),
     * plain JSON otherwise. Spring compares the ETag with the request's If-None-Match header and
     * answers 304 Not Modified (no body) when they match.
     */
    @GetMapping
    public ResponseEntity<byte[]> snapshot(
            @RequestHeader(value = HttpHeaders.ACCEPT_ENCODING, required = false) String acceptEncoding) {
        StreamSnapshotCache.Snapshot snapshot = snapshotCache.current();
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .cacheControl(SNAPSHOT_CACHE)
                .varyBy(HttpHeaders.ACCEPT_ENCODING); // caches/CDNs keep one copy per encoding

        if (acceptsGzip(acceptEncoding)) {
            return response
                    .eTag(snapshot.gzipEtag())
                    .header(HttpHeaders.CONTENT_ENCODING, "gzip")
                    .body(snapshot.gzip());
        }
        return response.eTag(snapshot.etag()).body(snapshot.json());
    }

    /** True for "gzip", "gzip, deflate, br", "gzip;q=0.8"...; false for "gzip;q=0" or no header. */
    static boolean acceptsGzip(String acceptEncoding) {
        if (acceptEncoding == null) {
            return false;
        }
        for (String part : acceptEncoding.split(",")) {
            String[] tokens = part.trim().split(";");
            if (tokens[0].trim().equalsIgnoreCase("gzip")) {
                for (int i = 1; i < tokens.length; i++) {
                    String param = tokens[i].trim().replace(" ", "");
                    if (param.equals("q=0") || param.matches("q=0\\.0+")) {
                        return false;
                    }
                }
                return true;
            }
        }
        return false;
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
