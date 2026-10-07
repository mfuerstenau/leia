package io.github.mfuerstenau.leia.core;

/**
 * A query that can't be run: the expression doesn't parse, or the query has too many clauses once
 * it is rewritten.
 */
public final class InvalidQueryException extends Exception {

    private static final long serialVersionUID = 1L;

    public InvalidQueryException(String message, Throwable cause) {
        super(message, cause);
    }
}
