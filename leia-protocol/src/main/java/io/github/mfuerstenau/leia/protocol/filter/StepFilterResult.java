package io.github.mfuerstenau.leia.protocol.filter;

import java.util.Optional;

/**
 * Result of {@code stepFilter}.
 *
 * @param match the matching document found; absent if there is none in that direction
 */
public record StepFilterResult(Optional<FilterMatch> match) {}
