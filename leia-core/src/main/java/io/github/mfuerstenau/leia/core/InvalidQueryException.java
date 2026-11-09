package io.github.mfuerstenau.leia.core;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A query that can't be run: the expression doesn't parse, or the query has too many clauses once
 * it is rewritten.
 */
public final class InvalidQueryException extends Exception {

    private static final long serialVersionUID = 1L;

    /** The message, which every constructor takes. */
    @Override
    public String getMessage() {
        return Objects.requireNonNullElse(super.getMessage(), getClass().getSimpleName());
    }

    public InvalidQueryException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
