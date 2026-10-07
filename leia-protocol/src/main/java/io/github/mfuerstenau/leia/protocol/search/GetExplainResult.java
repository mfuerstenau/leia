package io.github.mfuerstenau.leia.protocol.search;

import java.util.Optional;

/**
 * Result of {@code getExplain}: the outcome of an explanation once it is done.
 *
 * @param explanation the outcome; absent while the explanation is running
 */
public record GetExplainResult(Optional<Explanation> explanation) {}
