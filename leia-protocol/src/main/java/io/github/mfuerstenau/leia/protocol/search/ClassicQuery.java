package io.github.mfuerstenau.leia.protocol.search;

/**
 * An expression for Lucene's classic {@code QueryParser}, analyzed with {@code StandardAnalyzer}.
 *
 * @param expression the query in the classic syntax, such as {@code title:fox AND body:qu*}
 * @param defaultField the field of terms that name none
 * @param defaultOperator how clauses without an operator are combined
 */
public record ClassicQuery(String expression, String defaultField, QueryOperator defaultOperator) {}
