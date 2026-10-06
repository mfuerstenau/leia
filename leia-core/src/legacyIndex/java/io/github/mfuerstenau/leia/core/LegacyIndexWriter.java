package io.github.mfuerstenau.leia.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.NIOFSDirectory;

/**
 * Writes a small test index with an older Lucene version (build tooling, not part of leia).
 *
 * <p>Compiled against Lucene 9 and run with Lucene 8 or 9 on the classpath (see {@code
 * leia-core/build.gradle.kts}), so it only uses API that both have. The index has 3 documents, one
 * of them deleted, and commit user data.
 */
public final class LegacyIndexWriter {

    private LegacyIndexWriter() {}

    public static void main(String[] args) throws IOException {
        Path dir = Path.of(args[0]);
        // Start empty, so every run writes the same files (segments_2, one segment with one deletion).
        Files.createDirectories(dir);
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.toList()) {
                Files.delete(file);
            }
        }
        // NIOFSDirectory: MMapDirectory of old Lucene versions may not work on new JVMs.
        try (Directory directory = new NIOFSDirectory(dir);
                IndexWriter writer = new IndexWriter(
                        directory,
                        new IndexWriterConfig()
                                // No merges, which would reclaim the deleted document.
                                .setMergePolicy(NoMergePolicy.INSTANCE))) {
            for (int i = 1; i <= 3; i++) {
                Document doc = new Document();
                doc.add(new StringField("id", Integer.toString(i), Field.Store.YES));
                doc.add(new TextField("body", "document number " + i, Field.Store.YES));
                writer.addDocument(doc);
            }
            // Committed first, so that the delete marks a document in a segment instead of dropping it
            // from the in-memory buffer.
            writer.commit();
            writer.deleteDocuments(new Term("id", "2"));
            writer.setLiveCommitData(Map.of("writer", "leia LegacyIndexWriter").entrySet());
            writer.commit();
        }
    }
}
