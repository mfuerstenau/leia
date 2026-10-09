package io.github.mfuerstenau.leia.protocol.analysis;

/**
 * Lucene's {@code LimitTokenCountAnalyzer} around an analyzer (ADR 0023).
 *
 * @param maxTokenCount the most tokens, at least 1
 * @param consumeAllTokens whether the analyzer's stream is read to its end after the last token
 */
public record LimitTokenCount(int maxTokenCount, boolean consumeAllTokens) {}
