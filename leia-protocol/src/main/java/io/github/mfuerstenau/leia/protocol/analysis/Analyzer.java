package io.github.mfuerstenau.leia.protocol.analysis;

import java.util.Optional;

/**
 * An analyzer, as one of several alternatives: exactly one is present (ADR 0014). The server
 * builds it for the request.
 *
 * @param standard Lucene's {@code StandardAnalyzer}
 * @param preset an analyzer by class name, such as {@code EnglishAnalyzer} (ADR 0015)
 * @param custom a {@code CustomAnalyzer} of char filters, a tokenizer and token filters
 */
public record Analyzer(
        Optional<StandardAnalyzer> standard, Optional<PresetAnalyzer> preset, Optional<CustomAnalyzer> custom) {}
