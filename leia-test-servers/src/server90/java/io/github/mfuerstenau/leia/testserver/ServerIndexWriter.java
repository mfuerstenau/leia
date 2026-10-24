package io.github.mfuerstenau.leia.testserver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.codecs.Codec;
import org.apache.lucene.codecs.PostingsFormat;
import org.apache.lucene.codecs.lucene90.Lucene90Codec;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.KnnVectorField;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.SortedSetDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;

/**
 * Writes the fake server's test indexes with its Lucene 9.0 (ADR 0090, ADR 0091): {@code <index-dir>}
 * with its postings format {@link LeiaServerPostings} for every field, as a shard of Elasticsearch
 * has formats of its own, and {@code <plain-dir>} with Lucene's codec only. Not part of the server's
 * jar. Documents 0 to 2 each have {@code id}; {@code title}, stored, with term vectors; {@code rank},
 * the numeric doc values {@code i}; {@code tags}, the sorted set doc values {@code all} and {@code
 * t<i>}; {@code count}, the int point {@code 10 * i}; and {@code vector}, the KNN vector {@code (i,
 * 1)} of 9.0, without an encoding.
 */
public final class ServerIndexWriter {

    private ServerIndexWriter() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: ServerIndexWriter <index-dir> <plain-dir>");
        }
        write(Path.of(args[0]), new Lucene90Codec() {
            @Override
            public PostingsFormat getPostingsFormatForField(String field) {
                return new LeiaServerPostings();
            }
        });
        write(Path.of(args[1]), new Lucene90Codec());
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
        FieldType titleType = new FieldType(TextField.TYPE_STORED);
        titleType.setStoreTermVectors(true);
        titleType.setStoreTermVectorPositions(true);
        try (FSDirectory directory = FSDirectory.open(dir);
                IndexWriter writer = new IndexWriter(directory, config)) {
            String[] titles = {"server shard", "server codecs", "read with the server"};
            for (int i = 0; i < titles.length; i++) {
                Document doc = new Document();
                doc.add(new StringField("id", Integer.toString(i), Field.Store.YES));
                doc.add(new Field("title", titles[i], titleType));
                doc.add(new NumericDocValuesField("rank", i));
                doc.add(new SortedSetDocValuesField("tags", new BytesRef("all")));
                doc.add(new SortedSetDocValuesField("tags", new BytesRef("t" + i)));
                doc.add(new IntPoint("count", 10 * i));
                doc.add(new KnnVectorField("vector", new float[] {i, 1}));
                writer.addDocument(doc);
            }
            writer.commit();
        }
    }
}
