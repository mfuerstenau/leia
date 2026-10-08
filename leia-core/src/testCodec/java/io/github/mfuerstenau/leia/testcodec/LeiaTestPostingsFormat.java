package io.github.mfuerstenau.leia.testcodec;

import java.io.IOException;
import org.apache.lucene.codecs.FieldsConsumer;
import org.apache.lucene.codecs.FieldsProducer;
import org.apache.lucene.codecs.PostingsFormat;
import org.apache.lucene.codecs.lucene104.Lucene104PostingsFormat;
import org.apache.lucene.index.SegmentReadState;
import org.apache.lucene.index.SegmentWriteState;

/** A postings format that leia doesn't bundle: Lucene's default under a name of its own (ADR 0018). */
public final class LeiaTestPostingsFormat extends PostingsFormat {

    private final PostingsFormat delegate = new Lucene104PostingsFormat();

    public LeiaTestPostingsFormat() {
        super("LeiaTestPostings");
    }

    @Override
    public FieldsConsumer fieldsConsumer(SegmentWriteState state) throws IOException {
        return delegate.fieldsConsumer(state);
    }

    @Override
    public FieldsProducer fieldsProducer(SegmentReadState state) throws IOException {
        return delegate.fieldsProducer(state);
    }
}
