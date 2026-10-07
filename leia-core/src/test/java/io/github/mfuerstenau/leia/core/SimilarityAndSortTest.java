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

    @TempDir
    Path tmp;

    @Test
    void sortsByNumericDocValuesWithTheScores() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            Map<Integer, Float> scores =
                    search(index, BM25, Optional.empty(), 0).stream().collect(Collectors.toMap(Hit::docId, Hit::score));

            List<Hit> hits = search(index, BM25, sort(index, "n", SortType.LONG, false), 0);

            // Document 2 has no value and sorts as 0, Lucene's default.
            assertThat(hits).extracting(Hit::docId).containsExactly(1, 2, 0, 3);
            assertThat(hits)
                    .extracting(Hit::sortValues)
                    .containsExactly(
                            List.of(number(-2L)), List.of(number(0L)), List.of(number(5L)), List.of(number(10L)));
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
                            List.of(new SortValue(Optional.empty(), Optional.empty())),
                            List.of(term("apple")),
                            List.of(term("banana")),
                            List.of(term("cherry")));

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
            Query query =
                    Queries.classic("fox dog", "body", ParserOptions.defaults(Operator.OR), ClassicOptions.DEFAULTS);

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
