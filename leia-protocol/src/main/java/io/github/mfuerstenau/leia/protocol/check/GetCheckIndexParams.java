package io.github.mfuerstenau.leia.protocol.check;

/**
 * Params of {@code getCheckIndex} (ADR 0022).
 *
 * @param jobId the job, as returned by {@code startCheckIndex}
 * @param fromLine the first line of the output to return: the number of lines the caller has
 */
public record GetCheckIndexParams(long jobId, int fromLine) {}
