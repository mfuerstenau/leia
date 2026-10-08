package io.github.mfuerstenau.leia.testcodec;

import java.io.IOException;
import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.codecs.KnnVectorsReader;
import org.apache.lucene.codecs.KnnVectorsWriter;
import org.apache.lucene.codecs.lucene99.Lucene99HnswVectorsFormat;
import org.apache.lucene.index.SegmentReadState;
import org.apache.lucene.index.SegmentWriteState;

/** A KNN vectors format that leia doesn't bundle: Lucene's default under a name of its own (ADR 0018). */
public final class LeiaTestKnnVectorsFormat extends KnnVectorsFormat {

    private final KnnVectorsFormat delegate = new Lucene99HnswVectorsFormat();

    public LeiaTestKnnVectorsFormat() {
        super("LeiaTestKnnVectors");
    }

    @Override
    public KnnVectorsWriter fieldsWriter(SegmentWriteState state) throws IOException {
        return delegate.fieldsWriter(state);
    }

    @Override
    public KnnVectorsReader fieldsReader(SegmentReadState state) throws IOException {
        return delegate.fieldsReader(state);
    }

    @Override
    public int getMaxDimensions(String fieldName) {
        return delegate.getMaxDimensions(fieldName);
    }
}
