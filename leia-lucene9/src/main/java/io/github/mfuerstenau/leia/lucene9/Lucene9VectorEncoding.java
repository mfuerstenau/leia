package io.github.mfuerstenau.leia.lucene9;

import io.github.mfuerstenau.leia.runtime.RuntimeIndex.VectorEncoding;
import org.apache.lucene.index.FieldInfo;

/**
 * The encoding of a field's KNN vectors, through 9.4's {@code FieldInfo.getVectorEncoding()}: a
 * class of its own, which the verifier can't load without 9.4's enum, so it is called only where
 * {@link Lucene9Api#VECTOR_ENCODING} says the runtime's Lucene has it (ADR 0091).
 */
final class Lucene9VectorEncoding {

    private Lucene9VectorEncoding() {}

    static VectorEncoding of(FieldInfo info) {
        // By name, so that no switch map names 9.4's enum.
        return VectorEncoding.valueOf(info.getVectorEncoding().name());
    }
}
