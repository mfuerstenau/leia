package io.github.mfuerstenau.leia.protocol.analysis;

import java.util.List;

/**
 * The stop words of one field (ADR 0039), as Lucene's {@code QueryAutoStopWordAnalyzer} removes
 * them.
 *
 * @param field the field's name
 * @param words the words, case-sensitive; none for none
 */
public record FieldStopWords(String field, List<String> words) {}
