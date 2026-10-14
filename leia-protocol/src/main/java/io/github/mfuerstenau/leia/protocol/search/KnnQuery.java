package io.github.mfuerstenau.leia.protocol.search;

import io.github.mfuerstenau.leia.protocol.document.KnnVector;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The documents whose KNN vectors in one field are nearest to a target vector (ADR 0040). Exactly
 * one of {@code docId} and {@code vector} is the target.
 *
 * @param field a field with KNN vectors
 * @param docId the document whose vector in {@code field} is the target, from 0 to maxDoc - 1; it
 *     may be deleted
 * @param vector the target vector, of the field's encoding and dimension; floats are finite
 * @param k how many nearest documents the approximate search finds, from 1 to {@code MAX_K};
 *     present exactly when {@code exact} is false
 * @param exact whether to score every document with a vector instead of searching the field's
 *     graph; the hits are then every such document, best first
 * @param filter only documents that match this classic expression are searched; absent for all
 */
public record KnnQuery(
        String field,
        OptionalInt docId,
        Optional<KnnVector> vector,
        OptionalInt k,
        boolean exact,
        Optional<ClassicQuery> filter) {

    /** The largest {@code k} the server accepts: {@link StartSearchParams#MAX_WINDOW}. */
    public static final int MAX_K = StartSearchParams.MAX_WINDOW;
}
