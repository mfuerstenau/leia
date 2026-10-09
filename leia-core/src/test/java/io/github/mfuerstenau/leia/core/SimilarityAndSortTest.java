package io.github.mfuerstenau.leia.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mfuerstenau.leia.core.Queries.Operator;
import io.github.mfuerstenau.leia.core.SearchResults.Hit;
import io.github.mfuerstenau.leia.core.SearchResults.SortValue;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.BinaryDocValuesField;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.DoubleDocValuesField;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.SortedNumericDocValuesField;
import org.apache.lucene.document.SortedSetDocValuesField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.search.Explanation;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.similarities.Similarity;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SimilarityAndSortTest {

    private static final Similarity BM25 = Similarities.bm25(1.2f, 0.75f);

    /** Lucene's analyzers are thread-safe; this one lives as long as the tests. */
    private static final Analyzer ANALYZER = new StandardAnalyzer();

    @TempDir
    Path tmp;

    @Test
    void sortsByNumericDocValuesWithTheScores() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            Map<Integer, Float> scores =
                    search(index, BM25, Optional.empty(), 0).stream().collect(Collectors.toMap(Hit::docId, Hit::score));

            List<Hit> hits = search(index, BM25, sort(index, "n", SortType.LONG, false), 0);

            // Document 2 has no value and sorts as 0, Lucene's default, but shows no value.
            assertThat(hits).extracting(Hit::docId).containsExactly(1, 2, 0, 3);
            assertThat(hits)
                    .extracting(Hit::sortValues)
                    .containsExactly(List.of(number(-2L)), List.of(none()), List.of(number(5L)), List.of(number(10L)));
            // Sorted hits have the scores that the same search by score gives them.
            assertThat(hits).allSatisfy(hit -> assertThat(hit.score()).isEqualTo(scores.get(hit.docId())));
            assertThat(search(index, BM25, sort(index, "n", SortType.LONG, true), 0))
                    .extracting(Hit::docId)
                    .containsExactly(3, 0, 2, 1);
            assertThat(search(index, BM25, Optional.empty(), 0))
                    .allSatisfy(hit -> assertThat(hit.sortValues()).isEmpty());
        }
    }

    @Test
    void readsNumbersAsTheTypeSays() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            // Raw double bits: only DOUBLE orders the negative values right.
            List<Hit> doubles = search(index, BM25, sort(index, "d", SortType.DOUBLE, false), 0);
            assertThat(doubles).extracting(Hit::docId).containsExactly(3, 0, 2, 1);
            assertThat(doubles.getFirst().sortValues()).containsExactly(number(-3.0));

            // A sorted numeric field sorts by its smallest value.
            List<Hit> smallest = search(index, BM25, sort(index, "sn", SortType.INT, false), 0);
            assertThat(smallest).extracting(Hit::docId).containsExactly(3, 2, 1, 0);
            assertThat(smallest.getFirst().sortValues()).containsExactly(number(-4));
        }
    }

    @Test
    void sortsWhateverTypeThePointsOfTheFieldHave() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            // n has 8-byte points; Lucene's pruning would refuse to read it as INT.
            List<Hit> ints = index.search(
                            query(),
                            BM25,
                            sort(index, "n", SortType.INT, false),
                            0,
                            2,
                            false,
                            Optional.empty(),
                            ScanProgress.NONE)
                    .hits();

            assertThat(ints).extracting(Hit::docId).containsExactly(1, 2);
            assertThat(ints.getFirst().sortValues()).containsExactly(number(-2));
        }
    }

    @Test
    void sortsByTermsWithMissingValuesFirst() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            List<Hit> sorted = search(index, BM25, sort(index, "s", SortType.STRING, false), 0);
            assertThat(sorted).extracting(Hit::docId).containsExactly(2, 1, 0, 3);
            assertThat(sorted)
                    .extracting(Hit::sortValues)
                    .containsExactly(
                            List.of(none()), List.of(term("apple")), List.of(term("banana")), List.of(term("cherry")));

            // A sorted set sorts by its smallest term.
            List<Hit> sets = search(index, BM25, sort(index, "ss", SortType.STRING, false), 0);
            assertThat(sets).extracting(Hit::docId).containsExactly(2, 3, 0, 1);
            assertThat(sets.get(1).sortValues()).containsExactly(term("a"));
        }
    }

    @Test
    void breaksTiesWithTheSecondSortFieldAndPages() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            FieldTypes fields = new FieldTypes(index);
            Optional<Sort> sort = Optional.of(new Sort(
                    SortType.STRING.sortField(fields.get("group"), false),
                    SortType.LONG.sortField(fields.get("n"), true)));

            List<Hit> hits = search(index, BM25, sort, 0);

            assertThat(hits).extracting(Hit::docId).containsExactly(0, 2, 3, 1);
            assertThat(hits.getFirst().sortValues()).containsExactly(term("a"), number(5L));
            SearchResults page = index.search(query(), BM25, sort, 1, 2, true, Optional.empty(), ScanProgress.NONE);
            assertThat(page.hits()).extracting(Hit::docId).containsExactly(2, 3);
        }
    }

    @Test
    void selectsTheValueOfMultiValuedFields() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            // sn: 0 {3, 9}, 1 {1}, 2 none (as 0), 3 {-4, 20}.
            List<Hit> largest = search(index, BM25, sort(index, "sn", SortType.LONG, false, SortSelector.MAX), 0);
            assertThat(largest).extracting(Hit::docId).containsExactly(2, 1, 0, 3);
            assertThat(largest)
                    .extracting(Hit::sortValues)
                    .containsExactly(List.of(none()), List.of(number(1L)), List.of(number(9L)), List.of(number(20L)));

            // ss: 0 {b, x}, 1 {c}, 2 none (first), 3 {a, z}.
            assertThat(search(index, BM25, sort(index, "ss", SortType.STRING, false, SortSelector.MAX), 0))
                    .extracting(Hit::docId)
                    .containsExactly(2, 1, 0, 3);
            List<Hit> middleMin =
                    search(index, BM25, sort(index, "ss", SortType.STRING, false, SortSelector.MIDDLE_MIN), 0);
            assertThat(middleMin).extracting(Hit::docId).containsExactly(2, 3, 0, 1);
            assertThat(middleMin.get(1).sortValues()).containsExactly(term("a"));
            List<Hit> middleMax =
                    search(index, BM25, sort(index, "ss", SortType.STRING, false, SortSelector.MIDDLE_MAX), 0);
            assertThat(middleMax).extracting(Hit::docId).containsExactly(2, 1, 0, 3);
            assertThat(middleMax.get(2).sortValues()).containsExactly(term("x"));
        }
    }

    @Test
    void putsMissingValuesFirstOrLastInTheOrderShown() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            // n: 0 → 5, 1 → -2, 2 none, 3 → 10; s: 0 banana, 1 apple, 2 none, 3 cherry.
            assertThat(docIds(index, "n", SortType.LONG, false, SortMissing.LAST))
                    .containsExactly(1, 0, 3, 2);
            assertThat(docIds(index, "n", SortType.LONG, true, SortMissing.LAST))
                    .containsExactly(3, 0, 1, 2);
            assertThat(docIds(index, "n", SortType.INT, false, SortMissing.FIRST))
                    .containsExactly(2, 1, 0, 3);
            assertThat(docIds(index, "n", SortType.LONG, true, SortMissing.FIRST))
                    .containsExactly(2, 3, 0, 1);
            assertThat(docIds(index, "d", SortType.DOUBLE, true, SortMissing.LAST))
                    .containsExactly(1, 0, 3, 2);
            assertThat(docIds(index, "sn", SortType.FLOAT, true, SortMissing.FIRST))
                    .containsExactly(2, 0, 1, 3);
            assertThat(docIds(index, "s", SortType.STRING, false, SortMissing.LAST))
                    .containsExactly(1, 0, 3, 2);
            assertThat(docIds(index, "s", SortType.STRING, true, SortMissing.LAST))
                    .containsExactly(3, 0, 1, 2);
            assertThat(docIds(index, "s", SortType.STRING, true, SortMissing.FIRST))
                    .containsExactly(2, 3, 0, 1);
            assertThat(docIds(index, "ss", SortType.STRING, true, SortMissing.DEFAULT))
                    .containsExactly(1, 0, 3, 2);
            // A missing value shows as none, whatever Lucene sorted it as.
            assertThat(search(index, BM25, sort(index, "n", SortType.LONG, true, SortMissing.LAST), 0)
                            .getLast()
                            .sortValues())
                    .containsExactly(none());
        }
    }

    @Test
    void sortsByScoreAndDocumentId() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            List<Hit> lowestFirst = search(index, BM25, Optional.of(new Sort(SortType.score(true))), 0);
            assertThat(lowestFirst).extracting(Hit::score).isSorted();
            assertThat(lowestFirst.getFirst().score())
                    .isLessThan(lowestFirst.getLast().score());
            assertThat(lowestFirst)
                    .allSatisfy(hit -> assertThat(hit.sortValues()).containsExactly(number(hit.score())));

            List<Hit> highestId = search(index, BM25, Optional.of(new Sort(SortType.doc(true))), 0);
            assertThat(highestId).extracting(Hit::docId).containsExactly(3, 2, 1, 0);
            assertThat(highestId.getFirst().sortValues()).containsExactly(number(3));

            // The group, then the score, best first: the shorter "a fox" beats "the quick fox".
            FieldTypes fields = new FieldTypes(index);
            List<Hit> grouped = search(
                    index,
                    BM25,
                    Optional.of(new Sort(SortType.STRING.sortField(fields.get("group"), false), SortType.score(false))),
                    0);
            assertThat(grouped).extracting(Hit::docId).containsExactly(2, 0, 1, 3);
        }
    }

    @Test
    void rejectsSelectorsThatTheDocValuesDontHave() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            FieldTypes fields = new FieldTypes(index);

            assertThatThrownBy(() ->
                            SortType.LONG.sortField(fields.get("n"), false, SortSelector.MAX, SortMissing.DEFAULT))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("the field 'n' has single-valued NUMERIC doc values, which can't select MAX");
            assertThatThrownBy(() -> SortType.STRING.sortField(
                            fields.get("s"), false, SortSelector.MIDDLE_MIN, SortMissing.DEFAULT))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("the field 's' has single-valued SORTED doc values, which can't select MIDDLE_MIN");
            assertThatThrownBy(() -> SortType.LONG.sortField(
                            fields.get("sn"), false, SortSelector.MIDDLE_MAX, SortMissing.DEFAULT))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage(
                            "the field 'sn' has multi-valued numeric SORTED_NUMERIC doc values, which can't select MIDDLE_MAX");
        }
    }

    @Test
    void rejectsFieldsThatCantSortAsTheType() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            FieldTypes fields = new FieldTypes(index);

            assertThatThrownBy(() -> SortType.LONG.sortField(fields.get("body"), false))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("the field 'body' has no doc values to sort by");
            assertThatThrownBy(() -> SortType.STRING.sortField(fields.get("bin"), false))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("the field 'bin' has BINARY doc values, which can't be sorted by");
            assertThatThrownBy(() -> SortType.STRING.sortField(fields.get("n"), false))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("the field 'n' has NUMERIC doc values, which can't be sorted by as STRING");
            assertThatThrownBy(() -> SortType.DOUBLE.sortField(fields.get("ss"), false))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("the field 'ss' has SORTED_SET doc values, which can't be sorted by as DOUBLE");
        }
    }

    @Test
    void scoresWithTheSimilarityAndExplainsTheSameScore() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            Query query = Queries.classic(
                    "fox dog", "body", ParserOptions.defaults(Operator.OR), ClassicOptions.DEFAULTS, ANALYZER);

            // Boolean: each matching term scores its boost, 1.
            List<Hit> bool = search(index, Similarities.bool(), query);
            assertThat(bool).extracting(Hit::docId).containsExactly(3, 0, 1, 2);
            assertThat(bool).extracting(Hit::score).containsExactly(2f, 1f, 1f, 1f);

            // TF-IDF rewards the two foxes of document 1 more than BM25 does.
            Similarity classic = Similarities.classic();
            List<Hit> tfIdf = search(index, classic, query);
            List<Hit> bm25 = search(index, BM25, query);
            assertThat(tfIdf)
                    .extracting(Hit::score)
                    .doesNotContainAnyElementsOf(bm25.stream().map(Hit::score).toList());
            Hit first = tfIdf.getFirst();
            Explanation explanation = index.explain(query, classic, first.docId(), ScanProgress.NONE);
            assertThat(explanation.getValue().floatValue()).isEqualTo(first.score());
            assertThat(index.explain(query, BM25, first.docId(), ScanProgress.NONE)
                            .getValue()
                            .floatValue())
                    .isNotEqualTo(first.score());
        }
    }

    @Test
    void rejectsBm25ParametersOutOfRange() {
        assertThatThrownBy(() -> Similarities.bm25(-1f, 0.75f)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Similarities.bm25(Float.POSITIVE_INFINITY, 0.75f))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Similarities.bm25(1.2f, 1.5f)).isInstanceOf(IllegalArgumentException.class);
        assertThat(Similarities.bm25(0f, 0f)).isNotNull();
    }

    private static Query query() {
        return Queries.term("body", new BytesRef("fox"));
    }

    private static List<Hit> search(ReadOnlyIndex index, Similarity similarity, Optional<Sort> sort, int offset)
            throws IOException, InvalidQueryException {
        return index.search(query(), similarity, sort, offset, 10, true, Optional.empty(), ScanProgress.NONE)
                .hits();
    }

    private static List<Hit> search(ReadOnlyIndex index, Similarity similarity, Query query)
            throws IOException, InvalidQueryException {
        return index.search(query, similarity, Optional.empty(), 0, 10, true, Optional.empty(), ScanProgress.NONE)
                .hits();
    }

    private static Optional<Sort> sort(ReadOnlyIndex index, String field, SortType type, boolean reverse) {
        SortField sortField = type.sortField(new FieldTypes(index).get(field), reverse);
        return Optional.of(new Sort(sortField));
    }

    private static Optional<Sort> sort(
            ReadOnlyIndex index, String field, SortType type, boolean reverse, SortSelector selector) {
        return Optional.of(
                new Sort(type.sortField(new FieldTypes(index).get(field), reverse, selector, SortMissing.DEFAULT)));
    }

    private static Optional<Sort> sort(
            ReadOnlyIndex index, String field, SortType type, boolean reverse, SortMissing missing) {
        return Optional.of(
                new Sort(type.sortField(new FieldTypes(index).get(field), reverse, SortSelector.MIN, missing)));
    }

    private static List<Integer> docIds(
            ReadOnlyIndex index, String field, SortType type, boolean reverse, SortMissing missing)
            throws IOException, InvalidQueryException {
        return search(index, BM25, sort(index, field, type, reverse, missing), 0).stream()
                .map(Hit::docId)
                .toList();
    }

    private static SortValue none() {
        return new SortValue(Optional.empty(), Optional.empty());
    }

    private static SortValue number(Number number) {
        return new SortValue(Optional.of(number), Optional.empty());
    }

    private static SortValue term(String term) {
        return new SortValue(Optional.empty(), Optional.of(new BytesRef(term)));
    }

    private record FieldTypes(ReadOnlyIndex index) {
        org.apache.lucene.index.FieldInfo get(String field) {
            return index.fieldInfo(field).orElseThrow();
        }
    }

    /**
     * Four documents with "fox" in two segments, each with doc values in {@code n} (numeric), {@code
     * sn} (sorted numeric), {@code d} (raw double bits), {@code s} (sorted), {@code ss} (sorted set)
     * and {@code group} (sorted), except document 2, which has only its group; and binary doc values
     * in {@code bin}.
     */
    private Path writeIndex() throws IOException {
        Path dir = tmp.resolve("index");
        try (FSDirectory directory = FSDirectory.open(dir);
                IndexWriter writer =
                        new IndexWriter(directory, new IndexWriterConfig().setMergePolicy(NoMergePolicy.INSTANCE))) {
            writer.addDocument(doc("the quick fox", "a", 5, -1.5, "banana", new long[] {3, 9}, "b", "x"));
            writer.addDocument(doc("fox fox", "b", -2, 2.25, "apple", new long[] {1}, "c"));
            writer.commit();
            Document missing = new Document();
            missing.add(new TextField("body", "a fox", Field.Store.NO));
            missing.add(new SortedDocValuesField("group", new BytesRef("a")));
            writer.addDocument(missing);
            writer.addDocument(doc("fox and dog", "b", 10, -3.0, "cherry", new long[] {-4, 20}, "a", "z"));
        }
        return dir;
    }

    private static Document doc(String body, String group, long n, double d, String s, long[] sn, String... ss) {
        Document doc = new Document();
        doc.add(new TextField("body", body, Field.Store.NO));
        doc.add(new SortedDocValuesField("group", new BytesRef(group)));
        doc.add(new NumericDocValuesField("n", n));
        doc.add(new LongPoint("n", n));
        doc.add(new DoubleDocValuesField("d", d));
        doc.add(new SortedDocValuesField("s", new BytesRef(s)));
        Arrays.stream(sn).forEach(value -> doc.add(new SortedNumericDocValuesField("sn", value)));
        Arrays.stream(ss).forEach(value -> doc.add(new SortedSetDocValuesField("ss", new BytesRef(value))));
        doc.add(new BinaryDocValuesField("bin", new BytesRef(s)));
        return doc;
    }
}
