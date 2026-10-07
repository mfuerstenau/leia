package io.github.mfuerstenau.leia.protocol.search;

/** How a query parser combines clauses without an operator, as Lucene's {@code QueryParser.Operator}; serialized as its name. */
public enum QueryOperator {
    OR,
    AND
}
