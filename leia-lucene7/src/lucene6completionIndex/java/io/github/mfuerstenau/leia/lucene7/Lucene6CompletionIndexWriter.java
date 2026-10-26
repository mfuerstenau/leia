package io.github.mfuerstenau.leia.lucene7;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.codecs.PostingsFormat;
import org.apache.lucene.codecs.lucene62.Lucene62Codec;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.suggest.document.Completion50PostingsFormat;
import org.apache.lucene.search.suggest.document.SuggestField;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

/**
 * Writes a small test index with a completion field with Lucene 6.6 (build tooling, not part of
 * leia), as Elasticsearch 5 and 6 write one: its postings format {@code Completion50} is in {@code
 * lucene-suggest}, which a reader needs.
 *
 * <p>Compiled against and run with the unrelocated lucene-core and lucene-suggest 6.6.6 only (see
 * {@code leia-lucene7/build.gradle.kts}). One commit, one segment, with documents 1 to 3 of {@link
 * #SUGGESTIONS}, each with:
 *
 * <ul>
 *   <li>{@code id}, stored and not analyzed;
 *   <li>{@code suggest}, the suggestion with the weight {@code 10 * i}, in {@code Completion50}.
 * </ul>
 */
public final class Lucene6CompletionIndexWriter {

    /** The suggestions of documents 1 to 3. */
    static final List<String> SUGGESTIONS = List.of("apple", "apricot", "banana");

    private Lucene6CompletionIndexWriter() {}

    public static void main(String[] args) throws IOException {
        Path dir = Path.of(args[0]);
        // Start empty, so every run writes the same files.
        Files.createDirectories(dir);
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.toList()) {
                Files.delete(file);
            }
        }
        IndexWriterConfig config = new IndexWriterConfig(new StandardAnalyzer())
                .setOpenMode(IndexWriterConfig.OpenMode.CREATE)
                .setUseCompoundFile(false)
                .setCodec(new Lucene62Codec() {
                    private final PostingsFormat completion = new Completion50PostingsFormat();

                    @Override
                    public PostingsFormat getPostingsFormatForField(String field) {
                        return field.equals("suggest") ? completion : super.getPostingsFormatForField(field);
                    }
                });
        try (Directory directory = FSDirectory.open(dir);
                IndexWriter writer = new IndexWriter(directory, config)) {
            for (int i = 1; i <= SUGGESTIONS.size(); i++) {
                Document doc = new Document();
                doc.add(new StringField("id", Integer.toString(i), Field.Store.YES));
                doc.add(new SuggestField("suggest", SUGGESTIONS.get(i - 1), 10 * i));
                writer.addDocument(doc);
            }
            writer.commit();
        }
        // Left behind by the writer; leia's tests check that reading writes none.
        Files.deleteIfExists(dir.resolve("write.lock"));
    }
}
