package io.github.mfuerstenau.leia.protocol.check;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Params of {@code startCheckIndex} (ADR 0022, ADR 0093).
 *
 * @param level how thoroughly to check, from {@code MIN_LEVEL} to {@code MAX_LEVEL}: 1 the checksums
 *     of every file, 2 also the integrity of every part of each segment, 3 also the slow checks;
 *     absent means {@code DEFAULT_LEVEL}
 * @param threads how many threads check segments at once, from {@code MIN_THREADS} to the processors
 *     of the server's JVM; absent means all the processors. A runtime without {@code CHECK_THREADS}
 *     ignores it
 * @param only the indexes, and in them the segments, to check; absent means every index with every
 *     segment
 */
public record StartCheckIndexParams(OptionalInt level, OptionalInt threads, Optional<List<CheckTarget>> only) {

    public static final int MIN_LEVEL = 1;
    public static final int MAX_LEVEL = 3;
    /** Lucene's default: the checksums. */
    public static final int DEFAULT_LEVEL = MIN_LEVEL;

    public static final int MIN_THREADS = 1;

    /** A check at the default level, of everything, on every processor. */
    public StartCheckIndexParams() {
        this(OptionalInt.empty());
    }

    /** A check of everything at {@code level}, on every processor. */
    public StartCheckIndexParams(OptionalInt level) {
        this(level, OptionalInt.empty(), Optional.empty());
    }
}
