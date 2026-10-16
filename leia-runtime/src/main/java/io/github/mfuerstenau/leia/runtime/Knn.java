package io.github.mfuerstenau.leia.runtime;

import java.io.IOException;
import java.util.Optional;

/**
 * The KNN vectors of a {@link RuntimeIndex} and the queries for the documents nearest to a target
 * (ADR 0040, ADR 0049). The queries run through {@link Search#search}, as classic ones do: an
 * approximate one searches the field's graph when it is rewritten. Both score a document with the
 * field's similarity, so their scores compare. The caller has checked that the target fits the
 * field.
 */
public interface Knn {

    /**
     * The {@code k} documents that the KNN search of the runtime's Lucene finds nearest to {@code
     * target} in {@code field}; with a filter, only documents that match it are searched.
     *
     * @param filter a query from {@link Search#parse}
     * @throws IllegalArgumentException if the index has no vectors in {@code field}
     */
    Search.ParsedQuery approximate(
            String field, RuntimeIndex.Vector target, int k, Optional<Search.ParsedQuery> filter);

    /**
     * Every document with a vector in {@code field}, scored by its similarity to {@code target}; with
     * a filter, only those that match it.
     *
     * @param filter a query from {@link Search#parse}
     * @throws IllegalArgumentException if the index has no vectors in {@code field}
     */
    Search.ParsedQuery exact(String field, RuntimeIndex.Vector target, Optional<Search.ParsedQuery> filter);

    /**
     * The vector of document {@code docId}, a deleted one too, in {@code field}; empty if it has none.
     *
     * @throws IllegalArgumentException if there is no such document
     */
    Optional<RuntimeIndex.Vector> vector(int docId, String field) throws IOException;
}
