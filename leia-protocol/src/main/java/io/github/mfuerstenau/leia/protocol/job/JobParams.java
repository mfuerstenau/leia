package io.github.mfuerstenau.leia.protocol.job;

/**
 * Params of every {@code get<Op>} method and of {@code cancelJob}.
 *
 * @param jobId the job, as returned by {@code start<Op>}
 */
public record JobParams(long jobId) {}
