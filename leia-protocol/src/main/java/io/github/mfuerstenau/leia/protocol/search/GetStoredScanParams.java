package io.github.mfuerstenau.leia.protocol.search;

/**
 * Params of {@code getStoredScan} (ADR 0027).
 *
 * @param jobId the job, as returned by {@code startStoredScan}
 * @param fromHit the first hit to return: the number of hits the caller has
 */
public record GetStoredScanParams(long jobId, int fromHit) {}
