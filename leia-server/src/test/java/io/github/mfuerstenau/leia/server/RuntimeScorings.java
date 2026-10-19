package io.github.mfuerstenau.leia.server;

import static io.github.mfuerstenau.leia.server.RealBackend.BM25;
import static io.github.mfuerstenau.leia.server.RealBackend.classic;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.search.ClassicSimilarity;
import io.github.mfuerstenau.leia.protocol.search.Explanation;
import io.github.mfuerstenau.leia.protocol.search.FieldSort;
import io.github.mfuerstenau.leia.protocol.search.Hit;
import io.github.mfuerstenau.leia.protocol.search.SearchResults;
import io.github.mfuerstenau.leia.protocol.search.Similarity;
import io.github.mfuerstenau.leia.protocol.search.SortKey;
import io.github.mfuerstenau.leia.protocol.search.SortMissing;
import io.github.mfuerstenau.leia.protocol.search.SortSelector;
import io.github.mfuerstenau.leia.protocol.search.SortType;
import io.github.mfuerstenau.leia.protocol.search.SortValue;
import io.github.mfuerstenau.leia.protocol.search.StartSearchParams;
import java.util.List;
import java.util.Optional;

/**
 * The similarity and sort on an index a runtime opened (ADR 0064), with a text field {@code body} in
 * which every document has {@code alpha}, the numeric doc values {@code dv_num}, which some documents
 * lack, and the binary doc values {@code dv_bin}.
 */
final class RuntimeScorings {

    static final Similarity CLASSIC =
            new Similarity(Optional.empty(), Optional.of(new ClassicSimilarity()), Optional.empty());

    private RuntimeScorings() {}

    static SortKey field(String field, SortType type, boolean reverse, SortSelector selector, SortMissing missing) {
        return new SortKey(
                Optional.of(new FieldSort(field, type, reverse, selector, missing)),
                Optional.empty(),
                Optional.empty());
    }

    static StartSearchParams sorted(Similarity similarity, SortKey... sort) {
        return new StartSearchParams(
                classic("alpha"), similarity, List.of(sort), 0, 20, true, Optional.of(List.of()), Optional.empty());
    }

    /**
     * Sorts by {@code dv_num} reversed, the documents without a value last, scored with TF-IDF: each
     * hit carries its value, empty without one, and its TF-IDF score, which the explanation gives too.
     * Keys that don't fit the field's doc values, or name no field, fail at once.
     */
    static void sortsAndScores(RealBackend backend) throws Exception {
        SearchResults results = backend.search(
                sorted(CLASSIC, field("dv_num", SortType.LONG, true, SortSelector.MIN, SortMissing.LAST)));
        List<Hit> hits = results.hits();
        assertThat(hits).isNotEmpty();
        List<Optional<String>> numbers = hits.stream()
                .map(hit -> hit.sortValues().getFirst())
                .map(SortValue::number)
                .toList();
        assertThat(numbers.getFirst()).isPresent();
        assertThat(numbers.getLast()).isEmpty();
        List<Long> present =
                numbers.stream().flatMap(Optional::stream).map(Long::parseLong).toList();
        assertThat(present).isSortedAccordingTo((a, b) -> Long.compare(b, a));

        Hit first = hits.getFirst();
        Explanation classic = backend.explain(classic("alpha"), CLASSIC, first.docId());
        assertThat(Float.parseFloat(classic.value())).isEqualTo(first.score());
        assertThat(Float.parseFloat(
                        backend.explain(classic("alpha"), BM25, first.docId()).value()))
                .isNotEqualTo(first.score());

        backend.fails(
                Methods.START_SEARCH,
                sorted(BM25, field("dv_bin", SortType.STRING, false, SortSelector.MIN, SortMissing.DEFAULT)),
                ErrorCode.INVALID_PARAMS);
        assertThat(backend.fails(
                                Methods.START_SEARCH,
                                sorted(
                                        BM25,
                                        field("dv_num", SortType.INT, false, SortSelector.MAX, SortMissing.DEFAULT)),
                                ErrorCode.INVALID_PARAMS)
                        .getMessage())
                .isEqualTo("Invalid params: the field 'dv_num' has single-valued NUMERIC doc values, which can't"
                        + " select MAX");
        backend.fails(
                Methods.START_SEARCH,
                sorted(BM25, field("nothing", SortType.INT, false, SortSelector.MIN, SortMissing.DEFAULT)),
                ErrorCode.FIELD_NOT_FOUND);
        backend.fails(
                Methods.START_SEARCH,
                sorted(BM25, new SortKey(Optional.empty(), Optional.empty(), Optional.empty())),
                ErrorCode.INVALID_PARAMS);
    }

    /** A runtime without scoring refuses any sort other than the best score first. */
    static void doesNotSort(RealBackend backend) {
        backend.fails(
                Methods.START_SEARCH,
                sorted(BM25, field("dv_num", SortType.LONG, false, SortSelector.MIN, SortMissing.DEFAULT)),
                ErrorCode.NOT_SUPPORTED);
    }
}
