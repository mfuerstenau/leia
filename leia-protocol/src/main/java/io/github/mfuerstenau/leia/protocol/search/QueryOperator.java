package io.github.mfuerstenau.leia.protocol.search;

/** How a query parser combines clauses without an operator, as Lucene's {@code QueryParser.Operator}. */
public enum QueryOperator {
    OR,
    AND
}
