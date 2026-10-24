package io.github.mfuerstenau.leia.testserver;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ServiceLoader;
import org.apache.lucene.codecs.FieldsConsumer;
import org.apache.lucene.codecs.FieldsProducer;
import org.apache.lucene.codecs.PostingsFormat;
import org.apache.lucene.codecs.lucene101.Lucene101PostingsFormat;
import org.apache.lucene.index.SegmentReadState;
import org.apache.lucene.index.SegmentWriteState;
import org.elasticsearch.common.logging.LogConfigurator;
import org.elasticsearch.nativeaccess.lib.LoaderHelper;

/**
 * A postings format of the fake server's own, as Elasticsearch's ES812Postings is (ADR 0090): Lucene
 * 10.2's, under a name that no Lucene knows, so that an index written with it opens only with the jar
 * of the server. Like Elasticsearch's vector formats, it can't be made before Elasticsearch's logging
 * and native libraries are set up, nor read without the server's jars in the context class loader.
 */
public final class LeiaServerPostings extends PostingsFormat {

    private final PostingsFormat delegate = new Lucene101PostingsFormat();

    public LeiaServerPostings() {
        super("LeiaServerPostings");
        if (!LogConfigurator.configured()) {
            throw new IllegalStateException("Elasticsearch's logging isn't set up");
        }
        // lib/platform/<os>-<arch>; the fake has lib/platform only.
        if (!Files.isDirectory(LoaderHelper.platformLibDir.getParent())) {
            throw new IllegalStateException("No native libraries in " + LoaderHelper.platformLibDir);
        }
    }

    @Override
    public FieldsConsumer fieldsConsumer(SegmentWriteState state) throws IOException {
        return delegate.fieldsConsumer(state);
    }

    @Override
    public FieldsProducer fieldsProducer(SegmentReadState state) throws IOException {
        // As Elasticsearch 9.5 finds its native providers, through the context class loader.
        if (ServiceLoader.load(PostingsFormat.class).stream()
                .noneMatch(provider -> provider.type() == LeiaServerPostings.class)) {
            throw new IllegalStateException("The context class loader doesn't see the server's jar");
        }
        return delegate.fieldsProducer(state);
    }
}
