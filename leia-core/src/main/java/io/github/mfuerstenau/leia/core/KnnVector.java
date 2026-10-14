package io.github.mfuerstenau.leia.core;

import java.util.Arrays;
import org.apache.lucene.index.VectorEncoding;

/** A KNN vector of floats or of bytes (ADR 0040). Equal vectors have equal components. */
public sealed interface KnnVector {

    /** How the vector is encoded. */
    VectorEncoding encoding();

    /** How many components the vector has. */
    int dimension();

    /** A vector of floats; the array is the caller's to keep, and is not copied. */
    record Floats(float[] values) implements KnnVector {

        @Override
        public VectorEncoding encoding() {
            return VectorEncoding.FLOAT32;
        }

        @Override
        public int dimension() {
            return values.length;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Floats other && Arrays.equals(values, other.values);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(values);
        }

        @Override
        public String toString() {
            return Arrays.toString(values);
        }
    }

    /** A vector of signed bytes; the array is the caller's to keep, and is not copied. */
    record Bytes(byte[] values) implements KnnVector {

        @Override
        public VectorEncoding encoding() {
            return VectorEncoding.BYTE;
        }

        @Override
        public int dimension() {
            return values.length;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Bytes other && Arrays.equals(values, other.values);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(values);
        }

        @Override
        public String toString() {
            return Arrays.toString(values);
        }
    }
}
