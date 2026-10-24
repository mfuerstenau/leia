package io.github.mfuerstenau.leia.testserver;

import java.io.IOException;
import org.apache.lucene.codecs.FieldsConsumer;
import org.apache.lucene.codecs.FieldsProducer;
import org.apache.lucene.codecs.PostingsFormat;
import org.apache.lucene.codecs.lucene90.Lucene90PostingsFormat;
import org.apache.lucene.index.SegmentReadState;
import org.apache.lucene.index.SegmentWriteState;

/**
 * A postings format of the fake server's own, as Elasticsearch's ES812Postings is (ADR 0090, ADR 0091): Lucene
 * 9.0's, under a name that no Lucene knows, so that an index written with it opens only with the jar
 * of the server.
 */
public final class LeiaServerPostings extends PostingsFormat {

    private final PostingsFormat delegate = new Lucene90PostingsFormat();

    public LeiaServerPostings() {
        super("LeiaServerPostings");
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
