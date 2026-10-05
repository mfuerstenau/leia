package io.github.mfuerstenau.leia.server;

import io.github.mfuerstenau.leia.protocol.RpcMethod;

/**
 * Handles one JSON-RPC method. Every method in {@code Methods} has exactly one handler, bound into
 * a set in {@link HandlerModule}.
 *
 * @param <P> the params DTO of {@link #method()}
 * @param <R> the result DTO of {@link #method()}
 */
public interface MethodHandler<P, R> {

    RpcMethod<P, R> method();

    /**
     * Runs the method.
     *
     * @throws RpcException for an expected failure with its own error code; any other exception
     *     becomes an {@code INTERNAL_ERROR}
     */
    R handle(P params) throws RpcException;
}
