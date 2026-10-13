package io.github.mfuerstenau.leia.server;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import java.io.Serial;

/** An expected failure of a method, which the UI shows with {@link #code()}. */
public final class RpcException extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    private final ErrorCode code;

    public RpcException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public RpcException(ErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
