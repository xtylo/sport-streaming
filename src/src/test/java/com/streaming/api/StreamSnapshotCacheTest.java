package com.streaming.api;

import com.streaming.StreamingService;
import com.streaming.domain.MatchEvent;
import com.streaming.domain.MatchEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class StreamSnapshotCacheTest {

    private StreamingService service;
    private StreamSnapshotCache cache;

    @BeforeEach
    void setUp() {
        service = new StreamingService();
        cache = new StreamSnapshotCache(service, JsonMapper.builder().build());
    }

    private void goal(String matchId, String team) {
        service.onEventReceived(new MatchEvent(null, matchId, MatchEventType.GOAL, 10, team, null, null));
    }

    @Test
    @DisplayName("without state changes the same rendered bytes are reused (no re-serialization)")
    void reusesSnapshotWhileUnchanged() {
        goal("m1", "Tigres");

        StreamSnapshotCache.Snapshot first = cache.current();
        StreamSnapshotCache.Snapshot second = cache.current();

        assertThat(second).isSameAs(first);
        assertThat(second.json()).isSameAs(first.json());
    }

    @Test
    @DisplayName("a state change produces a new snapshot with new content and a new ETag")
    void rendersAgainAfterChange() {
        goal("m1", "Tigres");
        StreamSnapshotCache.Snapshot before = cache.current();

        goal("m1", "Tigres");
        StreamSnapshotCache.Snapshot after = cache.current();

        assertThat(after).isNotSameAs(before);
        assertThat(after.etag()).isNotEqualTo(before.etag());
        assertThat(new String(after.json(), StandardCharsets.UTF_8)).contains("\"Tigres\":2");
    }

    @Test
    @DisplayName("the ETag depends on the content, so equal state gives an equal ETag")
    void etagIsContentBased() {
        StreamingService other = new StreamingService();
        StreamSnapshotCache otherCache = new StreamSnapshotCache(other, JsonMapper.builder().build());

        // Same JSON content in two independent instances -> same ETag
        assertThat(cache.current().etag()).isEqualTo(otherCache.current().etag());
        assertThat(cache.current().etag()).startsWith("\"").endsWith("\"");
    }

    @Test
    @DisplayName("the gzip representation decompresses to the same JSON, is smaller, and has its own ETag")
    void gzipRepresentation() throws Exception {
        for (int i = 1; i <= 20; i++) {
            goal("m" + i, "Tigres");
        }
        StreamSnapshotCache.Snapshot snapshot = cache.current();

        byte[] unzipped;
        try (var in = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(snapshot.gzip()))) {
            unzipped = in.readAllBytes();
        }
        assertThat(unzipped).isEqualTo(snapshot.json());
        assertThat(snapshot.gzip().length).isLessThan(snapshot.json().length);
        assertThat(snapshot.gzipEtag()).isNotEqualTo(snapshot.etag()).endsWith("-gzip\"");
    }

    @Test
    @DisplayName("Accept-Encoding parsing honours q=0 and missing headers")
    void acceptEncodingParsing() {
        assertThat(StreamController.acceptsGzip("gzip")).isTrue();
        assertThat(StreamController.acceptsGzip("gzip, deflate, br")).isTrue();
        assertThat(StreamController.acceptsGzip("br;q=1.0, GZIP;q=0.5")).isTrue();
        assertThat(StreamController.acceptsGzip("gzip;q=0")).isFalse();
        assertThat(StreamController.acceptsGzip("gzip; q=0.000")).isFalse();
        assertThat(StreamController.acceptsGzip("deflate, br")).isFalse();
        assertThat(StreamController.acceptsGzip(null)).isFalse();
    }

    @Test
    @DisplayName("an empty service renders an empty JSON array")
    void emptySnapshot() {
        assertThat(new String(cache.current().json(), StandardCharsets.UTF_8)).isEqualTo("[]");
    }
}
