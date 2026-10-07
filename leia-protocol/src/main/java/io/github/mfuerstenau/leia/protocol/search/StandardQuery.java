package io.github.mfuerstenau.leia.protocol.search;

import java.util.List;

/**
 * An expression for Lucene's standard (flexible) {@code StandardQueryParser}, analyzed with {@code
 * StandardAnalyzer} (ADR 0011).
 *
 * @param expression the query in the standard syntax, such as {@code price:[10 TO 20] AND fox}
 * @param defaultField the field of terms that name none
 * @param defaultOperator how clauses without an operator are combined
 * @param options the options both parsers have
 * @param points the point fields whose ranges and values become point queries, each with the type
 *     of its values
 */
public record StandardQuery(
        String expression,
        String defaultField,
        QueryOperator defaultOperator,
        ParserOptions options,
        List<PointField> points) {}
