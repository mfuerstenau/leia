package io.github.mfuerstenau.leia.mcp;

import java.io.Serial;

/**
 * A tool call that failed in a way the agent can correct: a result with {@code isError} whose text
 * is the message (ADR 0100 §3).
 */
public final class ToolException extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    public ToolException(String message) {
        super(message);
    }
}
