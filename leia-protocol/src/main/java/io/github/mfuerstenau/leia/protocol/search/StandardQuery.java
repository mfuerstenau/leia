package io.github.mfuerstenau.leia.protocol.search;

import io.github.mfuerstenau.leia.protocol.analysis.Analyzer;
import java.util.List;

/**
 * An expression for Lucene's standard (flexible) {@code StandardQueryParser} (ADR 0011, ADR 0014).
 *
 * @param expression the query in the standard syntax, such as {@code price:[10 TO 20] AND fox}
 * @param defaultField the field of terms that name none
 * @param defaultOperator how clauses without an operator are combined
 * @param options the options both parsers have
 * @param points the point fields whose ranges and values become point queries, each with the type
 *     of its values
 * @param analyzer analyzes the text of the expression
 */
public record StandardQuery(
        String expression,
        String defaultField,
        QueryOperator defaultOperator,
        ParserOptions options,
        List<PointField> points,
        Analyzer analyzer) {}
