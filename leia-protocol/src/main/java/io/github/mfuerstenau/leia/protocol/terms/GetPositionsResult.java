package io.github.mfuerstenau.leia.protocol.terms;

import io.github.mfuerstenau.leia.protocol.document.TermPosition;
import java.util.List;

/**
 * Result of {@code getPositions}.
 *
 * @param positions one entry per occurrence of the term in the document, in order, with offsets
 *     and payloads as far as they were indexed; empty if positions are not indexed
 */
public record GetPositionsResult(List<TermPosition> positions) {}
