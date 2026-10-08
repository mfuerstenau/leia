package io.github.mfuerstenau.leia.testcodec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.codecs.Codec;
import org.apache.lucene.codecs.DocValuesFormat;
import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.codecs.PostingsFormat;
import org.apache.lucene.codecs.lucene104.Lucene104Codec;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;

/**
 * Writes the two test indexes that need the test codec jar (ADR 0018): {@code <codec-dir>} with the
 * codec {@code LeiaTest}, and {@code <formats-dir>} with Lucene's codec and the jar's postings, doc
 * values and KNN vectors formats for every field. Not part of the jar.
 */
public final class TestCodecIndexWriter {

    private TestCodecIndexWriter() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: TestCodecIndexWriter <codec-dir> <formats-dir>");
        }
        write(Path.of(args[0]), new LeiaTestCodec());
        write(Path.of(args[1]), new Lucene104Codec() {
            @Override
            public PostingsFormat getPostingsFormatForField(String field) {
                return new LeiaTestPostingsFormat();
            }

            @Override
            public DocValuesFormat getDocValuesFormatForField(String field) {
                return new LeiaTestDocValuesFormat();
            }

            @Override
            public KnnVectorsFormat getKnnVectorsFormatForField(String field) {
                return new LeiaTestKnnVectorsFormat();
            }
        });
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
            String[] titles = {"custom codecs", "custom formats", "codecs from jars"};
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
