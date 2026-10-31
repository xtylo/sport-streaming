package com.streaming.api;

import com.streaming.StreamingService;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.zip.GZIPOutputStream;

/**
 * Read model for GET /stream: the JSON of all matches, serialized (and gzip-compressed) once per
 * state change instead of once per request (CQRS: writes are ~20/s, reads are ~1000/s).
 * <p>
 * Lazy and lock-protected: the first request after a change renders the snapshot; concurrent
 * requests wait for it instead of rendering the same JSON in parallel; every later request
 * gets the same bytes until the next change.
 * <p>
 * The ETag is a hash of the content (not the version number): versions are per instance, so two
 * pods could have the same version with different content, while equal hashes mean equal JSON.
 * The gzip representation has its own ETag, as HTTP requires for different representations.
 */
@Component
public class StreamSnapshotCache {

    /** Immutable rendered snapshot: safe to hand to many request threads. */
    public record Snapshot(long version, byte[] json, String etag, byte[] gzip, String gzipEtag) {
    }

    private final StreamingService streamingService;
    private final JsonMapper jsonMapper;

    private volatile Snapshot current = new Snapshot(-1, new byte[0], "\"empty\"", new byte[0], "\"empty-gzip\"");

    public StreamSnapshotCache(StreamingService streamingService, JsonMapper jsonMapper) {
        this.streamingService = streamingService;
        this.jsonMapper = jsonMapper;
    }

    /** The snapshot for the current state, rendering it only if the state changed. */
    public Snapshot current() {
        Snapshot snapshot = current;
        long latest = streamingService.version();
        if (snapshot.version() == latest) {
            return snapshot; // hot path: no lock, no serialization, no compression
        }
        synchronized (this) {
            snapshot = current;
            if (snapshot.version() != streamingService.version()) {
                // Read the version BEFORE taking the snapshot: if a change lands while rendering,
                // the stored version is older than the content, so the next call simply re-renders.
                long version = streamingService.version();
                byte[] json = jsonMapper.writeValueAsBytes(streamingService.snapshot());
                String etag = etagOf(json);
                String gzipEtag = etag.substring(0, etag.length() - 1) + "-gzip\"";
                snapshot = new Snapshot(version, json, etag, gzip(json), gzipEtag);
                current = snapshot;
            }
            return snapshot;
        }
    }

    private static byte[] gzip(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, data.length / 8));
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(data);
        } catch (IOException e) {
            throw new UncheckedIOException("in-memory gzip failed", e);
        }
        return out.toByteArray();
    }

    private static String etagOf(byte[] json) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(json);
            return "\"" + HexFormat.of().formatHex(hash, 0, 16) + "\"";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available in the JDK", e);
        }
    }
}
