package io.github.mfuerstenau.leia.server.runtime;

import java.nio.file.Path;
import java.util.Objects;

/**
 * A server of {@code --server} that can't be loaded (ADR 0090), such as one whose Lucene leia has no
 * adapter for.
 */
public final class ServerException extends Exception {

    private static final long serialVersionUID = 1L;

    /** The message, which every constructor takes. */
    @Override
    public String getMessage() {
        return Objects.requireNonNullElse(super.getMessage(), getClass().getSimpleName());
    }

    ServerException(Path home, String reason) {
        super(message(home, reason));
    }

    ServerException(Path home, String reason, Throwable cause) {
        super(message(home, reason), cause);
    }

    private static String message(Path home, String reason) {
        return "Cannot read with the server " + home + ": " + reason;
    }
}
