package io.github.mfuerstenau.leia.testserver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.store.FSDirectory;

/**
 * Writes the fake k-NN plugin's test index into {@code <knn-dir>} with its {@link LeiaKnnCodec}
 * (ADR 0092): three documents with a vector of two floats. Not part of the plugin's jar.
 */
public final class KnnIndexWriter {

    private KnnIndexWriter() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: KnnIndexWriter <knn-dir>");
        }
        Path dir = Path.of(args[0]);
        // Writing over an old index would add a commit; the tests expect segments_1.
        if (Files.isDirectory(dir)) {
            try (Stream<Path> files = Files.list(dir)) {
                for (Path file : files.toList()) {
                    Files.delete(file);
                }
            }
        }
        IndexWriterConfig config = new IndexWriterConfig(new StandardAnalyzer())
                .setCodec(new LeiaKnnCodec())
                .setOpenMode(IndexWriterConfig.OpenMode.CREATE)
                .setUseCompoundFile(false);
        try (FSDirectory directory = FSDirectory.open(dir);
                IndexWriter writer = new IndexWriter(directory, config)) {
            String[] titles = {"knn shard", "knn plugin", "read with the plugin"};
            for (int i = 0; i < titles.length; i++) {
                Document doc = new Document();
                doc.add(new StringField("id", Integer.toString(i), Field.Store.YES));
                doc.add(new TextField("title", titles[i], Field.Store.YES));
                doc.add(new KnnFloatVectorField("vector", new float[] {i, 1}, VectorSimilarityFunction.EUCLIDEAN));
                writer.addDocument(doc);
            }
            writer.commit();
        }
    }
}
