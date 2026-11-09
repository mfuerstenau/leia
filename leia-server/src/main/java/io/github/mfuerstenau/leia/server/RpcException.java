package io.github.mfuerstenau.leia.server;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import java.io.Serial;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** An expected failure of a method, which the UI shows with {@link #code()}. */
public final class RpcException extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    /** The message, which every constructor takes. */
    @Override
    public String getMessage() {
        return Objects.requireNonNullElse(super.getMessage(), getClass().getSimpleName());
    }

    private final ErrorCode code;

    public RpcException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public RpcException(ErrorCode code, String message, @Nullable Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
