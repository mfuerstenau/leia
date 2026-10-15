package io.github.mfuerstenau.leia.server.runtime;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.stream.Stream;

/**
 * Tells an index written before Lucene 4.0 by the first int of its newest {@code segments_N}, without
 * Lucene (ADR 0044). An interim detector: the routing task replaces it with one that finds the major
 * version of any index.
 */
public final class SegmentsHeader {

    /** The oldest {@code segments_N} format that Lucene 3.6 reads: 2.1, the first with {@code segments_N}. */
    private static final int FORMAT_LOCKLESS = -2;
    /** The newest {@code segments_N} format before codecs: 3.1 to 3.6. */
    private static final int FORMAT_3_1 = -11;

    private SegmentsHeader() {}

    /**
     * Whether {@code dir} holds an index of Lucene 2.1 to 3.6: one whose newest {@code segments_N} file
     * starts with a format of those versions, not with the header that every Lucene since 4.0 writes.
     * Such an index counts as major 3 for picking a runtime.
     */
    public static boolean isPreCodec(Path dir) {
        Optional<Path> segments = newestSegmentsFile(dir);
        if (segments.isEmpty()) {
            return false;
        }
        try (InputStream in = Files.newInputStream(segments.get());
                DataInputStream data = new DataInputStream(in)) {
            int format = data.readInt();
            return format <= FORMAT_LOCKLESS && format >= FORMAT_3_1;
        } catch (IOException e) {
            return false;
        }
    }

    private static Optional<Path> newestSegmentsFile(Path dir) {
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(file -> generation(file).isPresent())
                    .max(Comparator.comparingLong(file -> generation(file).orElseThrow()));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static OptionalLong generation(Path file) {
        String name = file.getFileName().toString();
        if (!name.startsWith("segments_")) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(Long.parseLong(name.substring("segments_".length()), Character.MAX_RADIX));
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }
}
