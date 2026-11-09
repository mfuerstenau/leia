package io.github.mfuerstenau.leia.lucene9;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mfuerstenau.leia.runtime.DirectoryKind;
import io.github.mfuerstenau.leia.runtime.RuntimeIndex;
import io.github.mfuerstenau.leia.runtime.Scoring;
import io.github.mfuerstenau.leia.runtime.Scoring.SortMissing;
import io.github.mfuerstenau.leia.runtime.Scoring.SortSelector;
import io.github.mfuerstenau.leia.runtime.Scoring.SortType;
import io.github.mfuerstenau.leia.runtime.Search;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The sort keys on the doc values of the {@code lucene8} index that {@link Lucene9IndexTest} reads: the
 * ways a key can be refused, and the key types, the placement of missing values and the selectors that
 * it accepts. {@code dv_num} has no value in two live documents, the other doc values fields in none.
 */
class Lucene9SortsTest {

    private static final Search.ParserOptions BODY = new Search.ParserOptions("body", false, false, 0, 0, true, false);
    private static final Scoring.Similarity BM25 = new Scoring.Similarity.Bm25(1.2f, 0.75f);

    private static RuntimeIndex openLucene8() throws IOException {
        String dir = System.getProperty("leia.test.index.lucene8");
        return new Lucene9Runtime().open(List.of(Path.of(dir).toAbsolutePath()), DirectoryKind.FS);
    }

    private static Search.SearchResults sorted(RuntimeIndex index, Scoring.SortKey key) throws Exception {
        Search.ParsedQuery alpha =
                index.search().orElseThrow().parse("alpha", BODY, new Search.AnalyzerSpec.Preset("StandardAnalyzer"));
        return index.scoring().orElseThrow().search(alpha, BM25, List.of(key), 0, 10, List.of("id"), _ -> {});
    }

    private static Scoring.SortKey key(
            String field, SortType type, boolean reverse, SortSelector selector, SortMissing missing) {
        return new Scoring.SortKey.Field(field, type, reverse, selector, missing);
    }

    private static List<Integer> docIds(Search.SearchResults results) {
        return results.hits().stream().map(Search.Hit::docId).toList();
    }

    private static List<String> terms(Search.SearchResults results) {
        return results.hits().stream()
                .map(hit -> new String(
                        Optional.ofNullable(hit.sortValues().getFirst().term()).orElseThrow(), StandardCharsets.UTF_8))
                .toList();
    }

    @Test
    void refusesAFieldThatIsNotInTheIndex() throws Exception {
        try (RuntimeIndex index = openLucene8()) {
            assertThatThrownBy(() ->
                            sorted(index, key("nope", SortType.LONG, false, SortSelector.MIN, SortMissing.DEFAULT)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("No field 'nope' in the index");
        }
    }

    @Test
    void refusesAFieldWithoutDocValues() throws Exception {
        try (RuntimeIndex index = openLucene8()) {
            assertThatThrownBy(() ->
                            sorted(index, key("body", SortType.STRING, false, SortSelector.MIN, SortMissing.DEFAULT)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("the field 'body' has no doc values to sort by");
        }
    }

    @Test
    void refusesATypeThatTheDocValuesDontFit() throws Exception {
        try (RuntimeIndex index = openLucene8()) {
            assertThatThrownBy(() -> sorted(
                            index, key("dv_sorted", SortType.LONG, false, SortSelector.MIN, SortMissing.DEFAULT)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("the field 'dv_sorted' has SORTED doc values, which can't be sorted by as LONG");
            assertThatThrownBy(() ->
                            sorted(index, key("dv_num", SortType.STRING, false, SortSelector.MIN, SortMissing.DEFAULT)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("the field 'dv_num' has NUMERIC doc values, which can't be sorted by as STRING");
            assertThatThrownBy(() ->
                            sorted(index, key("dv_set", SortType.LONG, false, SortSelector.MIN, SortMissing.DEFAULT)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("the field 'dv_set' has SORTED_SET doc values, which can't be sorted by as LONG");
        }
    }

    @Test
    void refusesASelectorThatTheDocValuesCantMake() throws Exception {
        try (RuntimeIndex index = openLucene8()) {
            assertThatThrownBy(() -> sorted(
                            index, key("dv_sorted", SortType.STRING, false, SortSelector.MAX, SortMissing.DEFAULT)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("the field 'dv_sorted' has single-valued SORTED doc values, which can't select MAX");
            assertThatThrownBy(() -> sorted(
                            index,
                            key("dv_sorted_num", SortType.LONG, false, SortSelector.MIDDLE_MIN, SortMissing.DEFAULT)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("the field 'dv_sorted_num' has multi-valued numeric SORTED_NUMERIC doc values, "
                            + "which can't select MIDDLE_MIN");
        }
    }

    /** The long values of {@code dv_num} are read as the bits of a float or a double, which keeps their order. */
    @ParameterizedTest
    @EnumSource(
            value = SortType.class,
            names = {"INT", "LONG", "FLOAT", "DOUBLE"})
    void sortsANumericFieldAsEveryNumberType(SortType type) throws Exception {
        try (RuntimeIndex index = openLucene8()) {
            Search.SearchResults first = sorted(index, key("dv_num", type, false, SortSelector.MIN, SortMissing.FIRST));
            assertThat(docIds(first)).containsExactly(2, 4, 0, 5, 6, 7, 3);
            Search.SearchResults last = sorted(index, key("dv_num", type, false, SortSelector.MIN, SortMissing.LAST));
            assertThat(docIds(last)).containsExactly(0, 5, 6, 7, 3, 2, 4);
            // Reversed, a missing value that is placed first in the order shown comes first still.
            Search.SearchResults reversedFirst =
                    sorted(index, key("dv_num", type, true, SortSelector.MIN, SortMissing.FIRST));
            assertThat(docIds(reversedFirst)).containsExactly(2, 4, 3, 7, 6, 5, 0);
        }
    }

    /** Every document has a value of {@code dv_sorted}, so where the missing ones go changes nothing. */
    @ParameterizedTest
    @EnumSource(SortMissing.class)
    void placesMissingValuesOfAStringSort(SortMissing missing) throws Exception {
        try (RuntimeIndex index = openLucene8()) {
            Search.SearchResults results =
                    sorted(index, key("dv_sorted", SortType.STRING, false, SortSelector.MIN, missing));
            assertThat(terms(results)).containsExactly("s0", "s0", "s0", "s1", "s1", "s1", "s2");
        }
    }

    @Test
    void sortsASortedSetBySelector() throws Exception {
        try (RuntimeIndex index = openLucene8()) {
            // Every document has the terms x and y<i>.
            assertThat(terms(sorted(index, key("dv_set", SortType.STRING, false, SortSelector.MIN, SortMissing.LAST))))
                    .containsOnly("x");
            assertThat(terms(sorted(
                            index, key("dv_set", SortType.STRING, false, SortSelector.MIDDLE_MIN, SortMissing.FIRST))))
                    .containsOnly("x");
            Search.SearchResults max =
                    sorted(index, key("dv_set", SortType.STRING, false, SortSelector.MAX, SortMissing.DEFAULT));
            assertThat(docIds(max)).containsExactly(0, 2, 4, 3, 5, 6, 7);
            assertThat(terms(max)).containsExactly("y1", "y3", "y3", "y4", "y5", "y6", "y7");
        }
    }
}
