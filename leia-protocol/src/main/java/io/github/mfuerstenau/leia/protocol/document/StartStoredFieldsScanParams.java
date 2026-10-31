package io.github.mfuerstenau.leia.protocol.document;

/**
 * Params of {@code startStoredFieldsScan} (ADR 0113).
 *
 * @param ignoreCache whether to read every segment again instead of taking what the cache knows of
 *     it; the cache is updated with what is read
 */
public record StartStoredFieldsScanParams(boolean ignoreCache) {}
