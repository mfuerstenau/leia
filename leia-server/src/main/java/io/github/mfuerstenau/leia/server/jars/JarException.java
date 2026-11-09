package io.github.mfuerstenau.leia.server.jars;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Jars that can't be loaded: a path that is no jar or holds none, or a jar whose factories, codecs
 * or formats can't be loaded, such as for a missing dependency (ADR 0016, ADR 0018).
 */
public final class JarException extends Exception {

    private static final long serialVersionUID = 1L;

    /** The message, which every constructor takes. */
    @Override
    public String getMessage() {
        return Objects.requireNonNullElse(super.getMessage(), getClass().getSimpleName());
    }

    public JarException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
