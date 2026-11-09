package io.github.mfuerstenau.leia.core;

import static io.github.mfuerstenau.leia.core.TestIndexes.testIndex;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.github.mfuerstenau.leia.core.PostingsPage.Posting;
import io.github.mfuerstenau.leia.core.TermPage.Entry;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.FixedBitSet;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TermBrowseTest {

    /** The "body" field: postings with positions, offsets and payloads. */
    private static final FieldType BODY = new FieldType(TextField.TYPE_NOT_STORED);

    static {
        BODY.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS);
        BODY.freeze();
    }

    private static final BytesRef BINARY = new BytesRef(new byte[] {(byte) 0xff, 0});

    @TempDir
    Path tmp;

    @Test
    void pagesThroughTheTermsOfAField() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            TermPage first = index.terms("body", null, 2);

            // The deleted document 1 still counts.
            assertThat(first.terms()).containsExactly(entry("blue", 4, 4), entry("green", 1, 1));
            assertThat(first.next()).isEqualTo(new BytesRef("red"));

            TermPage second = index.terms("body", first.next(), 2);
            assertThat(second.terms()).containsExactly(entry("red", 1, 2), entry("yellow", 1, 1));
            assertThat(second.next()).isNull();
        }
    }

    @Test
    void seeksToTheFirstTermFromAText() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            assertThat(index.terms("body", new BytesRef("c"), 1).terms()).containsExactly(entry("green", 1, 1));
            assertThat(index.terms("body", new BytesRef("red"), 1).terms()).containsExactly(entry("red", 1, 2));

            TermPage pastTheEnd = index.terms("body", new BytesRef("z"), 10);
            assertThat(pastTheEnd.terms()).isEmpty();
            assertThat(pastTheEnd.next()).isNull();
        }
    }

    @Test
    void fieldWithoutPostingsHasNoTerms() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            TermPage page = index.terms("stored", null, 10);

            assertThat(page.terms()).isEmpty();
            assertThat(page.next()).isNull();
        }
    }

    @Test
    void binaryTermsAreBytes() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            assertThat(index.terms("key", null, 10).terms()).containsExactly(new Entry(BINARY, 1, 1));
            assertThat(index.postings("key", BINARY, 0, 10).orElseThrow().postings())
                    .containsExactly(new Posting(3, false, null));
        }
    }

    @Test
    void pagesThroughThePostingsOfATerm() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            PostingsPage first =
                    index.postings("body", new BytesRef("blue"), 0, 3).orElseThrow();

            assertThat(first.docFreq()).isEqualTo(4);
            assertThat(first.totalTermFreq()).isEqualTo(4);
            // Across both segments; document 1 is deleted.
            assertThat(first.postings())
                    .containsExactly(posting(0, false, 1), posting(1, true, 1), posting(2, false, 1));
            assertThat(first.nextDoc()).isEqualTo(3);

            PostingsPage second =
                    index.postings("body", new BytesRef("blue"), 3, 3).orElseThrow();
            assertThat(second.postings()).containsExactly(posting(3, false, 1));
            assertThat(second.nextDoc()).isNull();
        }
    }

    @Test
    void marksThePostingsInAFilterOrReadsOnlyThose() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            DocFilter filter = filter(4, 1, 3);
            BytesRef blue = new BytesRef("blue");

            PostingsPage marked = index.postings("body", blue, 0, 10, new PostingsPage.Filter(filter, false))
                    .orElseThrow();
            assertThat(marked.postings())
                    .extracting(Posting::docId, Posting::inFilter)
                    .containsExactly(tuple(0, false), tuple(1, true), tuple(2, false), tuple(3, true));

            PostingsPage.Filter only = new PostingsPage.Filter(filter, true);
            PostingsPage first = index.postings("body", blue, 0, 1, only).orElseThrow();
            assertThat(first.docFreq()).isEqualTo(4);
            // The deleted document 1 is in the filter.
            assertThat(first.postings()).containsExactly(new Posting(1, true, 1, true));
            assertThat(first.nextDoc()).isEqualTo(3);
            PostingsPage second = index.postings("body", blue, 2, 1, only).orElseThrow();
            assertThat(second.postings()).extracting(Posting::docId).containsExactly(3);
            assertThat(second.nextDoc()).isNull();

            // Red is only in document 0, which the filter leaves out.
            PostingsPage none =
                    index.postings("body", new BytesRef("red"), 0, 10, only).orElseThrow();
            assertThat(none.postings()).isEmpty();
            assertThat(none.nextDoc()).isNull();
        }
    }

    @Test
    void postingsStartAtTheFirstDocumentFromAnId() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            PostingsPage red =
                    index.postings("body", new BytesRef("red"), 0, 10).orElseThrow();
            assertThat(red.postings()).containsExactly(posting(0, false, 2));

            PostingsPage afterRed =
                    index.postings("body", new BytesRef("red"), 1, 10).orElseThrow();
            assertThat(afterRed.docFreq()).isEqualTo(1);
            assertThat(afterRed.postings()).isEmpty();
            assertThat(afterRed.nextDoc()).isNull();

            assertThat(index.postings("body", new BytesRef("blue"), 100, 10)
                            .orElseThrow()
                            .postings())
                    .isEmpty();
        }
    }

    @Test
    void postingsOfAFieldWithoutFrequenciesHaveNone() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            assertThat(index.postings("id", new BytesRef("3"), 0, 10)
                            .orElseThrow()
                            .postings())
                    .containsExactly(new Posting(2, false, null));
        }
    }

    @Test
    void unknownTermHasNoPostings() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            assertThat(index.postings("body", new BytesRef("purple"), 0, 10)).isEmpty();
            assertThat(index.postings("stored", new BytesRef("x"), 0, 10)).isEmpty();
        }
    }

    @Test
    void readsThePositionsOfATermInADocument() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            assertThat(index.positions(0, "body", new BytesRef("red")))
                    .hasValue(List.of(position(0, 0, 3, new BytesRef("p0")), position(2, 9, 12, null)));
            // From the second segment.
            assertThat(index.positions(2, "body", new BytesRef("yellow"))).hasValue(List.of(position(1, 5, 11, null)));
            // A deleted document.
            assertThat(index.positions(1, "body", new BytesRef("green"))).hasValue(List.of(position(0, 0, 5, null)));
        }
    }

    @Test
    void readsThePositionsWithThePostings() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            List<TermPosition> red = List.of(position(0, 0, 3, new BytesRef("p0")), position(2, 9, 12, null));
            assertThat(index.postings("body", new BytesRef("red"), 0, 10, null, 5)
                            .orElseThrow()
                            .postings())
                    .singleElement()
                    .extracting(Posting::positions)
                    .isEqualTo(red);
            // Cut, and from the postings of a deleted document.
            assertThat(index.postings("body", new BytesRef("red"), 0, 10, null, 1)
                            .orElseThrow()
                            .postings()
                            .getFirst()
                            .positions())
                    .isEqualTo(List.of(red.getFirst()));
            assertThat(index.postings("body", new BytesRef("green"), 0, 10, null, 1)
                            .orElseThrow()
                            .postings())
                    .extracting(Posting::deleted, Posting::positions)
                    .containsExactly(tuple(true, List.of(position(0, 0, 5, null))));
            // Without positions indexed the list is empty, and without maxPositions there is none.
            assertThat(index.postings("id", new BytesRef("3"), 0, 10, null, 5)
                            .orElseThrow()
                            .postings())
                    .extracting(Posting::positions)
                    .containsExactly(List.of());
            assertThat(index.postings("body", new BytesRef("red"), 0, 10)
                            .orElseThrow()
                            .postings())
                    .extracting(Posting::positions)
                    .containsExactly((List<TermPosition>) null);
        }
    }

    @Test
    void documentWithoutTheTermHasNoPositions() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            assertThat(index.positions(2, "body", new BytesRef("red"))).isEmpty();
            assertThat(index.positions(0, "body", new BytesRef("purple"))).isEmpty();
            assertThat(index.positions(0, "stored", new BytesRef("x"))).isEmpty();
        }
    }

    @Test
    void fieldWithoutPositionsHasAnEmptyList() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            assertThat(index.positions(2, "id", new BytesRef("3"))).hasValue(List.of());
        }
    }

    @Test
    void rejectsUnknownFieldsAndBadPages() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            assertThatThrownBy(() -> index.terms("nope", null, 10))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("No field nope");
            assertThatThrownBy(() -> index.terms("body", null, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("limit must be at least 1: 0");
            assertThatThrownBy(() -> index.postings("nope", new BytesRef("x"), 0, 10))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> index.postings("body", new BytesRef("blue"), -1, 10))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("fromDoc must not be negative: -1");
            assertThatThrownBy(() -> index.positions(4, "body", new BytesRef("blue")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("No document 4; maxDoc is 4");
            assertThatThrownBy(() -> index.positions(0, "nope", new BytesRef("blue")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void browsesALucene9Index() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(testIndex("lucene9"))) {
            // "document number N" for N = 1..3; document 2 is deleted.
            assertThat(index.terms("body", null, 10).terms())
                    .extracting(entry -> entry.term().utf8ToString())
                    .containsExactly("1", "2", "3", "document", "number");
            assertThat(index.postings("body", new BytesRef("document"), 0, 10)
                            .orElseThrow()
                            .postings())
                    .containsExactly(posting(0, false, 1), posting(1, true, 1), posting(2, false, 1));
            assertThat(index.positions(2, "body", new BytesRef("number")).orElseThrow())
                    .extracting(TermPosition::position)
                    .containsExactly(1);
        }
    }

    private static DocFilter filter(int maxDoc, int... matches) {
        FixedBitSet bits = new FixedBitSet(maxDoc);
        for (int docId : matches) {
            bits.set(docId);
        }
        return new DocFilter(bits, "q", "q", Duration.ZERO);
    }

    private static Entry entry(String term, int docFreq, long totalTermFreq) {
        return new Entry(new BytesRef(term), docFreq, totalTermFreq);
    }

    private static Posting posting(int docId, boolean deleted, int freq) {
        return new Posting(docId, deleted, freq);
    }

    private static TermPosition position(int position, int start, int end, @Nullable BytesRef payload) {
        return new TermPosition(position, start, end, payload);
    }

    /**
     * Four documents with the ids 1 to 4 in two segments: "red blue red" (with payloads on the first
     * two tokens), "green blue" (deleted), then "blue yellow" and "blue". Document 4 also has the
     * binary term 0xff00 in "key"; "stored" has no postings.
     */
    private Path writeIndex() throws IOException {
        Path dir = tmp.resolve("index");
        try (FSDirectory directory = FSDirectory.open(dir);
                IndexWriter writer =
                        new IndexWriter(directory, new IndexWriterConfig().setMergePolicy(NoMergePolicy.INSTANCE))) {
            Document first = document("1");
            first.add(new Field(
                    "body",
                    new PayloadTokens(new String[] {"red", "blue", "red"}, new int[] {0, 4, 9}, "p0", "p1", null),
                    BODY));
            first.add(new StoredField("stored", "not indexed"));
            writer.addDocument(first);
            writer.addDocument(document("2", "green blue"));
            // Committed first, so that the delete marks a document in a segment instead of dropping
            // it from the in-memory buffer.
            writer.commit();
            writer.addDocument(document("3", "blue yellow"));
            Document fourth = document("4", "blue");
            fourth.add(new StringField("key", BINARY, Field.Store.NO));
            writer.addDocument(fourth);
            writer.deleteDocuments(new Term("id", "2"));
            writer.commit();
        }
        return dir;
    }

    private static Document document(String id, String body) {
        Document doc = document(id);
        doc.add(new Field("body", body, BODY));
        return doc;
    }

    private static Document document(String id) {
        Document doc = new Document();
        doc.add(new StringField("id", id, Field.Store.YES));
        return doc;
    }
}
