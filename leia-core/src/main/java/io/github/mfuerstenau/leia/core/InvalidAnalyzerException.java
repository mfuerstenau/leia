package io.github.mfuerstenau.leia.core;

/**
 * An analyzer that can't be built: an unknown component, an unknown or bad parameter, a config
 * directory that is not a directory, or a resource file that can't be read or parsed (ADR 0014).
 */
public final class InvalidAnalyzerException extends Exception {

    private static final long serialVersionUID = 1L;

    public InvalidAnalyzerException(String message, Throwable cause) {
        super(message, cause);
    }
}
