package io.github.mfuerstenau.leia.protocol.search;

import java.util.Optional;

/**
 * Result of {@code getHighlight}: the outcome of a highlight once it is done (ADR 0042).
 *
 * @param highlight the outcome; absent while the highlight is running
 */
public record GetHighlightResult(Optional<DocumentHighlight> highlight) {}
