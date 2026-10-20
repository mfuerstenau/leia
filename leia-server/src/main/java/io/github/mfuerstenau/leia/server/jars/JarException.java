package io.github.mfuerstenau.leia.server.jars;

/**
 * Jars that can't be loaded: a path that is no jar or holds none, or a jar whose factories, codecs
 * or formats can't be loaded, such as for a missing dependency (ADR 0016, ADR 0018).
 */
public final class JarException extends Exception {

    private static final long serialVersionUID = 1L;

    public JarException(String message, Throwable cause) {
        super(message, cause);
    }
}
