package io.github.mfuerstenau.leia.testserver;

import org.apache.lucene.codecs.FilterCodec;
import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.codecs.lucene99.Lucene99Codec;

/**
 * A codec of the fake k-NN plugin, as the plugin's {@code KNN9120Codec} is for OpenSearch (ADR
 * 0092): Lucene 9.10's, with a vectors format whose reader needs the plugin's setup.
 */
public final class LeiaKnnCodec extends FilterCodec {

    public LeiaKnnCodec() {
        super("LeiaKnn", new Lucene99Codec());
    }

    @Override
    public KnnVectorsFormat knnVectorsFormat() {
        return new LeiaKnnVectorsFormat();
    }
}
