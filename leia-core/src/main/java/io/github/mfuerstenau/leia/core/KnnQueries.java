package io.github.mfuerstenau.leia.core;

import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.VectorEncoding;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.search.KnnByteVectorQuery;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.Query;
import org.jspecify.annotations.Nullable;

/**
 * Builds queries for the documents whose KNN vectors in one field are nearest to a target (ADR
 * 0040): approximately, through the field's graph, or exactly, by scoring every document with a
 * vector. Both score a document with the field's similarity, so their scores compare.
 */
public final class KnnQueries {

    private KnnQueries() {}

    /**
     * The {@code k} documents that Lucene's KNN search finds nearest to {@code target}. The search
     * runs when the query is rewritten. With a filter, only documents that match it are searched,
     * and Lucene scores them all if there are few enough.
     *
     * @throws IllegalArgumentException if {@code target} doesn't fit the field (see {@link #check}),
     *     or {@code k} is below 1
     */
    public static Query approximate(FieldInfo field, KnnVector target, int k, @Nullable Query filter) {
        check(field, target);
        if (k < 1) {
            throw new IllegalArgumentException("k must be at least 1: " + k);
        }
        return switch (target) {
            case KnnVector.Floats floats -> new KnnFloatVectorQuery(field.name, floats.values(), k, filter);
            case KnnVector.Bytes bytes -> new KnnByteVectorQuery(field.name, bytes.values(), k, filter);
        };
    }

    /**
     * Every document with a vector in the field, scored by its similarity to {@code target}, and
     * with a filter only those that match it.
     *
     * @throws IllegalArgumentException if {@code target} doesn't fit the field (see {@link #check})
     */
    public static Query exact(FieldInfo field, KnnVector target, @Nullable Query filter) {
        check(field, target);
        return new ExactKnnQuery(field.name, target, filter);
    }

    /**
     * Checks that {@code target} can be compared with the vectors of {@code field}.
     *
     * @throws IllegalArgumentException if the field has no vectors, or vectors of another encoding or
     *     dimension; if a float of {@code target} is NaN or infinite; or if {@code target} is all
     *     zeros and the field compares by cosine, which a vector of zeros has none of
     */
    public static void check(FieldInfo field, KnnVector target) {
        if (field.getVectorDimension() == 0) {
            throw new IllegalArgumentException("the field '" + field.name + "' has no KNN vectors");
        }
        if (field.getVectorEncoding() != target.encoding()) {
            throw new IllegalArgumentException("the field '" + field.name + "' has vectors of "
                    + components(field.getVectorEncoding()) + ", not of " + components(target.encoding()));
        }
        if (field.getVectorDimension() != target.dimension()) {
            throw new IllegalArgumentException("the field '" + field.name + "' has vectors of "
                    + field.getVectorDimension() + " dimensions, not " + target.dimension());
        }
        boolean zeros = true;
        switch (target) {
            case KnnVector.Floats floats -> {
                float[] values = floats.values();
                for (int i = 0; i < values.length; i++) {
                    if (!Float.isFinite(values[i])) {
                        throw new IllegalArgumentException(
                                "component " + i + " of the vector is " + values[i] + ", not a finite number");
                    }
                    zeros &= values[i] == 0;
                }
            }
            case KnnVector.Bytes bytes -> {
                for (byte value : bytes.values()) {
                    zeros &= value == 0;
                }
            }
        }
        if (zeros && field.getVectorSimilarityFunction() == VectorSimilarityFunction.COSINE) {
            throw new IllegalArgumentException(
                    "the field '" + field.name + "' compares by cosine, which a vector of zeros has none of");
        }
    }

    private static String components(VectorEncoding encoding) {
        return switch (encoding) {
            case FLOAT32 -> "floats";
            case BYTE -> "bytes";
        };
    }
}
