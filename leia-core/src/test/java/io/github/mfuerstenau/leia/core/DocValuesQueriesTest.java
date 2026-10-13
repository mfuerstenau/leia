package io.github.mfuerstenau.leia.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.lucene.document.BinaryDocValuesField;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.DoubleDocValuesField;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FloatDocValuesField;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.SortedNumericDocValuesField;
import org.apache.lucene.document.SortedSetDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.similarities.Similarity;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.NumericUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocValuesQueriesTest {

    private static final Similarity BM25 = Similarities.bm25(1.2f, 0.75f);

    @TempDir
    Path tmp;

    @Test
    void findsLongsExactlyInASetAndInARange() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            assertThat(numbers(index, "n", DocValuesNumber.LONG, values("5"))).containsExactly(0);
            assertThat(numbers(index, "n", DocValuesNumber.LONG, values("5", "-3", "5")))
                    .containsExactly(0, 2);
            assertThat(numbers(index, "n", DocValuesNumber.LONG, range(null, "0", true, true)))
                    .containsExactly(2);
            assertThat(numbers(index, "n", DocValuesNumber.LONG, range("5", null, false, true)))
                    .containsExactly(1);
            assertThat(numbers(index, "n", DocValuesNumber.LONG, range(null, null, false, false)))
                    .containsExactly(0, 1, 2);
            // Nothing lies above the largest long, or below the smallest.
            assertThat(numbers(
                            index, "n", DocValuesNumber.LONG, range(Long.toString(Long.MAX_VALUE), null, false, true)))
                    .isEmpty();
            assertThat(numbers(
                            index, "n", DocValuesNumber.LONG, range(null, Long.toString(Long.MIN_VALUE), true, false)))
                    .isEmpty();
        }
    }

    @Test
    void splitsARangeOfRawDoubleBitsAtZero() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            // d: -2.5, 0.0, 1.0
            assertThat(numbers(index, "d", DocValuesNumber.DOUBLE, range("-2.5", "1", true, true)))
                    .containsExactly(0, 1, 2);
            assertThat(numbers(index, "d", DocValuesNumber.DOUBLE, range("-2.5", "1", false, false)))
                    .containsExactly(1);
            assertThat(numbers(index, "d", DocValuesNumber.DOUBLE, range("-3", "-1", true, true)))
                    .containsExactly(0);
            assertThat(numbers(index, "d", DocValuesNumber.DOUBLE, range("0", null, true, true)))
                    .containsExactly(1, 2);
            assertThat(numbers(index, "d", DocValuesNumber.DOUBLE, range(null, "-Infinity", true, true)))
                    .isEmpty();
            assertThat(numbers(index, "d", DocValuesNumber.DOUBLE, range("2", "1", true, true)))
                    .isEmpty();
            assertThat(numbers(index, "d", DocValuesNumber.DOUBLE, values("0"))).containsExactly(1);
            // -0.0 and 0.0 are different values.
            assertThat(numbers(index, "d", DocValuesNumber.DOUBLE, values("-0")))
                    .isEmpty();
            assertThat(numbers(index, "d", DocValuesNumber.DOUBLE, values("-2.5", "1e0")))
                    .containsExactly(0, 2);
        }
    }

    @Test
    void keepsNegativeZeroBelowZeroInARangeOfRawFloatBits() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            // f: 1.5, -0.0, -7.0
            assertThat(numbers(index, "f", DocValuesNumber.FLOAT, range("-1", "2", true, true)))
                    .containsExactly(0, 1);
            assertThat(numbers(index, "f", DocValuesNumber.FLOAT, range("0", null, true, true)))
                    .containsExactly(0);
            assertThat(numbers(index, "f", DocValuesNumber.FLOAT, range(null, "-0", true, false)))
                    .containsExactly(2);
            assertThat(numbers(index, "f", DocValuesNumber.FLOAT, range("-0", "-0", true, true)))
                    .containsExactly(1);
            assertThat(numbers(index, "f", DocValuesNumber.FLOAT, range(null, null, true, true)))
                    .containsExactly(0, 1, 2);
        }
    }

    @Test
    void findsSortableBitsInOneRange() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            // sd: 0.5, -1.0, 2.5; sf: -0.25, 3.0
            assertThat(numbers(index, "sd", DocValuesNumber.SORTABLE_DOUBLE, range("-1.5", "1", true, true)))
                    .containsExactly(0, 1);
            assertThat(numbers(index, "sd", DocValuesNumber.SORTABLE_DOUBLE, range("-1", null, false, true)))
                    .containsExactly(0, 2);
            assertThat(numbers(index, "sd", DocValuesNumber.SORTABLE_DOUBLE, values("2.5")))
                    .containsExactly(2);
            assertThat(numbers(index, "sf", DocValuesNumber.SORTABLE_FLOAT, range(null, "0", true, true)))
                    .containsExactly(0);
            assertThat(numbers(index, "sf", DocValuesNumber.SORTABLE_FLOAT, values("3")))
                    .containsExactly(1);
        }
    }

    @Test
    void matchesADocumentIfAnyOfItsNumbersMatches() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            // sn: {1, 10}, {3}, {-5}
            assertThat(numbers(index, "sn", DocValuesNumber.LONG, range("5", "9", true, true)))
                    .isEmpty();
            assertThat(numbers(index, "sn", DocValuesNumber.LONG, range("9", "10", true, true)))
                    .containsExactly(0);
            assertThat(numbers(index, "sn", DocValuesNumber.LONG, values("-5", "1")))
                    .containsExactly(0, 2);
        }
    }

    @Test
    void findsSortedAndSortedSetBytes() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            // s: apple, banana, cherry; ss: {a, b}, {c}
            assertThat(bytes(index, "s", range(text("b"), text("c"), true, true)))
                    .containsExactly(1);
            assertThat(bytes(index, "s", values(text("apple"), text("cherry")))).containsExactly(0, 2);
            assertThat(bytes(index, "s", values(text("banana")))).containsExactly(1);
            assertThat(bytes(index, "ss", values(text("c")))).containsExactly(1);
            assertThat(bytes(index, "ss", range(null, text("a"), true, true))).containsExactly(0);
            assertThat(bytes(index, "ss", values(text("b"), text("c")))).containsExactly(0, 1);
        }
    }

    @Test
    void comparesBinaryValuesUnsigned() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            // bin: 7f, 80, 8000; the deleted document 4 has 7f too.
            assertThat(bytes(index, "bin", values(bytes(0x80)))).containsExactly(1);
            assertThat(bytes(index, "bin", values(bytes(0x7f), bytes(0x80)))).containsExactly(0, 1);
            assertThat(bytes(index, "bin", range(bytes(0x80), null, true, true)))
                    .containsExactly(1, 2);
            assertThat(bytes(index, "bin", range(bytes(0x80), bytes(0x80, 0), false, true)))
                    .containsExactly(2);
            assertThat(bytes(index, "bin", range(null, bytes(0x80), true, false)))
                    .containsExactly(0);
            assertThat(bytes(index, "bin", range(null, null, true, true))).containsExactly(0, 1, 2);

            Query query = DocValuesQueries.bytes(field(index, "bin"), values(bytes(0x7f)));
            assertThat(matchIds(index.matches(query, true, ScanProgress.NONE))).containsExactly(0, 4);
            assertThat(query.toString()).isEqualTo("bin:[7f]");
            assertThat(DocValuesQueries.bytes(field(index, "bin"), range(bytes(0x80), null, false, true))
                            .toString())
                    .isEqualTo("bin:{[80] TO *]");
        }
    }

    @Test
    void findsValuesByAPrefix() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            assertThat(prefix(index, "s", text("b"))).containsExactly(1);
            assertThat(prefix(index, "s", text("ch"))).containsExactly(2);
            assertThat(prefix(index, "s", text("bananas"))).isEmpty();
            assertThat(prefix(index, "ss", text("c"))).containsExactly(1);
            assertThat(prefix(index, "bin", bytes(0x80))).containsExactly(1, 2);
            assertThat(prefix(index, "bin", bytes(0x80, 0))).containsExactly(2);
            assertThat(prefix(index, "bin", bytes(0x7f))).containsExactly(0);
            assertThat(prefix(index, "bin", bytes(0xff))).isEmpty();
            // The empty prefix matches any value.
            assertThat(prefix(index, "s", text(""))).containsExactly(0, 1, 2);
            assertThat(prefix(index, "ss", text(""))).containsExactly(0, 1);
            assertThat(prefix(index, "bin", text(""))).containsExactly(0, 1, 2);

            assertThat(DocValuesQueries.prefix(field(index, "bin"), bytes(0x7f)).toString())
                    .isEqualTo("bin:[[7f] TO [80]}");
            assertThat(DocValuesQueries.prefix(field(index, "bin"), bytes(0xff)).toString())
                    .isEqualTo("bin:[[ff] TO *}");
            assertThat(DocValuesQueries.prefix(field(index, "bin"), text("")).toString())
                    .isEqualTo("bin:[* TO *]");
            assertThatThrownBy(() -> DocValuesQueries.prefix(field(index, "n"), text("1")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("the field 'n' has NUMERIC doc values, which hold numbers: say how they are encoded");
        }
    }

    @Test
    void theSuccessorOfAPrefixIsTheLeastBytesAboveItsValues() {
        assertThat(DocValuesQueries.successor(bytes(0x61, 0x62))).hasValue(bytes(0x61, 0x63));
        assertThat(DocValuesQueries.successor(bytes(0x7f, 0xff, 0xff))).hasValue(bytes(0x80));
        assertThat(DocValuesQueries.successor(bytes(0, 0xfe))).hasValue(bytes(0, 0xff));
        assertThat(DocValuesQueries.successor(bytes(0xff, 0xff))).isEmpty();
        assertThat(DocValuesQueries.successor(new BytesRef())).isEmpty();
        // Only the prefix's own bytes count, not the rest of its array.
        assertThat(DocValuesQueries.successor(new BytesRef(new byte[] {9, 0x10, (byte) 0xff, 7}, 1, 2)))
                .hasValue(bytes(0x11));
    }

    @Test
    void searchesAndExplainsADocValuesQuery() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            Query query = DocValuesQueries.bytes(field(index, "bin"), range(bytes(0x80), null, true, true));
            SearchResults results =
                    index.search(query, BM25, Optional.empty(), 0, 10, true, Optional.empty(), ScanProgress.NONE);

            assertThat(results.hits()).extracting(SearchResults.Hit::docId).containsExactly(1, 2);
            assertThat(index.explain(query, BM25, 2, ScanProgress.NONE).isMatch())
                    .isTrue();
            assertThat(index.explain(query, BM25, 0, ScanProgress.NONE).isMatch())
                    .isFalse();
        }
    }

    @Test
    void cancellingStopsAScanOfBinaryValues() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            AtomicInteger calls = new AtomicInteger();
            ScanProgress cancelled = _ -> {
                if (calls.incrementAndGet() > 1) {
                    throw new CancellationException("cancelled");
                }
            };
            Query query = DocValuesQueries.bytes(field(index, "bin"), range(null, null, true, true));

            assertThatThrownBy(() -> index.matches(query, true, cancelled)).isInstanceOf(CancellationException.class);
        }
    }

    @Test
    void refusesFieldsAndValuesThatDontFit() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            assertThatThrownBy(() -> DocValuesQueries.numbers(field(index, "s"), DocValuesNumber.LONG, values("1")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("the field 's' has SORTED doc values, which hold bytes, not numbers");
            assertThatThrownBy(() -> DocValuesQueries.bytes(field(index, "n"), values(text("1"))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("the field 'n' has NUMERIC doc values, which hold numbers: say how they are encoded");
            assertThatThrownBy(() -> DocValuesQueries.bytes(field(index, "id"), values(text("1"))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("the field 'id' has no doc values");
            assertThatThrownBy(() -> DocValuesQueries.numbers(field(index, "n"), DocValuesNumber.LONG, values("1.5")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("'1.5' is not a LONG");
            assertThatThrownBy(() -> DocValuesQueries.numbers(
                            field(index, "d"), DocValuesNumber.DOUBLE, range("NaN", null, true, true)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("NaN can't be searched for");
            assertThatThrownBy(() -> DocValuesQueries.numbers(field(index, "f"), DocValuesNumber.FLOAT, values("x")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("'x' is not a FLOAT");
            assertThatThrownBy(() -> new DocValuesMatch.Values<String>(List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static List<Integer> numbers(
            ReadOnlyIndex index, String field, DocValuesNumber number, DocValuesMatch<String> match)
            throws IOException, InvalidQueryException {
        return matchIds(
                index.matches(DocValuesQueries.numbers(field(index, field), number, match), false, ScanProgress.NONE));
    }

    private static List<Integer> bytes(ReadOnlyIndex index, String field, DocValuesMatch<BytesRef> match)
            throws IOException, InvalidQueryException {
        return matchIds(index.matches(DocValuesQueries.bytes(field(index, field), match), false, ScanProgress.NONE));
    }

    private static List<Integer> prefix(ReadOnlyIndex index, String field, BytesRef prefix)
            throws IOException, InvalidQueryException {
        return matchIds(index.matches(DocValuesQueries.prefix(field(index, field), prefix), false, ScanProgress.NONE));
    }

    private static FieldInfo field(ReadOnlyIndex index, String name) {
        return index.fieldInfo(name).orElseThrow();
    }

    private static DocValuesMatch<String> values(String... values) {
        return new DocValuesMatch.Values<>(List.of(values));
    }

    private static DocValuesMatch<BytesRef> values(BytesRef... values) {
        return new DocValuesMatch.Values<>(List.of(values));
    }

    private static <T> DocValuesMatch<T> range(T lower, T upper, boolean includeLower, boolean includeUpper) {
        return new DocValuesMatch.Range<>(
                Optional.ofNullable(lower), Optional.ofNullable(upper), includeLower, includeUpper);
    }

    private static BytesRef text(String text) {
        return new BytesRef(text);
    }

    private static BytesRef bytes(int... bytes) {
        byte[] array = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            array[i] = (byte) bytes[i];
        }
        return new BytesRef(array);
    }

    private static List<Integer> matchIds(DocFilter filter) {
        List<Integer> ids = new ArrayList<>();
        for (var match = filter.step(0, true);
                match.isPresent();
                match = filter.step(match.get().docId() + 1, true)) {
            ids.add(match.get().docId());
        }
        return ids;
    }

    /**
     * Five documents in two segments, the third of which has no doc values and the fifth is deleted:
     *
     * <pre>
     *     n        d     f     sd    sf     sn       s       ss      bin
     * 0   5        -2.5  1.5   0.5   -0.25  {1, 10}  apple   {a, b}  7f
     * 1   MAX      0.0   -0.0  -1.0  3.0    {3}      banana  {c}     80
     * 2   -3       1.0   -7.0  2.5          {-5}     cherry          80 00
     * 3
     * 4   5                                                          7f   (deleted)
     * </pre>
     */
    private Path writeIndex() throws IOException {
        Path dir = tmp.resolve("index");
        try (FSDirectory directory = FSDirectory.open(dir);
                IndexWriter writer =
                        new IndexWriter(directory, new IndexWriterConfig().setMergePolicy(NoMergePolicy.INSTANCE))) {
            Document first = doc("0", 5, -2.5, 1.5f, "apple", bytes(0x7f));
            first.add(new SortedNumericDocValuesField("sd", NumericUtils.doubleToSortableLong(0.5)));
            first.add(new SortedNumericDocValuesField("sf", NumericUtils.floatToSortableInt(-0.25f)));
            first.add(new SortedNumericDocValuesField("sn", 1));
            first.add(new SortedNumericDocValuesField("sn", 10));
            first.add(new SortedSetDocValuesField("ss", new BytesRef("a")));
            first.add(new SortedSetDocValuesField("ss", new BytesRef("b")));
            writer.addDocument(first);
            Document second = doc("1", Long.MAX_VALUE, 0.0, -0.0f, "banana", bytes(0x80));
            second.add(new SortedNumericDocValuesField("sd", NumericUtils.doubleToSortableLong(-1.0)));
            second.add(new SortedNumericDocValuesField("sf", NumericUtils.floatToSortableInt(3.0f)));
            second.add(new SortedNumericDocValuesField("sn", 3));
            second.add(new SortedSetDocValuesField("ss", new BytesRef("c")));
            writer.addDocument(second);
            writer.commit();
            Document third = doc("2", -3, 1.0, -7.0f, "cherry", bytes(0x80, 0));
            third.add(new SortedNumericDocValuesField("sd", NumericUtils.doubleToSortableLong(2.5)));
            third.add(new SortedNumericDocValuesField("sn", -5));
            writer.addDocument(third);
            Document empty = new Document();
            empty.add(new StringField("id", "3", Field.Store.YES));
            writer.addDocument(empty);
            Document deleted = new Document();
            deleted.add(new StringField("id", "4", Field.Store.YES));
            deleted.add(new NumericDocValuesField("n", 5));
            deleted.add(new BinaryDocValuesField("bin", bytes(0x7f)));
            writer.addDocument(deleted);
            writer.deleteDocuments(new Term("id", "4"));
            writer.commit();
        }
        return dir;
    }

    private static Document doc(String id, long n, double d, float f, String s, BytesRef bin) {
        Document doc = new Document();
        doc.add(new StringField("id", id, Field.Store.YES));
        doc.add(new NumericDocValuesField("n", n));
        doc.add(new DoubleDocValuesField("d", d));
        doc.add(new FloatDocValuesField("f", f));
        doc.add(new SortedDocValuesField("s", new BytesRef(s)));
        doc.add(new BinaryDocValuesField("bin", bin));
        return doc;
    }
}
