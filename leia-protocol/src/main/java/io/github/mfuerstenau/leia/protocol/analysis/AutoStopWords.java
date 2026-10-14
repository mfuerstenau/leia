package io.github.mfuerstenau.leia.protocol.analysis;

import java.util.List;

/**
 * The stop words found by {@code startAutoStopWords} (ADR 0039): the terms of each field in more than
 * {@code maxDocFreq} documents, in term order.
 *
 * @param maxDocFreq the threshold, as given or computed from the percentage of documents
 * @param fields each scanned field with its words, sorted by name; a field without words is listed too
 */
public record AutoStopWords(int maxDocFreq, List<FieldStopWords> fields) {}
