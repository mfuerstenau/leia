package io.github.mfuerstenau.leia.protocol.analysis;

import java.util.List;
import java.util.Optional;

/**
 * An analyzer, as one of several alternatives: exactly one of {@code standard}, {@code preset} and
 * {@code custom} is present (ADR 0014). Any of them can be wrapped in shingles, then in a token
 * limit (ADR 0023). That is the default analyzer; {@code fields} gives fields their own, as Lucene's
 * {@code PerFieldAnalyzerWrapper} does, and {@code autoStopWords} removes the stop words of fields
 * around all of it, as Lucene's {@code QueryAutoStopWordAnalyzer} does (ADR 0039). The server builds
 * it for the request.
 *
 * @param standard Lucene's {@code StandardAnalyzer}
 * @param preset an analyzer by class name, such as {@code EnglishAnalyzer} (ADR 0015)
 * @param custom a {@code CustomAnalyzer} of char filters, a tokenizer and token filters
 * @param shingles the analyzer in Lucene's {@code ShingleAnalyzerWrapper}; absent for none
 * @param limitTokenCount the analyzer, with its shingles, in Lucene's {@code LimitTokenCountAnalyzer};
 *     absent for none
 * @param fields the fields with an analyzer of their own, each named once; none for none
 * @param autoStopWords the stop words of fields, such as {@code startAutoStopWords} finds them, each
 *     field named once; none for none
 */
public record Analyzer(
        Optional<StandardAnalyzer> standard,
        Optional<PresetAnalyzer> preset,
        Optional<CustomAnalyzer> custom,
        Optional<Shingles> shingles,
        Optional<LimitTokenCount> limitTokenCount,
        List<FieldAnalyzer> fields,
        List<FieldStopWords> autoStopWords) {}
