package io.github.mfuerstenau.leia.protocol.search;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Optional;

/**
 * What scores the hits, as one of several alternatives: exactly one is present (ADR 0012).
 *
 * @param bm25 Lucene's default, {@code BM25Similarity}
 * @param classic TF-IDF, {@code ClassicSimilarity}
 * @param booleanSimilarity {@code BooleanSimilarity}: a matching term scores its boost; {@code
 *     boolean} on the wire
 */
public record Similarity(
        Optional<Bm25Similarity> bm25,
        Optional<ClassicSimilarity> classic,
        @JsonProperty("boolean") Optional<BooleanSimilarity> booleanSimilarity) {}
