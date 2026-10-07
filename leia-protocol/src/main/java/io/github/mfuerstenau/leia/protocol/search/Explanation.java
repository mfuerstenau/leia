package io.github.mfuerstenau.leia.protocol.search;

import java.util.List;

/**
 * How a document scores for a query, or why it doesn't match: Lucene's {@code Explanation} tree.
 *
 * @param match whether the document matches this part of the query
 * @param value the value of this part, such as a score or a document count, as Java's {@code
 *     toString} writes the number
 * @param description what the value is
 * @param details the parts the value is computed from
 */
public record Explanation(boolean match, String value, String description, List<Explanation> details) {}
