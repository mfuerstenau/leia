package io.github.mfuerstenau.leia.testserver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.codecs.Codec;
import org.apache.lucene.codecs.PostingsFormat;
import org.apache.lucene.codecs.lucene101.Lucene101Codec;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;
import org.elasticsearch.common.logging.LogConfigurator;

/**
 * Writes the fake server's test indexes with its Lucene 10.2 (ADR 0090): {@code <index-dir>} with
 * its postings format {@link LeiaServerPostings} for every field, as a shard of Elasticsearch has
 * formats of its own, and {@code <plain-dir>} with Lucene's codec only. Not part of the server's jar.
 */
public final class ServerIndexWriter {

    private ServerIndexWriter() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: ServerIndexWriter <index-dir> <plain-dir>");
        }
        // As Elasticsearch's bootstrap does, before its formats are made.
        LogConfigurator.configureESLogging();
        write(Path.of(args[0]), new Lucene101Codec() {
            @Override
            public PostingsFormat getPostingsFormatForField(String field) {
                return new LeiaServerPostings();
            }
        });
        write(Path.of(args[1]), new Lucene101Codec());
    }

    private static void write(Path dir, Codec codec) throws IOException {
        // Writing over an old index would add a commit; the tests expect segments_1.
        if (Files.isDirectory(dir)) {
            try (Stream<Path> files = Files.list(dir)) {
                for (Path file : files.toList()) {
                    Files.delete(file);
                }
            }
        }
        IndexWriterConfig config = new IndexWriterConfig(new StandardAnalyzer())
                .setCodec(codec)
                .setOpenMode(IndexWriterConfig.OpenMode.CREATE)
                .setUseCompoundFile(false);
        try (FSDirectory directory = FSDirectory.open(dir);
                IndexWriter writer = new IndexWriter(directory, config)) {
            String[] titles = {"server shard", "server codecs", "read with the server"};
            for (int i = 0; i < titles.length; i++) {
                Document doc = new Document();
                doc.add(new StringField("id", Integer.toString(i), Field.Store.YES));
                doc.add(new TextField("title", titles[i], Field.Store.YES));
                doc.add(new NumericDocValuesField("rank", i));
                doc.add(new KnnFloatVectorField("vector", new float[] {i, 1}));
                writer.addDocument(doc);
            }
            writer.commit();
        }
    }
}
