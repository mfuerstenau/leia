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
import io.github.mfuerstenau.leia.protocol.search.DocValuesRange;
import io.github.mfuerstenau.leia.protocol.search.Hit;
import io.github.mfuerstenau.leia.protocol.search.LikeTerm;
import io.github.mfuerstenau.leia.protocol.search.PointField;
import io.github.mfuerstenau.leia.protocol.search.PointType;
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
     * Refuses the standard parser, More-Like-This and doc values queries, which 3.6 lacks (ADR 0066, ADR
     * 0074), in a search, a filter and an explanation.
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
     * The standard parser, More-Like-This and doc values queries on 5.5 and 7.7 (ADR 0074), on what
     * their writers put in the indexes, where document {@code i} has the id {@code i - 1}: each in a
     * search, a filter and an explanation of its first hit, which agree.
     *
     * @param points whether the index has points, which the standard parser then searches
     */
    static void searchesTheQueriesOf912(RealBackend backend, boolean points) throws Exception {
        assertThat(searchFilterAndExplain(backend, RealBackend.standard("body:GAM*", QueryOperator.OR, List.of())))
                .containsExactly(0);
        assertThat(backend.search(RealBackend.standard("body:GAM*", QueryOperator.OR, List.of()), 10)
                        .query())
                .isEqualTo("body:gam*");
        if (points) {
            SearchQuery query = RealBackend.standard(
                    "pt:[100 TO 114] OR amount:5000 OR gamma",
                    QueryOperator.OR,
                    List.of(new PointField("pt", PointType.INT), new PointField("amount", PointType.LONG)));
            assertThat(searchFilterAndExplain(backend, query)).containsExactlyInAnyOrder(0, 4);
            assertThat(backend.search(query, 10).query()).isEqualTo("pt:[100 TO 114] amount:[5000 TO 5000] body:gamma");
        } else {
            // No field has points.
            backend.fails(
                    Methods.START_SEARCH,
                    params(
                            RealBackend.standard(
                                    "price:10", QueryOperator.OR, List.of(new PointField("price", PointType.INT))),
                            10),
                    ErrorCode.INVALID_PARAMS);
        }
        RpcException syntax = backend.fails(
                Methods.START_SEARCH,
                params(RealBackend.standard("body:(", QueryOperator.OR, List.of()), 10),
                ErrorCode.INVALID_QUERY);
        assertThat(syntax).hasMessage("Invalid query: Cannot parse 'body:(': Encountered \"<EOF>\" at column 6.");

        SearchQuery byDoc = RealBackend.moreLikeThis(OptionalInt.of(0), Optional.empty());
        assertThat(searchFilterAndExplain(backend, byDoc)).startsWith(0);
        SearchResults results = backend.search(byDoc, 10);
        assertThat(results.query())
                .isEqualTo("like(doc 0, fields [body], minTermFreq 1, minDocFreq 1, maxQueryTerms 25)");
        assertThat(results.moreLikeThisTerms().orElseThrow().getFirst())
                .extracting(LikeTerm::term, LikeTerm::tf, LikeTerm::docFreq)
                .containsExactly("gamma", 2, 1);
        SearchQuery byText = RealBackend.moreLikeThis(OptionalInt.empty(), Optional.of("gamma"));
        assertThat(searchFilterAndExplain(backend, byText)).containsExactly(0);
        assertThat(backend.search(byText, 10).moreLikeThisTerms().orElseThrow())
                .extracting(LikeTerm::term)
                .containsExactly("gamma");
        assertThat(backend.search(RealBackend.classic("gamma"), 10).moreLikeThisTerms())
                .isEmpty();

        // Queries that the 400 of document 4, which 4.x lacks, doesn't change.
        Optional<DocValuesNumber> longs = Optional.of(DocValuesNumber.LONG);
        assertThat(searchFilterAndExplain(
                        backend,
                        docValues("dv_num", longs, Optional.of(List.of(text("5"), text("6"))), Optional.empty())))
                .containsExactlyInAnyOrder(4, 5);
        assertThat(searchFilterAndExplain(
                        backend,
                        docValues(
                                "dv_num",
                                Optional.of(DocValuesNumber.SORTABLE_DOUBLE),
                                Optional.empty(),
                                Optional.of(new DocValuesRange(
                                        Optional.of(text("0")), Optional.of(text("1")), true, true)))))
                // As sortable bits, a small long is a tiny positive double.
                .containsExactlyInAnyOrder(0, 3, 4, 5, 6);
        assertThat(searchFilterAndExplain(
                        backend,
                        docValues(
                                "dv_sorted_num",
                                longs,
                                Optional.of(List.of(text("-5"), text("-7"))),
                                Optional.empty())))
                .containsExactlyInAnyOrder(4, 6);
        assertThat(searchFilterAndExplain(
                        backend,
                        docValues("dv_sorted", Optional.empty(), Optional.of(List.of(text("s2"))), Optional.empty())))
                .containsExactly(4);
        assertThat(searchFilterAndExplain(
                        backend,
                        RealBackend.docValues(new DocValuesQuery(
                                "dv_set",
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.of(text("y5"))))))
                .containsExactly(4);
        // dv_bin is b<i>: 62 is b, 35 to 37 are 5 to 7.
        assertThat(searchFilterAndExplain(
                        backend,
                        docValues(
                                "dv_bin",
                                Optional.empty(),
                                Optional.empty(),
                                Optional.of(new DocValuesRange(
                                        Optional.of(new BytesValue("6235", true)),
                                        Optional.of(text("b7")),
                                        true,
                                        false)))))
                .containsExactlyInAnyOrder(4, 5);
        RpcException binary = backend.fails(
                Methods.START_SEARCH,
                params(docValues("dv_bin", longs, Optional.of(List.of(text("1"))), Optional.empty()), 10),
                ErrorCode.INVALID_PARAMS);
        assertThat(binary)
                .hasMessage("Invalid params: the field 'dv_bin' has BINARY doc values, which hold bytes, not numbers");
    }

    /** The search, the filter and the explanation of the first hit agree; returns the hits' ids. */
    private static List<Integer> searchFilterAndExplain(RealBackend backend, SearchQuery query) throws Exception {
        SearchResults results = backend.search(query, 10);
        assertThat(results.hits()).isNotEmpty();
        FilterResult filter = backend.filter(new StartFilterParams(Optional.of(query), Optional.empty(), false));
        assertThat(filter.matchCount()).isEqualTo(results.totalHits());
        assertThat(filter.query()).isEqualTo(results.query());
        RuntimeExplains.explainsTheHit(backend, query, results.hits().getFirst(), false);
        return results.hits().stream().map(Hit::docId).toList();
    }

    private static SearchQuery docValues(
            String field,
            Optional<DocValuesNumber> number,
            Optional<List<BytesValue>> values,
            Optional<DocValuesRange> range) {
        return RealBackend.docValues(new DocValuesQuery(field, number, values, range, Optional.empty()));
    }

    private static BytesValue text(String text) {
        return new BytesValue(text, false);
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
