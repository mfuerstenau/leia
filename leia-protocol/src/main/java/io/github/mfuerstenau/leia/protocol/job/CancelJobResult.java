package io.github.mfuerstenau.leia.protocol.job;

/**
 * Result of {@code cancelJob}.
 *
 * @param cancelled whether a running job was stopped; false if the job had already finished or is
 *     unknown
 */
public record CancelJobResult(boolean cancelled) {}
