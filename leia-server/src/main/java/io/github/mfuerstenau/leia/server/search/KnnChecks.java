package io.github.mfuerstenau.leia.server.search;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.document.KnnVector;
import io.github.mfuerstenau.leia.protocol.index.VectorEncoding;
import io.github.mfuerstenau.leia.protocol.index.VectorSimilarity;
import io.github.mfuerstenau.leia.protocol.search.KnnQuery;
import io.github.mfuerstenau.leia.server.RpcException;
import java.util.List;

/**
 * The checks of a KNN query that need no Lucene, so that an index of Lucene 10 and one that a
 * runtime reads refuse a query in the same words (ADR 0040, ADR 0049). The target's checks are
 * worded as leia-core's {@code KnnQueries.check}, which checks the same for Lucene 10.
 */
public final class KnnChecks {

    private KnnChecks() {}

    /**
     * Checks the target and {@code k} of {@code knn}, whose field has vectors of {@code
     * vectorDimension} components, 0 without vectors.
     *
     * @throws RpcException {@code INVALID_PARAMS} if the field has no vectors, unless exactly one of
     *     {@code docId} and {@code vector} is present, for a {@code k} of an exact query, or a missing
     *     or out-of-range {@code k} of an approximate one
     */
    public static void check(KnnQuery knn, int vectorDimension) throws RpcException {
        if (vectorDimension == 0) {
            throw new RpcException(
                    ErrorCode.INVALID_PARAMS, "Invalid params: the field '" + knn.field() + "' has no KNN vectors");
        }
        if (knn.docId().isPresent() == knn.vector().isPresent()) {
            throw new RpcException(
                    ErrorCode.INVALID_PARAMS, "Invalid params: knn needs exactly one of docId and vector");
        }
        if (knn.exact() && knn.k().isPresent()) {
            throw new RpcException(
                    ErrorCode.INVALID_PARAMS,
                    "Invalid params: an exact knn query has no k: it scores every document with a vector");
        }
        if (!knn.exact()
                && (knn.k().isEmpty() || knn.k().getAsInt() < 1 || knn.k().getAsInt() > KnnQuery.MAX_K)) {
            throw new RpcException(
                    ErrorCode.INVALID_PARAMS,
                    "Invalid params: an approximate knn query needs k from 1 to " + KnnQuery.MAX_K
                            + (knn.k().isPresent() ? ": " + knn.k().getAsInt() : ""));
        }
    }

    /**
     * Checks that {@code target} can be compared with the vectors of {@code field}: {@code dimension}
     * components of {@code encoding}, compared by {@code similarity}.
     *
     * @throws IllegalArgumentException if {@code target} has another encoding or dimension, a float of
     *     it is NaN or infinite, or it is all zeros and the field compares by cosine
     */
    public static void checkTarget(
            String field, int dimension, VectorEncoding encoding, VectorSimilarity similarity, KnnVector target) {
        if (encoding != target.encoding()) {
            throw new IllegalArgumentException("the field '" + field + "' has vectors of " + components(encoding)
                    + ", not of " + components(target.encoding()));
        }
        if (dimension != target.dimension()) {
            throw new IllegalArgumentException(
                    "the field '" + field + "' has vectors of " + dimension + " dimensions, not " + target.dimension());
        }
        boolean zeros = true;
        if (encoding == VectorEncoding.FLOAT32) {
            List<Float> values = target.floats();
            for (int i = 0; i < values.size(); i++) {
                float value = values.get(i);
                if (!Float.isFinite(value)) {
                    throw new IllegalArgumentException(
                            "component " + i + " of the vector is " + value + ", not a finite number");
                }
                zeros &= value == 0;
            }
        } else {
            for (byte value : target.bytes()) {
                zeros &= value == 0;
            }
        }
        if (zeros && similarity == VectorSimilarity.COSINE) {
            throw new IllegalArgumentException(
                    "the field '" + field + "' compares by cosine, which a vector of zeros has none of");
        }
    }

    private static String components(VectorEncoding encoding) {
        return switch (encoding) {
            case FLOAT32 -> "floats";
            case BYTE -> "bytes";
        };
    }
}
