package io.github.mfuerstenau.leia.lucene9;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnByteVectorField;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LogDocMergePolicy;
import org.apache.lucene.index.NoDeletionPolicy;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

/**
 * Writes a test index created by Lucene 8.11 and written to by 9.12 (build tooling, not part of
 * leia), as an index is after an upgrade: Lucene 10 can't read it, since 8 created it.
 *
 * <p>Compiled against and run with the unrelocated lucene-core and backward-codecs 9.12.3 only (see
 * {@code leia-lucene9/build.gradle.kts}). It copies the index of {@code Lucene8IndexWriter} (the
 * second argument) and appends a third commit: documents 9 to 11 in the segment {@code _2}, without a
 * compound file, and document 5 deleted. Lucene 9 keeps a field's schema, so the new documents have
 * only {@code id} and {@code order} of the old fields, and:
 *
 * <ul>
 *   <li>{@code title}, stored and analyzed: {@code north}, {@code north east}, {@code east};
 *   <li>{@code embedding}, a KNN vector of 4 floats compared by cosine: {@code (1, 0, 0, 0)}, {@code
 *       (0.8, 0.6, 0, 0)}, {@code (0, 1, 0, 0)};
 *   <li>{@code code}, a KNN vector of 4 bytes compared by Euclidean distance, in documents 9 and 10
 *       only: {@code (1, 2, 3, 4)}, {@code (4, 3, 2, 1)}.
 * </ul>
 */
public final class Lucene8to9IndexWriter {

    private Lucene8to9IndexWriter() {}

    public static void main(String[] args) throws IOException {
        Path dir = Path.of(args[0]);
        Path lucene8 = Path.of(args[1]);
        // Start empty, so every run writes the same files.
        Files.createDirectories(dir);
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.toList()) {
                Files.delete(file);
            }
        }
        try (Stream<Path> files = Files.list(lucene8)) {
            for (Path file : files.toList()) {
                Files.copy(file, dir.resolve(file.getFileName()));
            }
        }
        try (Directory directory = FSDirectory.open(dir);
                IndexWriter writer = new IndexWriter(directory, config())) {
            writer.addDocument(document(9, "north", new float[] {1, 0, 0, 0}, new byte[] {1, 2, 3, 4}));
            writer.addDocument(document(10, "north east", new float[] {0.8f, 0.6f, 0, 0}, new byte[] {4, 3, 2, 1}));
            writer.addDocument(document(11, "east", new float[] {0, 1, 0, 0}, null));
            writer.deleteDocuments(new Term("id", "5"));
            writer.setLiveCommitData(Map.of("commit", "third").entrySet());
            writer.commit();
        }
        // Left behind by the writer; leia's tests check that reading writes none.
        Files.deleteIfExists(dir.resolve("write.lock"));
    }

    /** As the index was written: no merges, every commit kept, its soft deletes field and sort. */
    private static IndexWriterConfig config() {
        LogDocMergePolicy mergePolicy = new LogDocMergePolicy();
        mergePolicy.setMergeFactor(1000);
        return new IndexWriterConfig(new StandardAnalyzer())
                .setOpenMode(IndexWriterConfig.OpenMode.APPEND)
                .setUseCompoundFile(false)
                .setIndexDeletionPolicy(NoDeletionPolicy.INSTANCE)
                .setMergePolicy(mergePolicy)
                .setSoftDeletesField("__soft_deletes")
                .setIndexSort(new Sort(new SortField("order", SortField.Type.LONG)));
    }

    private static Document document(int i, String title, float[] embedding, byte[] code) {
        Document doc = new Document();
        doc.add(new StringField("id", Integer.toString(i), Field.Store.YES));
        doc.add(new TextField("title", title, Field.Store.YES));
        doc.add(new KnnFloatVectorField("embedding", embedding, VectorSimilarityFunction.COSINE));
        if (code != null) {
            doc.add(new KnnByteVectorField("code", code, VectorSimilarityFunction.EUCLIDEAN));
        }
        doc.add(new NumericDocValuesField("order", i));
        return doc;
    }
}
