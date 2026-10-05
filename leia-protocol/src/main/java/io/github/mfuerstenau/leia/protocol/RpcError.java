package io.github.mfuerstenau.leia.protocol;

/** The {@code error} member of a failed response. */
public record RpcError(ErrorCode code, String message) {}
