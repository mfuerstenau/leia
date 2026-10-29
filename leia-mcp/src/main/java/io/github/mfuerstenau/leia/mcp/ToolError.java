package io.github.mfuerstenau.leia.mcp;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.server.RpcException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/** Turns the failures of the backend and of bad arguments into a {@link ToolException} (ADR 0100 §3). */
public final class ToolError {

    private ToolError() {}

    /** An expected failure of the backend, as {@code message (CODE)}, with a hint where the agent can act on it. */
    public static ToolException of(RpcException error) {
        String hint = switch (error.code()) {
            case NO_INDEX_OPEN -> "; call open_index with the index's path first";
            case MISSING_CODEC ->
                "; the user can start leia --mcp with --jar and a jar that has it, or for an Elasticsearch or"
                        + " OpenSearch index with --server and its installation";
            case MISSING_RUNTIME -> "; the user can install it with leia --download-runtimes --runtime <folder>";
            default -> "";
        };
        return new ToolException(error.getMessage() + hint + " (" + error.code().name() + ")");
    }

    /**
     * The {@link RpcException} that {@code failure} is or wraps, as {@link #of(RpcException)}.
     *
     * @throws IllegalStateException for anything else, which becomes a JSON-RPC error
     */
    public static ToolException of(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof ExecutionException || cause instanceof CompletionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof RpcException rpc) {
            return of(rpc);
        }
        throw new IllegalStateException("Unexpected failure: " + cause, cause);
    }

    /** Arguments that the tool refuses. */
    public static ToolException invalid(String message) {
        return new ToolException(message + " (" + ErrorCode.INVALID_PARAMS.name() + ")");
    }
}
