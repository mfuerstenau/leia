package io.github.mfuerstenau.leia.protocol.filter;

/**
 * Params of {@code stepFilter} (ADR 0031).
 *
 * @param filterId the filter, as {@code getFilter} returned it
 * @param fromDoc the document id to look from; it counts as a match itself. Any number is taken:
 *     going forward from below 0 finds the first match, going back from maxDoc or beyond the last
 * @param forward whether to look for the first match from {@code fromDoc} on, or the last one up to
 *     it
 */
public record StepFilterParams(long filterId, int fromDoc, boolean forward) {}
