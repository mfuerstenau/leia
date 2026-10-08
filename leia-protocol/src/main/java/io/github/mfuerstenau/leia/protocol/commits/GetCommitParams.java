package io.github.mfuerstenau.leia.protocol.commits;

import java.util.OptionalInt;

/**
 * Params of {@code getCommit}.
 *
 * @param generation the generation of the commit, the {@code N} in {@code segments_N}
 * @param index the position of the index directory in {@code openIndex}' {@code indexes} (ADR
 *     0021); absent means 0
 */
public record GetCommitParams(long generation, OptionalInt index) {

    /** The commit of the first index directory. */
    public GetCommitParams(long generation) {
        this(generation, OptionalInt.empty());
    }
}
