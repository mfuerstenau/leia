package io.github.mfuerstenau.leia.testcodec;

import java.io.IOException;
import org.apache.lucene.codecs.DocValuesConsumer;
import org.apache.lucene.codecs.DocValuesFormat;
import org.apache.lucene.codecs.DocValuesProducer;
import org.apache.lucene.codecs.lucene90.Lucene90DocValuesFormat;
import org.apache.lucene.index.SegmentReadState;
import org.apache.lucene.index.SegmentWriteState;

/** A doc values format that leia doesn't bundle: Lucene's default under a name of its own (ADR 0018). */
public final class LeiaTestDocValuesFormat extends DocValuesFormat {

    private final DocValuesFormat delegate = new Lucene90DocValuesFormat();

    public LeiaTestDocValuesFormat() {
        super("LeiaTestDocValues");
    }

    @Override
    public DocValuesConsumer fieldsConsumer(SegmentWriteState state) throws IOException {
        return delegate.fieldsConsumer(state);
    }

    @Override
    public DocValuesProducer fieldsProducer(SegmentReadState state) throws IOException {
        return delegate.fieldsProducer(state);
    }
}
