package io.github.mfuerstenau.leia.protocol.index;

/** How a field's KNN vectors are encoded, as Lucene's {@code VectorEncoding} (ADR 0040). */
public enum VectorEncoding {
    /** Signed bytes, from -128 to 127. */
    BYTE,
    /** 32-bit floats. */
    FLOAT32
}
