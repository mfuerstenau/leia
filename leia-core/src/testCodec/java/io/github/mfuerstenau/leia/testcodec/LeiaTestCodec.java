package io.github.mfuerstenau.leia.testcodec;

import org.apache.lucene.codecs.FilterCodec;
import org.apache.lucene.codecs.lucene104.Lucene104Codec;

/** A codec that leia doesn't bundle: Lucene's default under a name of its own (ADR 0018). */
public final class LeiaTestCodec extends FilterCodec {

    public LeiaTestCodec() {
        super("LeiaTest", new Lucene104Codec());
    }
}
