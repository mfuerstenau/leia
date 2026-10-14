package io.github.mfuerstenau.leia.protocol.analysis;

/**
 * The analyzer of one field (ADR 0039), as Lucene's {@code PerFieldAnalyzerWrapper} has it.
 *
 * @param field the field's name, not empty
 * @param analyzer the field's analyzer, with no {@code fields} or {@code autoStopWords} of its own
 */
public record FieldAnalyzer(String field, Analyzer analyzer) {}
