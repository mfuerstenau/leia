package io.github.mfuerstenau.leia.mcp;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import java.io.Serial;
import java.util.Optional;

/**
 * A tool call that failed in a way the agent can correct: a result with {@code isError} whose text
 * is the message (ADR 0100 §3).
 */
public final class ToolException extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    /** The code the message ends with, or {@code null}. */
    private final ErrorCode code;

    /** A failure without a code, such as a job that took too long. */
    public ToolException(String message) {
        super(message);
        this.code = null;
    }

    /** A failure whose message ends with its code, as {@code message (CODE)}. */
    public ToolException(String message, ErrorCode code) {
        super(message + " (" + code.name() + ")");
        this.code = code;
    }

    /** The code of the failure; absent for one that has none. */
    public Optional<ErrorCode> code() {
        return Optional.ofNullable(code);
    }
}
