package io.github.mfuerstenau.leia.core;

/**
 * Analysis jars that can't be loaded: a path that is no jar or holds none, or a jar whose factories
 * can't be loaded, such as for a missing dependency (ADR 0016).
 */
public final class AnalysisJarException extends Exception {

    private static final long serialVersionUID = 1L;

    public AnalysisJarException(String message, Throwable cause) {
        super(message, cause);
    }
}
