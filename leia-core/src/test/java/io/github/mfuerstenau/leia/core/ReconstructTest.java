package io.github.mfuerstenau.leia.core;

import static io.github.mfuerstenau.leia.core.TestIndexes.testIndex;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.within;

import io.github.mfuerstenau.leia.core.Reconstruct.Source;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.CancellationException;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PayloadAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.LatLonPoint;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.geo.GeoEncodingUtils;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Rebuilds the terms a document was indexed with (ADR 0029). */
class ReconstructTest {

    @TempDir
    Path tmp;

    @Test
    void rebuildsAFieldFromThePostingsOfTheDocumentsSegment() throws Exception {
        Path dir = writeIndex(
                List.of(doc -> doc.add(new TextField("title", "first segment", Field.Store.NO))),
                List.of(
                        doc -> doc.add(new TextField("title", "other words", Field.Store.NO)),
                        doc -> doc.add(new TextField("title", "The quick fox saw the dog", Field.Store.NO))));

        try (ReadOnlyIndex index = ReadOnlyIndex.open(dir)) {
            Reconstruct.Field field = reconstruct(index, 2, "title");

            assertThat(field.source()).isEqualTo(Source.POSTINGS);
            assertThat(field.indexOptions()).isEqualTo(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS);
            // In term order, lowercased by the StandardAnalyzer, with no offsets indexed.
            assertThat(field.terms())
                    .extracting(term -> term.term().utf8ToString(), TermVectorTerm::freq, ReconstructTest::positions)
                    .containsExactly(
                            tuple("dog", 1, List.of(5)),
                            tuple("fox", 1, List.of(2)),
                            tuple("quick", 1, List.of(1)),
                            tuple("saw", 1, List.of(3)),
                            tuple("the", 2, List.of(0, 4)));
            assertThat(field.terms().getFirst().positions().getFirst().startOffset())
                    .isEmpty();
        }
    }

    @Test
    void readsATermVectorThatHoldsAllThatThePostingsHold() throws Exception {
        FieldType withVectors = textType(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS);
        withVectors.setStoreTermVectors(true);
        withVectors.setStoreTermVectorPositions(true);
        withVectors.setStoreTermVectorOffsets(true);
        Path dir = writeIndex(List.of(doc -> doc.add(new Field("body", "red apple red", withVectors))));

        try (ReadOnlyIndex index = ReadOnlyIndex.open(dir)) {
            Reconstruct.Field field = reconstruct(index, 0, "body");

            assertThat(field.source()).isEqualTo(Source.TERM_VECTOR);
            assertThat(field.terms()).isEqualTo(index.termVector(0, "body"));
            assertThat(index.reconstructCount(0, Optional.of(List.of("body")))).isEqualTo(OptionalLong.of(0));
        }
    }

    @Test
    void readsThePostingsWhenTheTermVectorHoldsLess() throws Exception {
        // Offsets in the postings, but not in the term vector.
        FieldType type = textType(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS);
        type.setStoreTermVectors(true);
        type.setStoreTermVectorPositions(true);
        Path dir = writeIndex(List.of(doc -> doc.add(new Field("body", "red apple", type))));

        try (ReadOnlyIndex index = ReadOnlyIndex.open(dir)) {
            Reconstruct.Field field = reconstruct(index, 0, "body");

            assertThat(field.source()).isEqualTo(Source.POSTINGS);
            assertThat(field.terms())
                    .extracting(
                            term -> term.term().utf8ToString(),
                            term -> term.positions().getFirst())
                    .containsExactly(
                            tuple("apple", position(1, 4, 9, Optional.empty())),
                            tuple("red", position(0, 0, 3, Optional.empty())));
            assertThat(index.reconstructCount(0, Optional.of(List.of("body")))).isEqualTo(OptionalLong.of(2));
        }
    }

    @Test
    void postingsWithoutPositionsGiveTermsAndFreqs() throws Exception {
        FieldType freqs = textType(IndexOptions.DOCS_AND_FREQS);
        Path dir = writeIndex(List.of(doc -> {
            doc.add(new StringField("key", new BytesRef(new byte[] {(byte) 0xff, 0}), Field.Store.NO));
            doc.add(new StringField("key", "plain", Field.Store.NO));
            doc.add(new Field("labels", "a b a", freqs));
        }));

        try (ReadOnlyIndex index = ReadOnlyIndex.open(dir)) {
            List<Reconstruct.Field> fields =
                    index.reconstruct(0, Optional.of(List.of("labels", "key")), ScanProgress.NONE);

            assertThat(fields).extracting(Reconstruct.Field::field).containsExactly("labels", "key");
            assertThat(fields.get(0).terms())
                    .extracting(term -> term.term().utf8ToString(), TermVectorTerm::freq, TermVectorTerm::positions)
                    .containsExactly(tuple("a", 2, List.of()), tuple("b", 1, List.of()));
            assertThat(fields.get(1).indexOptions()).isEqualTo(IndexOptions.DOCS);
            assertThat(fields.get(1).terms())
                    .extracting(TermVectorTerm::term, TermVectorTerm::freq)
                    .containsExactly(
                            tuple(new BytesRef("plain"), 1), tuple(new BytesRef(new byte[] {(byte) 0xff, 0}), 1));
        }
    }

    @Test
    void keepsStackedTokensGapsAndPayloads() throws Exception {
        FieldType type = textType(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS);
        // "fast" and "quick" at position 0, a gap at 1, "car" at 2.
        Path dir = writeIndex(List.of(doc -> doc.add(new Field(
                "tagged",
                new Tokens(new String[] {"fast", "quick", "car"}, new int[] {1, 0, 2}, new String[] {"JJ", null, "NN"}),
                type))));

        try (ReadOnlyIndex index = ReadOnlyIndex.open(dir)) {
            Reconstruct.Field field = reconstruct(index, 0, "tagged");

            assertThat(field.terms())
                    .extracting(
                            term -> term.term().utf8ToString(),
                            term -> term.positions().getFirst())
                    .containsExactly(
                            tuple("car", positionOnly(2, Optional.of(new BytesRef("NN")))),
                            tuple("fast", positionOnly(0, Optional.of(new BytesRef("JJ")))),
                            tuple("quick", positionOnly(0, Optional.empty())));
        }
    }

    @Test
    void rebuildsADeletedDocument() throws Exception {
        Path dir = writeIndex(List.of(
                doc -> doc.add(new TextField("title", "gone now", Field.Store.NO)),
                doc -> doc.add(new TextField("title", "still here", Field.Store.NO))));
        try (IndexWriter writer = new IndexWriter(
                FSDirectory.open(dir), new IndexWriterConfig().setMergePolicy(NoMergePolicy.INSTANCE))) {
            writer.deleteDocuments(new Term("id", "0"));
        }

        try (ReadOnlyIndex index = ReadOnlyIndex.open(dir)) {
            assertThat(index.document(0).deleted()).isTrue();
            assertThat(reconstruct(index, 0, "title").terms())
                    .extracting(term -> term.term().utf8ToString())
                    .containsExactly("gone", "now");
        }
    }

    @Test
    void allIndexedFieldsInFieldNumberOrder() throws Exception {
        Path dir = writeIndex(List.of(doc -> {
            doc.add(new TextField("title", "a title", Field.Store.YES));
            doc.add(new StoredField("stored_only", "not indexed"));
            doc.add(new NumericDocValuesField("number", 4));
        }));

        try (ReadOnlyIndex index = ReadOnlyIndex.open(dir)) {
            assertThat(index.reconstruct(0, Optional.empty(), ScanProgress.NONE))
                    .extracting(Reconstruct.Field::field)
                    .containsExactly("id", "title");
            // "id" has 1 term and "title" 2.
            assertThat(index.reconstructCount(0, Optional.empty())).isEqualTo(OptionalLong.of(3));
        }
    }

    @Test
    void reportsProgressAndCanBeCancelled() throws Exception {
        String words = IntStream.range(0, 3000).mapToObj(i -> "w" + i).collect(Collectors.joining(" "));
        Path dir = writeIndex(List.of(doc -> doc.add(new TextField("body", words, Field.Store.NO))));

        try (ReadOnlyIndex index = ReadOnlyIndex.open(dir)) {
            List<Long> reports = new ArrayList<>();
            index.reconstruct(0, Optional.of(List.of("body")), reports::add);

            assertThat(reports).containsExactly(0L, 1024L, 2048L, 3000L);
            assertThatThrownBy(() -> index.reconstruct(0, Optional.of(List.of("body")), scanned -> {
                        if (scanned > 0) {
                            throw new CancellationException();
                        }
                    }))
                    .isInstanceOf(CancellationException.class);
        }
    }

    @Test
    void rejectsUnknownDocumentsAndFieldsAndFieldsWithoutTermsOrPoints() throws Exception {
        Path dir = writeIndex(List.of(doc -> doc.add(new StoredField("stored_only", "x"))));

        try (ReadOnlyIndex index = ReadOnlyIndex.open(dir)) {
            assertThatThrownBy(() -> index.reconstruct(1, Optional.empty(), ScanProgress.NONE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("No document 1; maxDoc is 1");
            assertThatThrownBy(() -> index.reconstruct(0, Optional.of(List.of("nope")), ScanProgress.NONE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("No field nope");
            assertThatThrownBy(() -> index.reconstructCount(0, Optional.of(List.of("stored_only"))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Field stored_only has neither terms nor points");
        }
    }

    @Test
    void rebuildsThePointsOfAPointField() throws Exception {
        Path dir = writeIndex(
                List.of(doc -> doc.add(new IntPoint("number", 7))),
                List.of(doc -> doc.add(new IntPoint("number", 1)), doc -> {
                    // Multi-valued, a duplicate kept, in unsigned byte order: -2 before 3.
                    doc.add(new IntPoint("number", 3));
                    doc.add(new IntPoint("number", -2));
                    doc.add(new IntPoint("number", 3));
                }));

        try (ReadOnlyIndex index = ReadOnlyIndex.open(dir)) {
            Reconstruct.Field field = reconstruct(index, 2, "number");

            assertThat(field.indexOptions()).isEqualTo(IndexOptions.NONE);
            assertThat(field.source()).isEqualTo(Source.POSTINGS);
            assertThat(field.terms()).isEmpty();
            assertThat(field.points()).map(ReconstructTest::intPoint).containsExactly(-2, 3, 3);
            assertThat(reconstruct(index, 0, "number").points())
                    .map(ReconstructTest::intPoint)
                    .containsExactly(7);
            // The 4 points of the document's segment.
            assertThat(index.reconstructCount(2, Optional.of(List.of("number"))))
                    .isEqualTo(OptionalLong.of(4));
        }
    }

    @Test
    void rebuildsPointsOfSeveralDimensions() throws Exception {
        Path dir = writeIndex(List.of(
                doc -> doc.add(new LatLonPoint("location", 52.52, 13.405)),
                doc -> doc.add(new LatLonPoint("location", -33.87, 151.21))));

        try (ReadOnlyIndex index = ReadOnlyIndex.open(dir)) {
            List<BytesRef> points = reconstruct(index, 1, "location").points();

            assertThat(points).hasSize(1);
            byte[] packed = BytesRef.deepCopyOf(points.getFirst()).bytes;
            assertThat(GeoEncodingUtils.decodeLatitude(packed, 0)).isCloseTo(-33.87, within(1e-6));
            assertThat(GeoEncodingUtils.decodeLongitude(packed, 4)).isCloseTo(151.21, within(1e-6));
        }
    }

    @Test
    void rebuildsTermsAndPointsOfOneField() throws Exception {
        Path dir = writeIndex(List.of(doc -> {
            doc.add(new StringField("code", "abc", Field.Store.NO));
            doc.add(new IntPoint("code", 42));
        }));

        try (ReadOnlyIndex index = ReadOnlyIndex.open(dir)) {
            Reconstruct.Field field = reconstruct(index, 0, "code");

            assertThat(field.terms())
                    .extracting(term -> term.term().utf8ToString())
                    .containsExactly("abc");
            assertThat(field.points()).map(ReconstructTest::intPoint).containsExactly(42);
            // Every field with terms or points, and 1 term each plus 1 point.
            assertThat(index.reconstruct(0, Optional.empty(), ScanProgress.NONE))
                    .extracting(Reconstruct.Field::field)
                    .containsExactly("id", "code");
            assertThat(index.reconstructCount(0, Optional.empty())).isEqualTo(OptionalLong.of(3));
        }
    }

    @Test
    void rebuildsThePointsOfADeletedDocument() throws Exception {
        Path dir = writeIndex(
                List.of(doc -> doc.add(new IntPoint("number", 5)), doc -> doc.add(new IntPoint("number", 6))));
        try (IndexWriter writer = new IndexWriter(
                FSDirectory.open(dir), new IndexWriterConfig().setMergePolicy(NoMergePolicy.INSTANCE))) {
            writer.deleteDocuments(new Term("id", "0"));
        }

        try (ReadOnlyIndex index = ReadOnlyIndex.open(dir)) {
            assertThat(reconstruct(index, 0, "number").points())
                    .map(ReconstructTest::intPoint)
                    .containsExactly(5);
        }
    }

    @Test
    void reportsProgressThroughThePointsAndCanBeCancelled() throws Exception {
        List<Fields> docs = IntStream.range(0, 3000)
                .<Fields>mapToObj(i -> doc -> doc.add(new IntPoint("number", i)))
                .toList();
        Path dir = writeIndex(docs);

        try (ReadOnlyIndex index = ReadOnlyIndex.open(dir)) {
            List<Long> reports = new ArrayList<>();
            assertThat(index.reconstruct(5, Optional.of(List.of("number")), reports::add)
                            .getFirst()
                            .points())
                    .map(ReconstructTest::intPoint)
                    .containsExactly(5);

            assertThat(reports).containsExactly(0L, 1024L, 2048L, 3000L);
            assertThatThrownBy(() -> index.reconstruct(0, Optional.of(List.of("number")), scanned -> {
                        if (scanned > 0) {
                            throw new CancellationException();
                        }
                    }))
                    .isInstanceOf(CancellationException.class);
        }
    }

    @Test
    void rebuildsADocumentOfALucene9Index() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(testIndex("lucene9"))) {
            // "document number 1"; document 2 (doc id 1) is deleted.
            assertThat(reconstruct(index, 0, "body").terms())
                    .extracting(term -> term.term().utf8ToString(), ReconstructTest::positions)
                    .containsExactly(
                            tuple("1", List.of(2)), tuple("document", List.of(0)), tuple("number", List.of(1)));
        }
    }

    private static Reconstruct.Field reconstruct(ReadOnlyIndex index, int docId, String field) throws IOException {
        return index.reconstruct(docId, Optional.of(List.of(field)), ScanProgress.NONE)
                .getFirst();
    }

    private static int intPoint(BytesRef packed) {
        return IntPoint.decodeDimension(packed.bytes, packed.offset);
    }

    private static List<Integer> positions(TermVectorTerm term) {
        return term.positions().stream()
                .map(position -> position.position().orElseThrow())
                .toList();
    }

    private static TermPosition position(int position, int start, int end, Optional<BytesRef> payload) {
        return new TermPosition(OptionalInt.of(position), OptionalInt.of(start), OptionalInt.of(end), payload);
    }

    private static TermPosition positionOnly(int position, Optional<BytesRef> payload) {
        return new TermPosition(OptionalInt.of(position), OptionalInt.empty(), OptionalInt.empty(), payload);
    }

    private static FieldType textType(IndexOptions options) {
        FieldType type = new FieldType();
        type.setIndexOptions(options);
        type.setTokenized(true);
        return type;
    }

    @FunctionalInterface
    private interface Fields {
        void addTo(Document doc);
    }

    /** Writes one segment per list of documents; the documents get the ids 0, 1, … in order. */
    @SafeVarargs
    private Path writeIndex(List<Fields>... segments) throws IOException {
        Path dir = tmp.resolve("index");
        try (IndexWriter writer = new IndexWriter(
                FSDirectory.open(dir), new IndexWriterConfig().setMergePolicy(NoMergePolicy.INSTANCE))) {
            int id = 0;
            for (List<Fields> segment : segments) {
                for (Fields fields : segment) {
                    Document doc = new Document();
                    doc.add(new StringField("id", Integer.toString(id++), Field.Store.NO));
                    fields.addTo(doc);
                    writer.addDocument(doc);
                }
                writer.commit();
            }
        }
        return dir;
    }

    /** Fixed tokens with position increments and a payload each; a {@code null} payload means none. */
    private static final class Tokens extends TokenStream {

        private final CharTermAttribute term = addAttribute(CharTermAttribute.class);
        private final PositionIncrementAttribute increment = addAttribute(PositionIncrementAttribute.class);
        private final OffsetAttribute offset = addAttribute(OffsetAttribute.class);
        private final PayloadAttribute payload = addAttribute(PayloadAttribute.class);
        private final String[] tokens;
        private final int[] increments;
        private final String[] payloads;
        private int next;

        Tokens(String[] tokens, int[] increments, String[] payloads) {
            this.tokens = tokens;
            this.increments = increments;
            this.payloads = payloads;
        }

        @Override
        public boolean incrementToken() {
            if (next == tokens.length) {
                return false;
            }
            clearAttributes();
            term.append(tokens[next]);
            increment.setPositionIncrement(increments[next]);
            offset.setOffset(0, tokens[next].length());
            payload.setPayload(payloads[next] == null ? null : new BytesRef(payloads[next]));
            next++;
            return true;
        }

        @Override
        public void reset() throws IOException {
            super.reset();
            next = 0;
        }
    }
}
