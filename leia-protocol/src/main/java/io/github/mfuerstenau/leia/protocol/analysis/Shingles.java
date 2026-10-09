package io.github.mfuerstenau.leia.protocol.analysis;

/**
 * Lucene's {@code ShingleAnalyzerWrapper} around an analyzer: word n-grams of its tokens (ADR 0023).
 * Lucene's defaults are 2, 2, {@code " "}, {@code true}, {@code false} and {@code "_"}.
 *
 * @param minShingleSize the fewest tokens in a shingle, at least 2
 * @param maxShingleSize the most tokens in a shingle, at least {@code minShingleSize}
 * @param tokenSeparator what goes between the tokens of a shingle
 * @param outputUnigrams whether the tokens themselves are kept
 * @param outputUnigramsIfNoShingles whether the tokens are kept when there are no shingles
 * @param fillerToken what stands for a removed token, such as a stop word
 */
public record Shingles(
        int minShingleSize,
        int maxShingleSize,
        String tokenSeparator,
        boolean outputUnigrams,
        boolean outputUnigramsIfNoShingles,
        String fillerToken) {}
