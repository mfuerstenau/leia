package io.github.mfuerstenau.leia.lucene3;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.NumericField;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LogDocMergePolicy;
import org.apache.lucene.index.NoDeletionPolicy;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Version;

/**
 * Writes a small test index with Lucene 3.6 (build tooling, not part of leia).
 *
 * <p>Compiled against and run with the unrelocated lucene-core 3.6.2 only (see {@code
 * leia-lucene3/build.gradle.kts}). Every commit is kept. The first commit has documents 1 to 4 in
 * the segment {@code _0}, without a compound file; the second adds documents 5 to 7 in {@code _1},
 * a compound file, and deletes document 2. Each commit has user data. Each document has:
 *
 * <ul>
 *   <li>{@code id}, stored and not analyzed;
 *   <li>{@code body}, stored and analyzed, with term vectors (positions and offsets): {@code alpha}
 *       in every document, {@code beta} in every other, {@code gamma} in the first only;
 *   <li>{@code tag}, indexed docs-only without norms;
 *   <li>{@code price}, a stored {@code NumericField} of ints.
 * </ul>
 */
public final class Lucene3IndexWriter {

    private Lucene3IndexWriter() {}

    public static void main(String[] args) throws IOException {
        File dir = new File(args[0]);
        // Start empty, so every run writes the same files.
        dir.mkdirs();
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (!file.delete()) {
                    throw new IOException("Cannot delete " + file);
                }
            }
        }
        try (Directory directory = FSDirectory.open(dir)) {
            try (IndexWriter writer = new IndexWriter(directory, config(false, IndexWriterConfig.OpenMode.CREATE))) {
                for (int i = 1; i <= 4; i++) {
                    writer.addDocument(document(i));
                }
                writer.commit(Map.of("commit", "first"));
            }
            try (IndexWriter writer = new IndexWriter(directory, config(true, IndexWriterConfig.OpenMode.APPEND))) {
                for (int i = 5; i <= 7; i++) {
                    writer.addDocument(document(i));
                }
                writer.deleteDocuments(new Term("id", "2"));
                writer.commit(Map.of("commit", "second"));
            }
        }
    }

    /** Keeps every commit and every segment: no merges, compound files as {@code compound} says. */
    private static IndexWriterConfig config(boolean compound, IndexWriterConfig.OpenMode mode) {
        LogDocMergePolicy mergePolicy = new LogDocMergePolicy();
        mergePolicy.setUseCompoundFile(compound);
        mergePolicy.setNoCFSRatio(1.0);
        mergePolicy.setMergeFactor(1000);
        return new IndexWriterConfig(Version.LUCENE_36, new StandardAnalyzer(Version.LUCENE_36))
                .setOpenMode(mode)
                .setIndexDeletionPolicy(NoDeletionPolicy.INSTANCE)
                .setMergePolicy(mergePolicy);
    }

    private static Document document(int i) {
        Document doc = new Document();
        doc.add(new Field("id", Integer.toString(i), Field.Store.YES, Field.Index.NOT_ANALYZED));
        StringBuilder body = new StringBuilder("alpha document " + i);
        if (i % 2 == 1) {
            body.append(" beta");
        }
        if (i == 1) {
            body.append(" gamma");
        }
        doc.add(new Field(
                "body",
                body.toString(),
                Field.Store.YES,
                Field.Index.ANALYZED,
                Field.TermVector.WITH_POSITIONS_OFFSETS));
        Field tag = new Field("tag", i % 2 == 0 ? "even" : "odd", Field.Store.NO, Field.Index.NOT_ANALYZED_NO_NORMS);
        tag.setIndexOptions(FieldInfo.IndexOptions.DOCS_ONLY);
        doc.add(tag);
        doc.add(new NumericField("price", Field.Store.YES, true).setIntValue(i * 10));
        return doc;
    }
}
