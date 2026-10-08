package io.github.mfuerstenau.leia.protocol.commits;

import java.util.OptionalInt;

/**
 * Params of {@code getCommits}: it lists the commits of an index directory of the open path.
 *
 * @param index the position of the index directory in {@code openIndex}' {@code indexes} (ADR
 *     0021); absent means 0
 */
public record GetCommitsParams(OptionalInt index) {

    /** The commits of the first index directory. */
    public GetCommitsParams() {
        this(OptionalInt.empty());
    }
}
