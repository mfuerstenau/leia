package io.github.mfuerstenau.leia.core;

import org.apache.lucene.analysis.Analyzer;

/**
 * How the matches of a query are highlighted in stored text (ADR 0042).
 *
 * @param maxPassages the most passages per field and hit, at least 1; not used for one document's
 *     matches
 * @param requireFieldMatch whether a field is highlighted only with the query's terms in that field
 * @param analyzer analyzes a value where the offsets of its terms come from analysis; its offset gap
 *     must be 1, Lucene's default
 */
public record HighlightSpec(int maxPassages, boolean requireFieldMatch, Analyzer analyzer) {

    public HighlightSpec {
        if (maxPassages < 1) {
            throw new IllegalArgumentException("maxPassages must be at least 1: " + maxPassages);
        }
    }
}
