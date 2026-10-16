package io.github.mfuerstenau.leia.lucene5;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.WhitespaceTokenizer;
import org.apache.lucene.analysis.payloads.DelimitedPayloadTokenFilter;
import org.apache.lucene.analysis.payloads.IdentityEncoder;
import org.apache.lucene.document.BinaryDocValuesField;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.IntField;
import org.apache.lucene.document.LongField;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.SortedNumericDocValuesField;
import org.apache.lucene.document.SortedSetDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LogDocMergePolicy;
import org.apache.lucene.index.NoDeletionPolicy;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;

/**
 * Writes a small test index with Lucene 5.5 (build tooling, not part of leia).
 *
 * <p>Compiled against and run with the unrelocated lucene-core 5.5.5 only (see {@code
 * leia-lucene5/build.gradle.kts}); {@code Lucene4IndexWriter} writes the same index with 4.10. Every
 * commit is kept. The first commit has documents 1 to 4 in the segment {@code _0}, without a compound
 * file; the second adds documents 5 to 7 in {@code _1}, a compound file, deletes document 2 and sets
 * the {@code dv_num} of document 4 to 400. Each commit has user data. Each document has:
 *
 * <ul>
 *   <li>{@code id}, stored and not analyzed;
 *   <li>{@code body}, stored, split at whitespace, with offsets and with term vectors (positions,
 *       offsets and payloads): {@code alpha} in every document with the payload {@code a<i>}, {@code
 *       beta} in every other, {@code gamma} twice in the first only;
 *   <li>{@code tag}, indexed docs-only without norms;
 *   <li>{@code price}, a stored {@code IntField} of {@code i * 10}, and {@code time}, an unstored
 *       {@code LongField} of {@code i * 1000}: trie-encoded terms, which are bytes, not text;
 *   <li>{@code blob}, the stored bytes {@code ff fe i}, which aren't UTF-8;
 *   <li>doc values of every type: {@code dv_num} {@code i} (but none in document 3), {@code dv_bin}
 *       {@code b<i>}, {@code dv_sorted} {@code s<i % 3>}, {@code dv_set} {@code x} and {@code y<i>},
 *       and {@code dv_sorted_num} {@code -i} and {@code i}.
 * </ul>
 */
public final class Lucene5IndexWriter {

    private Lucene5IndexWriter() {}

    public static void main(String[] args) throws IOException {
        Path dir = Path.of(args[0]);
        // Start empty, so every run writes the same files.
        Files.createDirectories(dir);
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.toList()) {
                Files.delete(file);
            }
        }
        try (Directory directory = FSDirectory.open(dir)) {
            try (IndexWriter writer = new IndexWriter(directory, config(false, IndexWriterConfig.OpenMode.CREATE))) {
                for (int i = 1; i <= 4; i++) {
                    writer.addDocument(document(i));
                }
                writer.setCommitData(Map.of("commit", "first"));
                writer.commit();
            }
            try (IndexWriter writer = new IndexWriter(directory, config(true, IndexWriterConfig.OpenMode.APPEND))) {
                for (int i = 5; i <= 7; i++) {
                    writer.addDocument(document(i));
                }
                writer.deleteDocuments(new Term("id", "2"));
                writer.updateNumericDocValue(new Term("id", "4"), "dv_num", 400);
                writer.setCommitData(Map.of("commit", "second"));
                writer.commit();
            }
        }
        // Left behind by the writer; leia's tests check that reading writes none.
        Files.deleteIfExists(Path.of(args[0], "write.lock"));
    }

    /** Keeps every commit and every segment: no merges, compound files as {@code compound} says. */
    private static IndexWriterConfig config(boolean compound, IndexWriterConfig.OpenMode mode) {
        LogDocMergePolicy mergePolicy = new LogDocMergePolicy();
        mergePolicy.setNoCFSRatio(1.0);
        mergePolicy.setMergeFactor(1000);
        return new IndexWriterConfig(new PayloadAnalyzer())
                .setOpenMode(mode)
                .setUseCompoundFile(compound)
                .setIndexDeletionPolicy(NoDeletionPolicy.INSTANCE)
                .setMergePolicy(mergePolicy);
    }

    /** Splits at whitespace and takes what follows a {@code |} as the payload. */
    private static final class PayloadAnalyzer extends Analyzer {
        @Override
        protected TokenStreamComponents createComponents(String fieldName) {
            Tokenizer tokenizer = new WhitespaceTokenizer();
            return new TokenStreamComponents(
                    tokenizer, new DelimitedPayloadTokenFilter(tokenizer, '|', new IdentityEncoder()));
        }
    }

    private static Document document(int i) {
        Document doc = new Document();
        doc.add(new StringField("id", Integer.toString(i), Field.Store.YES));
        StringBuilder body = new StringBuilder("alpha|a" + i + " document " + i);
        if (i % 2 == 1) {
            body.append(" beta");
        }
        if (i == 1) {
            body.append(" gamma gamma");
        }
        FieldType bodyType = new FieldType(TextField.TYPE_STORED);
        bodyType.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS);
        bodyType.setStoreTermVectors(true);
        bodyType.setStoreTermVectorPositions(true);
        bodyType.setStoreTermVectorOffsets(true);
        bodyType.setStoreTermVectorPayloads(true);
        doc.add(new Field("body", body.toString(), bodyType));
        FieldType tagType = new FieldType();
        tagType.setIndexOptions(IndexOptions.DOCS);
        tagType.setTokenized(false);
        tagType.setOmitNorms(true);
        doc.add(new Field("tag", i % 2 == 0 ? "even" : "odd", tagType));
        doc.add(new IntField("price", i * 10, Field.Store.YES));
        doc.add(new LongField("time", i * 1000L, Field.Store.NO));
        doc.add(new StoredField("blob", new byte[] {(byte) 0xff, (byte) 0xfe, (byte) i}));
        if (i != 3) {
            doc.add(new NumericDocValuesField("dv_num", i));
        }
        doc.add(new BinaryDocValuesField("dv_bin", bytes("b" + i)));
        doc.add(new SortedDocValuesField("dv_sorted", bytes("s" + i % 3)));
        for (String value : List.of("x", "y" + i)) {
            doc.add(new SortedSetDocValuesField("dv_set", bytes(value)));
        }
        doc.add(new SortedNumericDocValuesField("dv_sorted_num", i));
        doc.add(new SortedNumericDocValuesField("dv_sorted_num", -i));
        return doc;
    }

    private static BytesRef bytes(String text) {
        return new BytesRef(text.getBytes(StandardCharsets.UTF_8));
    }
}
