package io.github.mfuerstenau.leia.protocol;

/**
 * A JSON-RPC request. {@code params} is always an object, an empty one for methods without
 * parameters.
 *
 * @param <P> the params DTO of {@code method}
 */
public record Request<P>(JsonRpcVersion jsonrpc, long id, String method, P params) {

    public static <P> Request<P> of(long id, RpcMethod<P, ?> method, P params) {
        return new Request<>(JsonRpcVersion.V2_0, id, method.name(), params);
    }
}
