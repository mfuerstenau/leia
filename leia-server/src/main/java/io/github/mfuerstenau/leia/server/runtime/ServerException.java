package io.github.mfuerstenau.leia.server.runtime;

import java.nio.file.Path;

/**
 * A server of {@code --server} that can't be loaded (ADR 0090), such as one whose Lucene leia has no
 * adapter for.
 */
public final class ServerException extends Exception {

    private static final long serialVersionUID = 1L;

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
