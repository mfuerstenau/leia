package io.github.mfuerstenau.leia.testserver;

import io.github.mfuerstenau.leia.testserver.ext.LeiaExtSupport;
import java.io.IOException;
import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.codecs.KnnVectorsReader;
import org.apache.lucene.codecs.KnnVectorsWriter;
import org.apache.lucene.codecs.lucene99.Lucene99HnswVectorsFormat;
import org.apache.lucene.index.ByteVectorValues;
import org.apache.lucene.index.FloatVectorValues;
import org.apache.lucene.index.SegmentReadState;
import org.apache.lucene.index.SegmentWriteState;
import org.apache.lucene.search.KnnCollector;
import org.apache.lucene.util.Bits;
import org.opensearch.knn.index.KNNSettings;

/**
 * The vectors format of the fake k-NN plugin (ADR 0092): Lucene 9.10's HNSW, which needs a class of
 * a module that the plugin extends ({@link LeiaExtSupport}, as the real plugin needs Guava of {@code
 * transport-grpc}), and whose reader asks the plugin's settings as it closes, as the native memory
 * cache of the real one does.
 */
public final class LeiaKnnVectorsFormat extends KnnVectorsFormat {

    private final KnnVectorsFormat delegate = new Lucene99HnswVectorsFormat();

    public LeiaKnnVectorsFormat() {
        super("LeiaKnnVectors");
        LeiaExtSupport.touch();
    }

    @Override
    public KnnVectorsWriter fieldsWriter(SegmentWriteState state) throws IOException {
        return delegate.fieldsWriter(state);
    }

    @Override
    public KnnVectorsReader fieldsReader(SegmentReadState state) throws IOException {
        KnnVectorsReader reader = delegate.fieldsReader(state);
        return new KnnVectorsReader() {
            @Override
            public void checkIntegrity() throws IOException {
                KNNSettings.state().cacheSize();
                reader.checkIntegrity();
            }

            @Override
            public FloatVectorValues getFloatVectorValues(String field) throws IOException {
                return reader.getFloatVectorValues(field);
            }

            @Override
            public ByteVectorValues getByteVectorValues(String field) throws IOException {
                return reader.getByteVectorValues(field);
            }

            @Override
            public void search(String field, float[] target, KnnCollector knnCollector, Bits acceptDocs)
                    throws IOException {
                reader.search(field, target, knnCollector, acceptDocs);
            }

            @Override
            public void search(String field, byte[] target, KnnCollector knnCollector, Bits acceptDocs)
                    throws IOException {
                reader.search(field, target, knnCollector, acceptDocs);
            }

            @Override
            public long ramBytesUsed() {
                return reader.ramBytesUsed();
            }

            @Override
            public void close() throws IOException {
                try {
                    KNNSettings.state().cacheSize();
                } finally {
                    reader.close();
                }
            }
        };
    }

    @Override
    public int getMaxDimensions(String fieldName) {
        return delegate.getMaxDimensions(fieldName);
    }
}
