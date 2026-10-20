package io.github.mfuerstenau.leia.server;

import static io.github.mfuerstenau.leia.server.RealBackend.BM25;
import static io.github.mfuerstenau.leia.server.RealBackend.classic;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.analysis.AnalysisComponent;
import io.github.mfuerstenau.leia.protocol.analysis.Analyzer;
import io.github.mfuerstenau.leia.protocol.analysis.CustomAnalyzer;
import io.github.mfuerstenau.leia.protocol.analysis.PresetAnalyzer;
import io.github.mfuerstenau.leia.protocol.analysis.Shingles;
import io.github.mfuerstenau.leia.protocol.analysis.StandardAnalyzer;
import io.github.mfuerstenau.leia.protocol.analysis.WordSet;
import io.github.mfuerstenau.leia.protocol.document.BytesValue;
import io.github.mfuerstenau.leia.protocol.filter.FilterResult;
import io.github.mfuerstenau.leia.protocol.filter.StartFilterParams;
import io.github.mfuerstenau.leia.protocol.search.DocValuesNumber;
import io.github.mfuerstenau.leia.protocol.search.DocValuesQuery;
import io.github.mfuerstenau.leia.protocol.search.Hit;
import io.github.mfuerstenau.leia.protocol.search.QueryOperator;
import io.github.mfuerstenau.leia.protocol.search.SearchQuery;
import io.github.mfuerstenau.leia.protocol.search.SearchResults;
import io.github.mfuerstenau.leia.protocol.search.StartExplainParams;
import io.github.mfuerstenau.leia.protocol.search.StartSearchParams;
import io.github.mfuerstenau.leia.protocol.search.TermQuery;
import io.github.mfuerstenau.leia.protocol.terms.GetPostingsParams;
import io.github.mfuerstenau.leia.protocol.terms.Posting;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

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

    /**
     * Refuses the standard parser, More-Like-This and doc values queries, which only the runtimes of
     * 9.12 and 10 have (ADR 0066), in a search, a filter and an explanation.
     */
    static void refusesTheQueriesOf912(RealBackend backend) {
        List<SearchQuery> queries = List.of(
                RealBackend.standard("alpha", QueryOperator.OR, List.of()),
                RealBackend.moreLikeThis(OptionalInt.of(0), Optional.empty()),
                RealBackend.moreLikeThis(OptionalInt.empty(), Optional.of("alpha")),
                RealBackend.docValues(new DocValuesQuery(
                        "dv_num",
                        Optional.of(DocValuesNumber.LONG),
                        Optional.of(List.of(new BytesValue("1", false))),
                        Optional.empty(),
                        Optional.empty())));
        for (SearchQuery query : queries) {
            backend.fails(Methods.START_SEARCH, params(query, 10), ErrorCode.NOT_SUPPORTED);
            backend.fails(
                    Methods.START_FILTER,
                    new StartFilterParams(Optional.of(query), Optional.empty(), false),
                    ErrorCode.NOT_SUPPORTED);
            backend.fails(Methods.START_EXPLAIN, new StartExplainParams(query, BM25, 0), ErrorCode.NOT_SUPPORTED);
        }
    }

    /**
     * The analyzers of 3.6, 5.5 and 7.7 (ADR 0067): stop words from a file, which the runtime reads as
     * Lucene 10 does; shingles, custom analyzers and the other options of a preset aren't supported.
     */
    static void searchesWithTheAnalyzersOfAnOldRuntime(RealBackend backend, String lucene, Path tmp) throws Exception {
        Path file = Files.writeString(tmp.resolve("stopwords.txt"), "\n  alpha  \n");
        assertThat(backend.search(classic("alpha document", preset(file, Optional.empty())), 10)
                        .query())
                .isEqualTo("body:document");
        Path none = tmp.resolve("none.txt");
        RpcException unreadable = backend.fails(
                Methods.START_SEARCH,
                params(classic("alpha", preset(none, Optional.empty())), 10),
                ErrorCode.INVALID_ANALYZER);
        assertThat(unreadable).hasMessage("Invalid analyzer: stop words: no file " + none);
        String notSupported = "Not supported with Lucene " + lucene + ": ";
        RpcException exclusions = backend.fails(
                Methods.START_SEARCH,
                params(
                        classic(
                                "alpha",
                                preset(
                                        file,
                                        Optional.of(new WordSet(Optional.of(List.of("documents")), Optional.empty())))),
                        10),
                ErrorCode.NOT_SUPPORTED);
        assertThat(exclusions)
                .hasMessage(notSupported + "options of StandardAnalyzer other than stop words and the longest token");
        RpcException custom = backend.fails(
                Methods.START_SEARCH,
                params(
                        classic(
                                "alpha",
                                new Analyzer(
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.of(new CustomAnalyzer(
                                                List.of(),
                                                new AnalysisComponent("whitespace", Map.of()),
                                                List.of(),
                                                Optional.empty())),
                                        Optional.empty(),
                                        Optional.empty(),
                                        List.of(),
                                        List.of())),
                        10),
                ErrorCode.NOT_SUPPORTED);
        assertThat(custom).hasMessage(notSupported + "custom analyzers");
        RpcException shingles = backend.fails(
                Methods.START_SEARCH,
                params(
                        classic(
                                "alpha",
                                new Analyzer(
                                        Optional.of(new StandardAnalyzer()),
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.of(new Shingles(2, 2, " ", true, false, "_")),
                                        Optional.empty(),
                                        List.of(),
                                        List.of())),
                        10),
                ErrorCode.NOT_SUPPORTED);
        assertThat(shingles).hasMessage(notSupported + "shingles");
    }

    /** {@code StandardAnalyzer} with the stop words of {@code file}, and these stem exclusions. */
    private static Analyzer preset(Path file, Optional<WordSet> stemExclusion) {
        return new Analyzer(
                Optional.empty(),
                Optional.of(new PresetAnalyzer(
                        "org.apache.lucene.analysis.standard.StandardAnalyzer",
                        Optional.of(new WordSet(Optional.empty(), Optional.of(file.toString()))),
                        stemExclusion,
                        OptionalInt.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty())),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                List.of());
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
