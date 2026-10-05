package io.github.mfuerstenau.leia.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Optional;

/**
 * A JSON-RPC response: exactly one of {@code result} and {@code error} is present.
 *
 * @param id the request's id, or empty (serialized as {@code null}) if the request could not be
 *     read far enough to know it
 * @param <R> the result DTO of the method that was called
 */
public record Response<R>(
        JsonRpcVersion jsonrpc,
        @JsonInclude(JsonInclude.Include.ALWAYS) Optional<Long> id,
        Optional<R> result,
        Optional<RpcError> error) {

    public Response {
        if (result.isPresent() == error.isPresent()) {
            throw new IllegalArgumentException("A response needs exactly one of result and error");
        }
    }

    public static <R> Response<R> success(long id, R result) {
        return new Response<>(JsonRpcVersion.V2_0, Optional.of(id), Optional.of(result), Optional.empty());
    }

    public static <R> Response<R> failure(Optional<Long> id, RpcError error) {
        return new Response<>(JsonRpcVersion.V2_0, id, Optional.empty(), Optional.of(error));
    }
}
