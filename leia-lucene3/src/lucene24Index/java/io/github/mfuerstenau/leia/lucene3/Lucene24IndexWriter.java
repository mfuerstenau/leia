package io.github.mfuerstenau.leia.lucene3;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

/**
 * Writes a small test index with Lucene 2.4 (build tooling, not part of leia).
 *
 * <p>Compiled against and run with lucene-core 2.4.1 only (see {@code leia-lucene3/build.gradle.kts}).
 * The index has 3 documents, one of them deleted, in one segment without a compound file. Each document has an id, a body with term vectors (positions and offsets) and a
 * binary stored field.
 */
public final class Lucene24IndexWriter {

    private Lucene24IndexWriter() {}

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
        Directory directory = FSDirectory.getDirectory(dir);
        IndexWriter writer =
                new IndexWriter(directory, new StandardAnalyzer(), true, IndexWriter.MaxFieldLength.UNLIMITED);
        writer.setUseCompoundFile(false);
        for (int i = 1; i <= 3; i++) {
            Document doc = new Document();
            doc.add(new Field("id", Integer.toString(i), Field.Store.YES, Field.Index.NOT_ANALYZED));
            doc.add(new Field(
                    "body",
                    "document number " + i + " of the old index",
                    Field.Store.YES,
                    Field.Index.ANALYZED,
                    Field.TermVector.WITH_POSITIONS_OFFSETS));
            doc.add(new Field("raw", ("bytes" + i).getBytes(StandardCharsets.UTF_8), Field.Store.YES));
            writer.addDocument(doc);
        }
        writer.deleteDocuments(new Term("id", "2"));
        writer.close();
        directory.close();
    }
}
