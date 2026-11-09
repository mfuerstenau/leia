package io.github.mfuerstenau.leia.core;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * An analyzer that can't be built: an unknown component, an unknown or bad parameter, a config
 * directory that is not a directory, or a resource file that can't be read or parsed (ADR 0014).
 */
public final class InvalidAnalyzerException extends Exception {

    private static final long serialVersionUID = 1L;

    /** The message, which every constructor takes. */
    @Override
    public String getMessage() {
        return Objects.requireNonNullElse(super.getMessage(), getClass().getSimpleName());
    }

    public InvalidAnalyzerException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
