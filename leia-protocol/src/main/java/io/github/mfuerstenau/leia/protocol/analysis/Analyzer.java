package io.github.mfuerstenau.leia.protocol.analysis;

import java.util.Optional;

/**
 * An analyzer, as one of several alternatives: exactly one of {@code standard}, {@code preset} and
 * {@code custom} is present (ADR 0014). Any of them can be wrapped in shingles, then in a token
 * limit (ADR 0023). The server builds it for the request.
 *
 * @param standard Lucene's {@code StandardAnalyzer}
 * @param preset an analyzer by class name, such as {@code EnglishAnalyzer} (ADR 0015)
 * @param custom a {@code CustomAnalyzer} of char filters, a tokenizer and token filters
 * @param shingles the analyzer in Lucene's {@code ShingleAnalyzerWrapper}; absent for none
 * @param limitTokenCount the analyzer, with its shingles, in Lucene's {@code LimitTokenCountAnalyzer};
 *     absent for none
 */
public record Analyzer(
        Optional<StandardAnalyzer> standard,
        Optional<PresetAnalyzer> preset,
        Optional<CustomAnalyzer> custom,
        Optional<Shingles> shingles,
        Optional<LimitTokenCount> limitTokenCount) {}
