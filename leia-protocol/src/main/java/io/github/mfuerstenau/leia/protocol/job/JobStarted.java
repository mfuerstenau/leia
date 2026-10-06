package io.github.mfuerstenau.leia.protocol.job;

/**
 * Result of every {@code start<Op>} method: the job runs on in the server (ADR 0005).
 *
 * @param jobId the job, for {@code get<Op>} and {@code cancelJob}
 */
public record JobStarted(long jobId) {}
