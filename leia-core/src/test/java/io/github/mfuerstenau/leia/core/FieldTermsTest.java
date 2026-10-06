package io.github.mfuerstenau.leia.core;

import static io.github.mfuerstenau.leia.core.TestIndexes.legacyIndex;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mfuerstenau.leia.core.FieldTerms.TopTerm;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FieldTermsTest {

    @TempDir
    Path tmp;

    @Test
    void countsAndRanksTermsAcrossSegments() throws Exception {
        Path dir = writeIndex(tmp.resolve("index"), "red blue red", "green red", "blue yellow");

        try (ReadOnlyIndex index = ReadOnlyIndex.open(dir)) {
            FieldTerms terms = index.fieldTerms("body", 3, ScanProgress.NONE);

            // "green" only occurs in the deleted document 2, and still counts.
            assertThat(terms.field()).isEqualTo("body");
            assertThat(terms.uniqueTerms()).isEqualTo(4);
            assertThat(terms.topTerms())
                    .containsExactly(
                            top("blue", 2, 2),
                            top("red", 2, 3),
                            // "green" and "yellow" tie at 1; the earlier term wins.
                            top("green", 1, 1));
        }
    }

    @Test
    void returnsAllTermsIfThereAreFewerThanTopN() throws Exception {
        Path dir = writeIndex(tmp.resolve("index"), "a b", "b", "c");

        try (ReadOnlyIndex index = ReadOnlyIndex.open(dir)) {
            assertThat(index.fieldTerms("body", 10, ScanProgress.NONE).topTerms())
                    .containsExactly(top("b", 2, 2), top("a", 1, 1), top("c", 1, 1));
        }
    }

    @Test
    void countsWithoutTopTerms() throws Exception {
        Path dir = writeIndex(tmp.resolve("index"), "a b", "b c", "d");

        try (ReadOnlyIndex index = ReadOnlyIndex.open(dir)) {
            FieldTerms terms = index.fieldTerms("body", 0, ScanProgress.NONE);

            assertThat(terms.uniqueTerms()).isEqualTo(4);
            assertThat(terms.topTerms()).isEmpty();
        }
    }

    @Test
    void fieldWithoutPostingsHasNoTerms() throws Exception {
        Path dir = writeIndex(tmp.resolve("index"), "a");
        List<Long> reports = new ArrayList<>();

        try (ReadOnlyIndex index = ReadOnlyIndex.open(dir)) {
            FieldTerms terms = index.fieldTerms("rank", 10, reports::add);

            assertThat(terms).isEqualTo(new FieldTerms("rank", 0, List.of()));
            assertThat(reports).containsExactly(0L);
        }
    }

    @Test
    void keepsBinaryTerms() throws Exception {
        Path dir = tmp.resolve("index");
        BytesRef binary = new BytesRef(new byte[] {(byte) 0xff, 0x00, 0x41});
        try (FSDirectory directory = FSDirectory.open(dir);
                IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig())) {
            Document doc = new Document();
            doc.add(new StringField("key", binary, Field.Store.NO));
            writer.addDocument(doc);
        }

        try (ReadOnlyIndex index = ReadOnlyIndex.open(dir)) {
            assertThat(index.fieldTerms("key", 1, ScanProgress.NONE).topTerms())
                    .containsExactly(new TopTerm(binary, 1, 1));
        }
    }

    @Test
    void reportsProgressAndCanBeCancelled() throws Exception {
        int count = 3 * ReadOnlyIndex.PROGRESS_INTERVAL + 5;
        Path dir = tmp.resolve("index");
        try (FSDirectory directory = FSDirectory.open(dir);
                IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig())) {
            for (int i = 0; i < count; i++) {
                Document doc = new Document();
                doc.add(new StringField("id", Integer.toString(i), Field.Store.NO));
                writer.addDocument(doc);
            }
        }
        List<Long> reports = new ArrayList<>();

        try (ReadOnlyIndex index = ReadOnlyIndex.open(dir)) {
            index.fieldTerms("id", 5, reports::add);
            assertThat(reports).containsExactly(1024L, 2048L, 3072L, (long) count);

            reports.clear();
            assertThatThrownBy(() -> index.fieldTerms("id", 5, scanned -> {
                        reports.add(scanned);
                        if (scanned >= 2048) {
                            throw new CancellationException("stop");
                        }
                    }))
                    .isInstanceOf(CancellationException.class);
            assertThat(reports).containsExactly(1024L, 2048L);
        }
    }

    @Test
    void rejectsUnknownFieldAndNegativeTopN() throws Exception {
        Path dir = writeIndex(tmp.resolve("index"), "a");

        try (ReadOnlyIndex index = ReadOnlyIndex.open(dir)) {
            assertThat(index.hasField("body")).isTrue();
            assertThat(index.hasField("missing")).isFalse();
            assertThatThrownBy(() -> index.fieldTerms("missing", 1, ScanProgress.NONE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("No field missing");
            assertThatThrownBy(() -> index.fieldTerms("body", -1, ScanProgress.NONE))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void scansLucene9Index() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(legacyIndex("lucene9"))) {
            // "document number N" for N = 1..3; document 2 is deleted but still counted.
            FieldTerms terms = index.fieldTerms("body", 3, ScanProgress.NONE);

            assertThat(terms.uniqueTerms()).isEqualTo(5);
            assertThat(terms.topTerms()).containsExactly(top("document", 3, 3), top("number", 3, 3), top("1", 1, 1));
            // One segment: the count needs no scan.
            assertThat(index.fieldTerms("id", 0, ScanProgress.NONE).uniqueTerms())
                    .isEqualTo(3);
        }
    }

    private static TopTerm top(String term, int docFreq, long totalTermFreq) {
        return new TopTerm(new BytesRef(term), docFreq, totalTermFreq);
    }

    /**
     * One document per body, with ids from 1. All but the last body go into the first segment, the
     * last one into a second segment, and document 2 is deleted if there is one. A deleted document
     * must share its segment with a live one: a segment without live documents is dropped.
     */
    private static Path writeIndex(Path dir, String... bodies) throws IOException {
        try (FSDirectory directory = FSDirectory.open(dir);
                IndexWriter writer =
                        new IndexWriter(directory, new IndexWriterConfig().setMergePolicy(NoMergePolicy.INSTANCE))) {
            for (int i = 0; i < bodies.length; i++) {
                if (i == bodies.length - 1 && i > 0) {
                    writer.commit();
                }
                Document doc = new Document();
                doc.add(new StringField("id", Integer.toString(i + 1), Field.Store.NO));
                doc.add(new TextField("body", bodies[i], Field.Store.NO));
                doc.add(new NumericDocValuesField("rank", i));
                writer.addDocument(doc);
            }
            writer.deleteDocuments(new Term("id", "2"));
            writer.commit();
        }
        return dir;
    }
}
