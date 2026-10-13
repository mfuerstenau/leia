package io.github.mfuerstenau.leia.protocol;

/**
 * Describes one method of the backend: its name and the DTOs of its params and result. All methods are
 * listed in {@link Methods}.
 */
public record RpcMethod<P, R>(String name, Class<P> paramsType, Class<R> resultType) {}
