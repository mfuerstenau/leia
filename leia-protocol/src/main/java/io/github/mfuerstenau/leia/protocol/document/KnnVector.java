package io.github.mfuerstenau.leia.protocol.document;

import io.github.mfuerstenau.leia.protocol.index.VectorEncoding;
import java.util.List;

/**
 * A KNN vector (ADR 0040).
 *
 * @param encoding how the vector is encoded
 * @param floats the components of a {@code FLOAT32} vector; empty for a {@code BYTE} vector
 * @param bytes the components of a {@code BYTE} vector, signed; empty for a {@code FLOAT32} vector
 */
public record KnnVector(VectorEncoding encoding, List<Float> floats, List<Byte> bytes) {

    /** The dimension: how many components the vector has. */
    public int dimension() {
        return encoding == VectorEncoding.FLOAT32 ? floats.size() : bytes.size();
    }
}
