package io.github.mfuerstenau.leia.server;

import static io.github.mfuerstenau.leia.server.RealBackend.BM25;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.document.BytesValue;
import io.github.mfuerstenau.leia.protocol.filter.FilterResult;
import io.github.mfuerstenau.leia.protocol.filter.StartFilterParams;
import io.github.mfuerstenau.leia.protocol.search.Hit;
import io.github.mfuerstenau.leia.protocol.search.SearchQuery;
import io.github.mfuerstenau.leia.protocol.search.SearchResults;
import io.github.mfuerstenau.leia.protocol.search.StartExplainParams;
import io.github.mfuerstenau.leia.protocol.search.StartSearchParams;
import io.github.mfuerstenau.leia.protocol.search.TermQuery;
import io.github.mfuerstenau.leia.protocol.terms.GetPostingsParams;
import io.github.mfuerstenau.leia.protocol.terms.Posting;
import java.util.List;
import java.util.Optional;

/** Term queries on an index of the runtimes' writers (ADR 0058), with a {@code body} field. */
final class RuntimeSearches {

    private RuntimeSearches() {}

    /**
     * Searches, filters by and explains a term query for {@code text} in {@code body}: its hits are
     * the live postings of the term. An unknown field and a binary term that isn't hex fail.
     *
     * @param exact whether an explanation's value is the score to the bit, as in {@link
     *     RuntimeExplains#explainsTheHit}
     */
    static void searchesATerm(RealBackend backend, int maxDoc, String text, boolean exact) throws Exception {
        BytesValue term = new BytesValue(text, false);
        List<Integer> live = backend
                .call(Methods.GET_POSTINGS, new GetPostingsParams("body", term, 0, maxDoc, Optional.empty()))
                .postings()
                .stream()
                .filter(posting -> !posting.deleted())
                .map(Posting::docId)
                .toList();
        assertThat(live).isNotEmpty();

        SearchResults results = backend.search(term("body", term), maxDoc);
        assertThat(results.query()).isEqualTo("body:" + text);
        assertThat(results.totalHits()).isEqualTo(live.size());
        assertThat(results.hits()).extracting(Hit::docId).containsExactlyInAnyOrderElementsOf(live);
        RuntimeExplains.explainsTheHit(
                backend, term("body", term), results.hits().getFirst(), exact);

        FilterResult filter =
                backend.filter(new StartFilterParams(Optional.of(term("body", term)), Optional.empty(), false));
        assertThat(filter.query()).isEqualTo("body:" + text);
        assertThat(filter.matchCount()).isEqualTo(live.size());

        assertThat(backend.search(term("body", new BytesValue("nothing", false)), maxDoc)
                        .totalHits())
                .isZero();
        backend.fails(Methods.START_SEARCH, params(term("nothing", term), maxDoc), ErrorCode.FIELD_NOT_FOUND);
        backend.fails(
                Methods.START_SEARCH,
                params(term("body", new BytesValue("not hex", true)), maxDoc),
                ErrorCode.INVALID_PARAMS);
        backend.fails(
                Methods.START_EXPLAIN,
                new StartExplainParams(term("nothing", term), BM25, 0),
                ErrorCode.FIELD_NOT_FOUND);
    }

    static SearchQuery term(String field, BytesValue term) {
        return new SearchQuery(
                Optional.empty(),
                Optional.empty(),
                Optional.of(new TermQuery(field, term)),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static StartSearchParams params(SearchQuery query, int limit) {
        return new StartSearchParams(query, BM25, List.of(), 0, limit, true, Optional.of(List.of()), Optional.empty());
    }
}
