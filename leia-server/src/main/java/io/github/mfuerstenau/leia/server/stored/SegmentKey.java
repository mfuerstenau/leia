package io.github.mfuerstenau.leia.server.stored;

import io.github.mfuerstenau.leia.runtime.RuntimeIndex;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * What identifies a segment's stored fields in the cache (ADR 0113). A segment's stored fields never
 * change once it is written, so a segment with an id, as Lucene records one since 5.0, is that id
 * wherever the index is; a copy of the index has the same data. A segment without one is its
 * directory, name and number of documents, and the size and modification time of each of its files.
 *
 * @param value the key as text, which the cache file repeats to rule out a clash of its name
 */
record SegmentKey(String value) {

    /** The key of {@code segment} of the index in {@code directory}; empty if it can't have one. */
    static Optional<SegmentKey> of(Path directory, RuntimeIndex.Segment segment) {
        if (segment.id() != null) {
            return Optional.of(new SegmentKey("id " + segment.id() + " " + segment.maxDoc()));
        }
        if (segment.name().equals("?") || segment.files().isEmpty()) {
            return Optional.empty();
        }
        try {
            StringBuilder key = new StringBuilder("files ")
                    .append(directory.toRealPath())
                    .append(' ')
                    .append(segment.name())
                    .append(' ')
                    .append(segment.maxDoc());
            for (String file : segment.files()) {
                Path path = directory.resolve(file);
                key.append(' ')
                        .append(file)
                        .append(' ')
                        .append(Files.size(path))
                        .append(' ')
                        .append(Files.getLastModifiedTime(path).toMillis());
            }
            return Optional.of(new SegmentKey(key.toString()));
        } catch (IOException _) {
            // A file that can't be stat'ed: the segment is read, not cached.
            return Optional.empty();
        }
    }

    /** The name of the cache file: the first 128 bits of the key's SHA-256, in hex. */
    String fileName() {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 16) + ".bin";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java has SHA-256", e);
        }
    }
}
