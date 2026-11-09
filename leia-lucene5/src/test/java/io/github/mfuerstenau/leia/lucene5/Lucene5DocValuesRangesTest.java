package io.github.mfuerstenau.leia.lucene5;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mfuerstenau.leia.runtime.DirectoryKind;
import io.github.mfuerstenau.leia.runtime.DocValuesSearch;
import io.github.mfuerstenau.leia.runtime.DocValuesSearch.Match;
import io.github.mfuerstenau.leia.runtime.DocValuesSearch.NumberEncoding;
import io.github.mfuerstenau.leia.runtime.RuntimeIndex;
import io.github.mfuerstenau.leia.runtime.Search;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.MultiFields;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The edges of the doc values queries on the {@code lucene5} index of {@link Lucene5IndexTest}: bounds
 * that exclude their value or lie at the end of the numbers, every encoding of floating point numbers,
 * and the ranges, sets and prefixes of bytes. {@code dv_num} is {@code i} in documents 0 and 4 to 6 (and
 * 400 in document 3, none in 2), {@code dv_bin} {@code b<i>}, {@code dv_set} {@code x} and
 * {@code y<i>}, {@code dv_sorted} {@code s<i % 3>}.
 */
class Lucene5DocValuesRangesTest {

    private static Path lucene5() {
        return Path.of(System.getProperty("leia.test.index.lucene5")).toAbsolutePath();
    }

    private static RuntimeIndex open() throws IOException {
        return new Lucene5Runtime().open(List.of(lucene5()), DirectoryKind.FS);
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static List<Integer> matched(RuntimeIndex index, Search.ParsedQuery query) throws IOException {
        return index.search().orElseThrow().matches(query, false, _ -> {}).docs().stream()
                .boxed()
                .toList();
    }

    private static Match<String> numbers(String lower, String upper, boolean includeLower, boolean includeUpper) {
        return new Match.Range<>(lower, upper, includeLower, includeUpper);
    }

    private static Match<byte[]> bytes(String lower, String upper, boolean includeLower, boolean includeUpper) {
        return new Match.Range<>(
                lower == null ? null : utf8(lower), upper == null ? null : utf8(upper), includeLower, includeUpper);
    }

    @Test
    void leavesOutTheBoundsOfALongRangeThatAreExclusive() throws Exception {
        try (RuntimeIndex index = open()) {
            DocValuesSearch docValues = index.docValuesSearch().orElseThrow();
            NumberEncoding longs = NumberEncoding.LONG;
            assertThat(matched(index, docValues.numbers("dv_num", longs, numbers("5", "7", true, false))))
                    .containsExactly(4, 5);
            assertThat(matched(index, docValues.numbers("dv_num", longs, numbers("5", "7", false, false))))
                    .containsExactly(5);
            // Nothing lies above the greatest or below the least long.
            assertThat(matched(
                            index,
                            docValues.numbers(
                                    "dv_num", longs, numbers(Long.toString(Long.MAX_VALUE), null, false, true))))
                    .isEmpty();
            assertThat(matched(
                            index,
                            docValues.numbers(
                                    "dv_num", longs, numbers(null, Long.toString(Long.MIN_VALUE), true, false))))
                    .isEmpty();
        }
    }

    /** For positive numbers, every encoding orders as the numbers do, and all of 1 to 400 are tiny. */
    @ParameterizedTest
    @EnumSource(
            value = NumberEncoding.class,
            names = {"DOUBLE", "FLOAT", "SORTABLE_DOUBLE", "SORTABLE_FLOAT"})
    void searchesRangesOfEveryFloatingPointEncoding(NumberEncoding encoding) throws Exception {
        try (RuntimeIndex index = open()) {
            DocValuesSearch docValues = index.docValuesSearch().orElseThrow();
            assertThat(matched(index, docValues.numbers("dv_num", encoding, numbers("0", "1", true, true))))
                    .containsExactly(0, 3, 4, 5, 6);
            // The bounds exclude themselves: the next number above 0 and below 1 still hold every value.
            assertThat(matched(index, docValues.numbers("dv_num", encoding, numbers("0", "1", false, false))))
                    .containsExactly(0, 3, 4, 5, 6);
            assertThat(matched(index, docValues.numbers("dv_num", encoding, numbers("1", "0", true, true))))
                    .isEmpty();
            assertThat(matched(index, docValues.numbers("dv_num", encoding, numbers("Infinity", null, false, true))))
                    .isEmpty();
            assertThat(matched(index, docValues.numbers("dv_num", encoding, numbers(null, "-Infinity", true, false))))
                    .isEmpty();
            assertThat(matched(index, docValues.numbers("dv_num", encoding, numbers(null, null, true, true))))
                    .containsExactly(0, 3, 4, 5, 6);
        }
    }

    /** {@code dv_sorted_num} has {@code -i} and {@code i}: negative longs, and in raw bits negative NaNs. */
    @ParameterizedTest
    @EnumSource(
            value = NumberEncoding.class,
            names = {"DOUBLE", "FLOAT"})
    void splitsARawRangeAtZero(NumberEncoding encoding) throws Exception {
        try (RuntimeIndex index = open()) {
            DocValuesSearch docValues = index.docValuesSearch().orElseThrow();
            assertThat(matched(index, docValues.numbers("dv_sorted_num", encoding, numbers("-1", "1", true, true))))
                    .contains(4, 5, 6);
            assertThat(matched(index, docValues.numbers("dv_sorted_num", encoding, numbers("-1", null, true, true))))
                    .contains(4, 5, 6);
            // Only the bits below -1.0 are left, which are the bits of the negative longs.
            assertThat(matched(index, docValues.numbers("dv_sorted_num", encoding, numbers(null, "-1", true, true))))
                    .contains(4, 5, 6);
            assertThat(matched(index, docValues.numbers("dv_num", encoding, numbers(null, "-1", true, true))))
                    .isEmpty();
        }
    }

    @Test
    void leavesOutTheBoundsOfAByteRangeThatAreExclusive() throws Exception {
        try (RuntimeIndex index = open()) {
            DocValuesSearch docValues = index.docValuesSearch().orElseThrow();
            List<Integer> inclusive = matched(index, docValues.bytes("dv_bin", bytes("b5", "b7", true, true)));
            assertThat(inclusive).containsExactly(4, 5, 6);
            assertThat(matched(index, docValues.bytes("dv_bin", bytes("b5", "b7", false, false))))
                    .containsExactly(5);
            assertThat(matched(index, docValues.bytes("dv_bin", bytes("b5", null, false, true))))
                    .containsExactly(5, 6);
            List<Integer> below = matched(index, docValues.bytes("dv_bin", bytes(null, "b5", true, true)));
            assertThat(matched(index, docValues.bytes("dv_bin", bytes(null, "b5", true, false))))
                    .isEqualTo(below.stream().filter(doc -> doc != 4).toList());
            assertThat(matched(index, docValues.bytes("dv_bin", bytes(null, null, true, true))))
                    .hasSize(index.maxDoc() - 1);
            assertThat(matched(index, docValues.prefix("dv_bin", new byte[0]))).hasSize(index.maxDoc() - 1);
        }
    }

    @Test
    void searchesSortedSetsBySetAndRange() throws Exception {
        try (RuntimeIndex index = open()) {
            DocValuesSearch docValues = index.docValuesSearch().orElseThrow();
            assertThat(matched(index, docValues.bytes("dv_set", new Match.Values<>(List.of(utf8("y5"), utf8("y6"))))))
                    .containsExactly(4, 5);
            assertThat(matched(index, docValues.bytes("dv_set", bytes("y5", "y6", true, true))))
                    .containsExactly(4, 5);
            assertThat(matched(index, docValues.bytes("dv_set", bytes("y5", "y6", false, false))))
                    .isEmpty();
        }
    }

    @Test
    void matchesNothingForAPrefixOfOnlyMaximalBytes() throws Exception {
        try (RuntimeIndex index = open()) {
            DocValuesSearch docValues = index.docValuesSearch().orElseThrow();
            // No byte string follows 0xff 0xff, so the prefix is a range without an upper end.
            assertThat(matched(index, docValues.prefix("dv_sorted", new byte[] {(byte) 0xff, (byte) 0xff})))
                    .isEmpty();
            assertThat(matched(index, docValues.prefix("dv_sorted", new byte[] {(byte) 0xff})))
                    .isEmpty();
        }
    }

    @Test
    void findsTheSuccessorOfAPrefix() {
        assertThat(Lucene5DocValuesQueries.successor(new BytesRef(new byte[] {1, 2, 3})))
                .hasValueSatisfying(next -> assertThat(next.utf8ToString()).isEqualTo("\u0001\u0002\u0004"));
        assertThat(Lucene5DocValuesQueries.successor(new BytesRef(new byte[] {1, (byte) 0xff, (byte) 0xff})))
                .hasValueSatisfying(next -> assertThat(next.length).isEqualTo(1));
        assertThat(Lucene5DocValuesQueries.successor(new BytesRef(new byte[] {(byte) 0xff, (byte) 0xff})))
                .isEmpty();
        assertThat(Lucene5DocValuesQueries.successor(new BytesRef(new byte[0]))).isEmpty();
    }

    private static Query binaryQuery(FieldInfo field, Match<byte[]> match) {
        return Lucene5DocValuesQueries.bytes(field, match);
    }

    @Test
    void comparesAndPrintsBinaryDocValuesQueries() throws IOException {
        try (FSDirectory directory = FSDirectory.open(lucene5());
                DirectoryReader reader = DirectoryReader.open(directory)) {
            FieldInfo bin = MultiFields.getMergedFieldInfos(reader).fieldInfo("dv_bin");
            Query range = binaryQuery(bin, bytes("b5", "b7", true, false));
            assertThat(range)
                    .isEqualTo(binaryQuery(bin, bytes("b5", "b7", true, false)))
                    .hasSameHashCodeAs(binaryQuery(bin, bytes("b5", "b7", true, false)))
                    .isNotEqualTo(binaryQuery(bin, bytes("b5", "b7", true, true)))
                    .isNotEqualTo(binaryQuery(bin, bytes("b5", "b8", true, false)))
                    .isNotEqualTo(binaryQuery(bin, bytes("b4", "b7", true, false)))
                    .isNotEqualTo(binaryQuery(bin, bytes("b5", "b7", false, false)))
                    .isNotEqualTo(binaryQuery(bin, new Match.Values<>(List.of(utf8("b5")))))
                    .isNotEqualTo(new MatchAllDocsQuery());
        }
    }

    @Test
    void printsBinaryDocValuesQueries() throws IOException {
        try (FSDirectory directory = FSDirectory.open(lucene5());
                DirectoryReader reader = DirectoryReader.open(directory)) {
            FieldInfo bin = MultiFields.getMergedFieldInfos(reader).fieldInfo("dv_bin");
            assertThat(binaryQuery(bin, bytes("b5", "b7", false, true)).toString("other"))
                    .startsWith("dv_bin:{")
                    .contains(" TO ")
                    .endsWith("]");
            assertThat(binaryQuery(bin, bytes(null, null, true, false)).toString("dv_bin"))
                    .isEqualTo("[* TO *}");
            assertThat(binaryQuery(bin, new Match.Values<>(List.of(utf8("b5")))).toString("dv_bin"))
                    .isEqualTo("[62 35]");
            assertThat(binaryQuery(bin, new Match.Values<>(List.of(utf8("b5"), utf8("b6"))))
                            .toString("dv_bin"))
                    .isEqualTo("([62 35] [62 36])");
        }
    }
}
