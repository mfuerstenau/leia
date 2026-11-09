package io.github.mfuerstenau.leia.runtime;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

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
            String field, RuntimeIndex.Vector target, int k, Search.@Nullable ParsedQuery filter);

    /**
     * Every document with a vector in {@code field}, scored by its similarity to {@code target}; with
     * a filter, only those that match it.
     *
     * @param filter a query from {@link Search#parse}
     * @throws IllegalArgumentException if the index has no vectors in {@code field}
     */
    Search.ParsedQuery exact(String field, RuntimeIndex.Vector target, Search.@Nullable ParsedQuery filter);

    /**
     * The vector of document {@code docId}, a deleted one too, in {@code field}; empty if it has none.
     *
     * @throws IllegalArgumentException if there is no such document
     */
    Optional<RuntimeIndex.Vector> vector(int docId, String field) throws IOException;

    /**
     * Up to {@code n} live documents with a vector in {@code field}, for a recall check (ADR 0086):
     * spread evenly by vector ordinal over the segments, so that each segment gives its share by its
     * number of vectors. A position that falls on a deleted document takes the next live one of its
     * segment. In document id order, without duplicates; every live document with a vector if there
     * are no more than {@code n}.
     *
     * @throws IllegalArgumentException if the index has no vectors in {@code field}, or {@code n} is
     *     below 1
     */
    List<Integer> sample(String field, int n) throws IOException;
}
