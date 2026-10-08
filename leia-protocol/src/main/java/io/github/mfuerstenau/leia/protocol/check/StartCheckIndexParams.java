package io.github.mfuerstenau.leia.protocol.check;

import java.util.OptionalInt;

/**
 * Params of {@code startCheckIndex} (ADR 0022).
 *
 * @param level how thoroughly to check, from {@code MIN_LEVEL} to {@code MAX_LEVEL}: 1 the checksums
 *     of every file, 2 also the integrity of every part of each segment, 3 also the slow checks;
 *     absent means {@code DEFAULT_LEVEL}
 */
public record StartCheckIndexParams(OptionalInt level) {

    public static final int MIN_LEVEL = 1;
    public static final int MAX_LEVEL = 3;
    /** Lucene's default: the checksums. */
    public static final int DEFAULT_LEVEL = MIN_LEVEL;

    /** A check at the default level. */
    public StartCheckIndexParams() {
        this(OptionalInt.empty());
    }
}
