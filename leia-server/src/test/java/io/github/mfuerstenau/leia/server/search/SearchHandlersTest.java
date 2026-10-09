package io.github.mfuerstenau.leia.server.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mfuerstenau.leia.core.ReadOnlyIndex;
import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.ProtocolJson;
import io.github.mfuerstenau.leia.protocol.Response;
import io.github.mfuerstenau.leia.protocol.analysis.AnalysisComponent;
import io.github.mfuerstenau.leia.protocol.analysis.Analyzer;
import io.github.mfuerstenau.leia.protocol.analysis.CustomAnalyzer;
import io.github.mfuerstenau.leia.protocol.analysis.StandardAnalyzer;
import io.github.mfuerstenau.leia.protocol.document.BytesValue;
import io.github.mfuerstenau.leia.protocol.document.StoredType;
import io.github.mfuerstenau.leia.protocol.document.StoredValue;
import io.github.mfuerstenau.leia.protocol.job.JobParams;
import io.github.mfuerstenau.leia.protocol.search.Bm25Similarity;
import io.github.mfuerstenau.leia.protocol.search.BooleanSimilarity;
import io.github.mfuerstenau.leia.protocol.search.ClassicQuery;
import io.github.mfuerstenau.leia.protocol.search.ClassicSimilarity;
import io.github.mfuerstenau.leia.protocol.search.DocSort;
import io.github.mfuerstenau.leia.protocol.search.Explanation;
import io.github.mfuerstenau.leia.protocol.search.FieldSort;
import io.github.mfuerstenau.leia.protocol.search.GetExplainResult;
import io.github.mfuerstenau.leia.protocol.search.GetSearchResult;
import io.github.mfuerstenau.leia.protocol.search.Hit;
import io.github.mfuerstenau.leia.protocol.search.HitField;
import io.github.mfuerstenau.leia.protocol.search.MoreLikeThisQuery;
import io.github.mfuerstenau.leia.protocol.search.ParserOptions;
import io.github.mfuerstenau.leia.protocol.search.PointField;
import io.github.mfuerstenau.leia.protocol.search.PointType;
import io.github.mfuerstenau.leia.protocol.search.QueryOperator;
import io.github.mfuerstenau.leia.protocol.search.ScoreSort;
import io.github.mfuerstenau.leia.protocol.search.SearchQuery;
import io.github.mfuerstenau.leia.protocol.search.SearchResults;
import io.github.mfuerstenau.leia.protocol.search.Similarity;
import io.github.mfuerstenau.leia.protocol.search.SortKey;
import io.github.mfuerstenau.leia.protocol.search.SortMissing;
import io.github.mfuerstenau.leia.protocol.search.SortSelector;
import io.github.mfuerstenau.leia.protocol.search.SortType;
import io.github.mfuerstenau.leia.protocol.search.SortValue;
import io.github.mfuerstenau.leia.protocol.search.StandardQuery;
import io.github.mfuerstenau.leia.protocol.search.StartExplainParams;
import io.github.mfuerstenau.leia.protocol.search.StartSearchParams;
import io.github.mfuerstenau.leia.protocol.search.TermQuery;
import io.github.mfuerstenau.leia.protocol.search.TotalHitsRelation;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.index.CurrentIndex;
import io.github.mfuerstenau.leia.server.job.Jobs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** {@code startSearch} / {@code getSearch} and {@code startExplain} / {@code getExplain} together. */
class SearchHandlersTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final String LONG_BODY = "red " + "x".repeat(300);
    private static final ParserOptions DEFAULTS = new ParserOptions(false, 0, 0, true);
    private static final Analyzer STANDARD = new Analyzer(
            Optional.of(new StandardAnalyzer()),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());
    private static final Similarity BM25 =
            new Similarity(Optional.of(new Bm25Similarity(1.2f, 0.75f)), Optional.empty(), Optional.empty());
    private static final Similarity BOOLEAN =
            new Similarity(Optional.empty(), Optional.empty(), Optional.of(new BooleanSimilarity()));

    @TempDir
    Path tmp;

    private final Jobs jobs = new Jobs();
    private final CurrentIndex currentIndex = new CurrentIndex(jobs);
    private final StartSearchHandler startSearch = new StartSearchHandler(currentIndex, jobs);
    private final GetSearchHandler getSearch = new GetSearchHandler(jobs);
    private final StartExplainHandler startExplain = new StartExplainHandler(currentIndex, jobs);
    private final GetExplainHandler getExplain = new GetExplainHandler(jobs);

    @Test
    void searchesAndPages() throws Exception {
        openIndex();

        SearchResults first = search(params(classic("red"), 0, 1, Optional.of(List.of("id"))));

        assertThat(first.query()).isEqualTo("body:red");
        assertThat(first.rewrittenQuery()).isEqualTo("body:red");
        assertThat(first.totalHits()).isEqualTo(2);
        assertThat(first.totalHitsRelation()).isEqualTo(TotalHitsRelation.EQUAL_TO);
        assertThat(first.hits()).extracting(Hit::docId).containsExactly(0);
        assertThat(first.hits().getFirst().fields())
                .containsExactly(new HitField("id", List.of(new StoredValue(StoredType.STRING, "1", false))));
        assertThat(first.hits().getFirst().score()).isPositive();

        SearchResults second = search(params(classic("red"), 1, 1, Optional.of(List.of("id"))));
        assertThat(second.hits()).extracting(Hit::docId).containsExactly(2);
    }

    @Test
    void cutsLongValues() throws Exception {
        openIndex();

        Hit hit = search(params(classic("red"), 1, 1, Optional.empty())).hits().getFirst();

        StoredValue body = hit.fields().stream()
                .filter(field -> field.name().equals("body"))
                .findFirst()
                .orElseThrow()
                .values()
                .getFirst();
        assertThat(body.value()).hasSize(SearchResults.MAX_VALUE_LENGTH);
        assertThat(body.fullLength()).isEqualTo(OptionalLong.of(LONG_BODY.length()));
    }

    @Test
    void searchesForABinaryTerm() throws Exception {
        openIndex();

        SearchResults results =
                search(params(term("key", new BytesValue("ff00", true)), 0, 10, Optional.of(List.of("id"))));

        assertThat(results.hits()).extracting(Hit::docId).containsExactly(1);
        assertThat(results.query()).isEqualTo("key:[ff 0]");
    }

    @Test
    void parsesTheQueryAtOnce() throws Exception {
        openIndex();
        SearchQuery broken = classic("body:(red");

        assertThatThrownBy(() -> startSearch.handle(params(broken, 0, 10, Optional.empty())))
                .isInstanceOfSatisfying(
                        RpcException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_QUERY))
                .hasMessageStartingWith("Invalid query: Cannot parse 'body:(red'")
                .hasMessageNotContaining("\n");
        assertFails(() -> startExplain.handle(new StartExplainParams(broken, BM25, 0)), ErrorCode.INVALID_QUERY);
        assertThat(errorCodeOf("startSearch.error-invalid-query.json")).isEqualTo(ErrorCode.INVALID_QUERY);
        assertThat(errorCodeOf("getSearch.error-invalid-query.json")).isEqualTo(ErrorCode.INVALID_QUERY);
    }

    @Test
    void checksTheFields() throws Exception {
        openIndex();
        SearchQuery unknownDefault = new SearchQuery(
                Optional.of(new ClassicQuery("red", "bdy", QueryOperator.OR, DEFAULTS, 2, false, false, STANDARD)),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());

        assertFails(
                () -> startSearch.handle(params(unknownDefault, 0, 10, Optional.empty())), ErrorCode.FIELD_NOT_FOUND);
        assertFails(
                () -> startSearch.handle(params(term("bdy", new BytesValue("red", false)), 0, 10, Optional.empty())),
                ErrorCode.FIELD_NOT_FOUND);
        assertFails(
                () -> startSearch.handle(params(classic("red"), 0, 10, Optional.of(List.of("id", "nope")))),
                ErrorCode.FIELD_NOT_FOUND);
    }

    @Test
    void needsExactlyOneAlternative() throws Exception {
        openIndex();
        SearchQuery both = new SearchQuery(
                classic("red").classic(),
                Optional.empty(),
                Optional.of(new TermQuery("body", new BytesValue("red", false))),
                Optional.empty());
        SearchQuery neither = new SearchQuery(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

        assertFails(() -> startSearch.handle(params(both, 0, 10, Optional.empty())), ErrorCode.INVALID_PARAMS);
        assertFails(() -> startSearch.handle(params(neither, 0, 10, Optional.empty())), ErrorCode.INVALID_PARAMS);
        assertFails(
                () -> startSearch.handle(params(term("key", new BytesValue("zz", true)), 0, 10, Optional.empty())),
                ErrorCode.INVALID_PARAMS);
    }

    @ParameterizedTest
    @CsvSource({"0, 0", "0, 1001", "-1, 10", "9991, 10", "9000, 1001"})
    void rejectsPagesOutOfRange(int offset, int limit) throws Exception {
        openIndex();

        assertFails(
                () -> startSearch.handle(params(classic("red"), offset, limit, Optional.empty())),
                ErrorCode.INVALID_PARAMS);
    }

    @Test
    void acceptsTheLastPageOfTheWindow() throws Exception {
        openIndex();

        SearchResults results = search(params(
                classic("red"),
                StartSearchParams.MAX_WINDOW - StartSearchParams.MAX_LIMIT,
                StartSearchParams.MAX_LIMIT,
                Optional.empty()));

        assertThat(results.hits()).isEmpty();
        assertThat(results.totalHits()).isEqualTo(2);
    }

    @Test
    void appliesTheParserOptions() throws Exception {
        openIndex();
        ParserOptions options = new ParserOptions(true, 2, 1, true);

        SearchResults classic =
                search(params(classic("*ed \"red x\" blu~", options, 1, false, false), 0, 10, Optional.empty()));
        assertThat(classic.query()).isEqualTo("body:*ed body:\"red x\"~2 body:blu~1");
        SearchResults standard = search(params(standard("*ed \"red x\"", options, List.of()), 0, 10, Optional.empty()));
        assertThat(standard.query()).isEqualTo("body:*ed body:\"red x\"~2");
        assertThat(standard.hits()).extracting(Hit::docId).containsExactly(0, 2);
    }

    @Test
    void rejectsParserOptionsOutOfRange() throws Exception {
        openIndex();

        for (SearchQuery query : List.of(
                classic("red", new ParserOptions(false, -1, 0, true), 2, false, false),
                standard("red", new ParserOptions(false, 0, -1, true), List.of()),
                classic("red", DEFAULTS, 3, false, false),
                classic("red", DEFAULTS, 2, false, true))) {
            assertFails(() -> startSearch.handle(params(query, 0, 10, Optional.empty())), ErrorCode.INVALID_PARAMS);
        }
    }

    @Test
    void searchesPointsWithTheStandardParser() throws Exception {
        openIndex();

        SearchResults results = search(params(
                standard("size:[15 TO 30]", DEFAULTS, List.of(new PointField("size", PointType.INT))),
                0,
                10,
                Optional.empty()));
        assertThat(results.query()).isEqualTo("size:[15 TO 30]");
        assertThat(results.hits()).extracting(Hit::docId).containsExactly(1, 2);
        // Without a type, the range is over text.
        assertThat(search(params(standard("size:[15 TO 30]", DEFAULTS, List.of()), 0, 10, Optional.empty()))
                        .hits())
                .isEmpty();
        assertFails(
                () -> startSearch.handle(params(
                        standard("size:[x TO 30]", DEFAULTS, List.of(new PointField("size", PointType.INT))),
                        0,
                        10,
                        Optional.empty())),
                ErrorCode.INVALID_QUERY);
    }

    @Test
    void checksThePointTypes() throws Exception {
        openIndex();

        assertFails(
                () -> startSearch.handle(params(
                        standard("red", DEFAULTS, List.of(new PointField("sise", PointType.INT))),
                        0,
                        10,
                        Optional.empty())),
                ErrorCode.FIELD_NOT_FOUND);
        for (List<PointField> points : List.of(
                List.of(new PointField("size", PointType.LONG)),
                List.of(new PointField("body", PointType.INT)),
                List.of(new PointField("size", PointType.INT), new PointField("size", PointType.FLOAT)))) {
            assertFails(
                    () -> startSearch.handle(params(standard("red", DEFAULTS, points), 0, 10, Optional.empty())),
                    ErrorCode.INVALID_PARAMS);
        }
    }

    @Test
    void sortsByDocValuesAndSendsTheSortValues() throws Exception {
        openIndex();
        SearchResults byScore = search(params(classic("red"), 0, 10, Optional.of(List.of())));

        SearchResults byColor = search(sorted(classic("red"), fieldKey("color", SortType.STRING, false)));
        SearchResults byRank = search(sorted(classic("red"), fieldKey("rank", SortType.LONG, false)));

        assertThat(byScore.hits()).extracting(Hit::docId).containsExactly(0, 2);
        assertThat(byScore.hits())
                .allSatisfy(hit -> assertThat(hit.sortValues()).isEmpty());
        assertThat(byColor.hits()).extracting(Hit::docId).containsExactly(2, 0);
        assertThat(byColor.hits())
                .extracting(Hit::sortValues)
                .containsExactly(
                        List.of(new SortValue(Optional.empty(), Optional.of(new BytesValue("crimson", false)))),
                        List.of(new SortValue(Optional.empty(), Optional.of(new BytesValue("red", false)))));
        assertThat(byRank.hits()).extracting(Hit::docId).containsExactly(2, 0);
        assertThat(byRank.hits().getFirst().sortValues())
                .containsExactly(new SortValue(Optional.of("70"), Optional.empty()));
        // The hits keep the scores they have without a sort.
        assertThat(byRank.hits().getLast().score())
                .isEqualTo(byScore.hits().getFirst().score());
    }

    @Test
    void checksTheSortKeys() throws Exception {
        openIndex();

        assertFails(
                () -> startSearch.handle(sorted(classic("red"), fieldKey("nothing", SortType.LONG, false))),
                ErrorCode.FIELD_NOT_FOUND);
        assertThatThrownBy(() -> startSearch.handle(sorted(classic("red"), fieldKey("body", SortType.STRING, false))))
                .isInstanceOfSatisfying(
                        RpcException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_PARAMS))
                .hasMessage("Invalid params: the field 'body' has no doc values to sort by");
        assertFails(
                () -> startSearch.handle(sorted(classic("red"), fieldKey("rank", SortType.STRING, false))),
                ErrorCode.INVALID_PARAMS);
        assertThatThrownBy(() -> startSearch.handle(sorted(
                        classic("red"),
                        new SortKey(
                                Optional.of(new FieldSort(
                                        "rank", SortType.LONG, false, SortSelector.MAX, SortMissing.DEFAULT)),
                                Optional.empty(),
                                Optional.empty()))))
                .isInstanceOfSatisfying(
                        RpcException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_PARAMS))
                .hasMessage("Invalid params: the field 'rank' has single-valued NUMERIC doc values, which can't"
                        + " select MAX");
        assertFails(
                () -> startSearch.handle(sorted(
                        classic("red"),
                        new SortKey(
                                Optional.empty(), Optional.of(new ScoreSort(false)), Optional.of(new DocSort(false))))),
                ErrorCode.INVALID_PARAMS);
        assertFails(
                () -> startSearch.handle(
                        sorted(classic("red"), new SortKey(Optional.empty(), Optional.empty(), Optional.empty()))),
                ErrorCode.INVALID_PARAMS);
    }

    @Test
    void sortsByScoreAndDocumentId() throws Exception {
        openIndex();

        SearchResults lowestFirst = search(sorted(
                classic("red"), new SortKey(Optional.empty(), Optional.of(new ScoreSort(true)), Optional.empty())));
        SearchResults highestId = search(sorted(
                classic("red"), new SortKey(Optional.empty(), Optional.empty(), Optional.of(new DocSort(true)))));

        assertThat(lowestFirst.hits()).extracting(Hit::docId).containsExactly(2, 0);
        assertThat(lowestFirst.hits().getFirst().sortValues())
                .containsExactly(new SortValue(
                        Optional.of(Float.toString(lowestFirst.hits().getFirst().score())), Optional.empty()));
        assertThat(highestId.hits()).extracting(Hit::docId).containsExactly(2, 0);
        assertThat(highestId.hits().getFirst().sortValues())
                .containsExactly(new SortValue(Optional.of("2"), Optional.empty()));
    }

    @Test
    void scoresAndExplainsWithTheSimilarity() throws Exception {
        openIndex();
        SearchQuery query = term("body", new BytesValue("red", false));

        SearchResults results = search(new StartSearchParams(query, BOOLEAN, List.of(), 0, 10, true, Optional.empty()));

        assertThat(results.hits()).extracting(Hit::score).containsExactly(1f, 1f);
        assertThat(explain(new StartExplainParams(query, BOOLEAN, 0)).value()).isEqualTo("1.0");
        Similarity classic = new Similarity(Optional.empty(), Optional.of(new ClassicSimilarity()), Optional.empty());
        assertThat(explain(new StartExplainParams(query, classic, 0)).description())
                .contains("ClassicSimilarity");
    }

    @Test
    void searchesForDocumentsLikeOne() throws Exception {
        openIndex();
        SearchQuery like = moreLikeThis(0, List.of("body"), 25);

        SearchResults results = search(params(like, 0, 10, Optional.of(List.of("id"))));

        assertThat(results.query())
                .isEqualTo("like(doc 0, fields [body], minTermFreq 1, minDocFreq 1, maxQueryTerms 25)");
        // body is stored without a term vector: its text is analyzed.
        assertThat(results.rewrittenQuery()).isEqualTo("body:red");
        assertThat(results.hits()).extracting(Hit::docId).containsExactly(0, 2);

        Explanation explanation = explain(new StartExplainParams(like, BM25, 2));
        assertThat(explanation.match()).isTrue();
        assertThat(Float.parseFloat(explanation.value()))
                .isEqualTo(results.hits().get(1).score());
    }

    @Test
    void checksTheMoreLikeThisParams() throws Exception {
        openIndex();

        assertFails(
                () -> startSearch.handle(params(moreLikeThis(3, List.of("body"), 25), 0, 10, Optional.empty())),
                ErrorCode.DOC_NOT_FOUND);
        assertFails(
                () -> startExplain.handle(new StartExplainParams(moreLikeThis(-1, List.of("body"), 25), BM25, 0)),
                ErrorCode.DOC_NOT_FOUND);
        assertFails(
                () -> startSearch.handle(params(moreLikeThis(0, List.of("nope"), 25), 0, 10, Optional.empty())),
                ErrorCode.FIELD_NOT_FOUND);
        // Doc values and points only: no terms.
        for (String field : List.of("rank", "size")) {
            assertThatThrownBy(() ->
                            startSearch.handle(params(moreLikeThis(0, List.of(field), 25), 0, 10, Optional.empty())))
                    .isInstanceOfSatisfying(
                            RpcException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_PARAMS))
                    .hasMessageContaining("'" + field + "' is not indexed");
        }
        for (SearchQuery invalid : List.of(
                moreLikeThis(0, List.of(), 25),
                moreLikeThis(0, List.of("body", "body"), 25),
                moreLikeThis(0, List.of("body"), 0),
                moreLikeThis(0, List.of("body"), MoreLikeThisQuery.MAX_QUERY_TERMS + 1))) {
            assertFails(() -> startSearch.handle(params(invalid, 0, 10, Optional.empty())), ErrorCode.INVALID_PARAMS);
        }
        assertThat(search(params(
                                moreLikeThis(0, List.of("body"), MoreLikeThisQuery.MAX_QUERY_TERMS),
                                0,
                                10,
                                Optional.empty()))
                        .hits())
                .isNotEmpty();
    }

    @Test
    void checksTheSimilarity() throws Exception {
        openIndex();
        SearchQuery query = classic("red");
        Similarity both = new Similarity(
                Optional.of(new Bm25Similarity(1.2f, 0.75f)), Optional.of(new ClassicSimilarity()), Optional.empty());
        Similarity neither = new Similarity(Optional.empty(), Optional.empty(), Optional.empty());
        Similarity badB = new Similarity(Optional.of(new Bm25Similarity(1.2f, 2f)), Optional.empty(), Optional.empty());

        for (Similarity similarity : List.of(both, neither, badB)) {
            assertFails(
                    () -> startSearch.handle(
                            new StartSearchParams(query, similarity, List.of(), 0, 10, true, Optional.empty())),
                    ErrorCode.INVALID_PARAMS);
            assertFails(
                    () -> startExplain.handle(new StartExplainParams(query, similarity, 0)), ErrorCode.INVALID_PARAMS);
        }
    }

    @Test
    void failsWithoutOpenIndex() {
        assertFails(() -> startSearch.handle(params(classic("red"), 0, 10, Optional.empty())), ErrorCode.NO_INDEX_OPEN);
        assertFails(
                () -> startExplain.handle(new StartExplainParams(classic("red"), BM25, 0)), ErrorCode.NO_INDEX_OPEN);
    }

    @Test
    void explainsADocument() throws Exception {
        openIndex();
        SearchQuery query = term("body", new BytesValue("red", false));

        Explanation match = explain(new StartExplainParams(query, BM25, 0));
        assertThat(match.match()).isTrue();
        assertThat(match.description()).startsWith("weight(body:red in 0)");
        // Counts are longs and come as whole numbers; the score is a float.
        assertThat(flatten(match)).anySatisfy(node -> {
            assertThat(node.description()).startsWith("n, number of documents containing term");
            assertThat(node.value()).isEqualTo("2");
        });
        assertThat(match.value()).contains(".");

        Explanation miss = explain(new StartExplainParams(query, BM25, 1));
        assertThat(miss.match()).isFalse();
    }

    @Test
    void explainsOnlyDocumentsOfTheIndex() throws Exception {
        openIndex();

        assertFails(
                () -> startExplain.handle(new StartExplainParams(classic("red"), BM25, 3)), ErrorCode.DOC_NOT_FOUND);
        assertThat(errorCodeOf("startExplain.error-doc-not-found.json")).isEqualTo(ErrorCode.DOC_NOT_FOUND);
    }

    @Test
    void parsesWithTheQuerysAnalyzer() throws Exception {
        openIndex();

        // The whitespace tokenizer keeps the case, and the index has only "red".
        SearchResults upper = search(params(classic("RED", whitespace()), 0, 10, Optional.empty()));
        assertThat(upper.query()).isEqualTo("body:RED");
        assertThat(upper.hits()).isEmpty();
        assertThat(search(params(classic("red", whitespace()), 0, 10, Optional.empty()))
                        .hits())
                .isNotEmpty();
    }

    @Test
    void failsForAnAnalyzerThatCantBeBuilt() throws Exception {
        openIndex();
        var broken = new Analyzer(
                Optional.empty(),
                Optional.empty(),
                Optional.of(new CustomAnalyzer(
                        List.of(), new AnalysisComponent("nope", Map.of()), List.of(), Optional.empty())),
                Optional.empty(),
                Optional.empty());
        var both = new Analyzer(
                STANDARD.standard(), Optional.empty(), whitespace().custom(), Optional.empty(), Optional.empty());

        assertFails(
                () -> startSearch.handle(params(classic("red", broken), 0, 10, Optional.empty())),
                ErrorCode.INVALID_ANALYZER);
        assertFails(
                () -> startSearch.handle(params(moreLikeThis(0, List.of("body"), 25, broken), 0, 10, Optional.empty())),
                ErrorCode.INVALID_ANALYZER);
        assertFails(
                () -> startExplain.handle(new StartExplainParams(classic("red", both), BM25, 0)),
                ErrorCode.INVALID_PARAMS);
    }

    @Test
    void searchAndExplainJobsAreKeptApart() throws Exception {
        openIndex();

        long jobId = startSearch
                .handle(params(classic("red"), 0, 10, Optional.empty()))
                .jobId();

        assertFails(() -> getExplain.handle(new JobParams(jobId)), ErrorCode.JOB_NOT_FOUND);
        assertThat(awaitSearch(jobId).results()).isPresent();
    }

    private static SearchQuery classic(String expression) {
        return classic(expression, DEFAULTS, 2, false, false);
    }

    private static SearchQuery classic(String expression, Analyzer analyzer) {
        return classic(expression, DEFAULTS, 2, false, false, analyzer);
    }

    private static SearchQuery classic(
            String expression,
            ParserOptions options,
            int fuzzyMaxEdits,
            boolean splitOnWhitespace,
            boolean autoGeneratePhraseQueries) {
        return classic(expression, options, fuzzyMaxEdits, splitOnWhitespace, autoGeneratePhraseQueries, STANDARD);
    }

    private static SearchQuery classic(
            String expression,
            ParserOptions options,
            int fuzzyMaxEdits,
            boolean splitOnWhitespace,
            boolean autoGeneratePhraseQueries,
            Analyzer analyzer) {
        return new SearchQuery(
                Optional.of(new ClassicQuery(
                        expression,
                        "body",
                        QueryOperator.OR,
                        options,
                        fuzzyMaxEdits,
                        splitOnWhitespace,
                        autoGeneratePhraseQueries,
                        analyzer)),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static SearchQuery standard(String expression, ParserOptions options, List<PointField> points) {
        return new SearchQuery(
                Optional.empty(),
                Optional.of(new StandardQuery(expression, "body", QueryOperator.OR, options, points, STANDARD)),
                Optional.empty(),
                Optional.empty());
    }

    private static SearchQuery term(String field, BytesValue term) {
        return new SearchQuery(
                Optional.empty(), Optional.empty(), Optional.of(new TermQuery(field, term)), Optional.empty());
    }

    private static SearchQuery moreLikeThis(int docId, List<String> fields, int maxQueryTerms) {
        return moreLikeThis(docId, fields, maxQueryTerms, STANDARD);
    }

    private static SearchQuery moreLikeThis(int docId, List<String> fields, int maxQueryTerms, Analyzer analyzer) {
        return new SearchQuery(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(new MoreLikeThisQuery(
                        docId, fields, 1, 1, OptionalInt.empty(), maxQueryTerms, false, analyzer)));
    }

    private static Analyzer whitespace() {
        return new Analyzer(
                Optional.empty(),
                Optional.empty(),
                Optional.of(new CustomAnalyzer(
                        List.of(), new AnalysisComponent("whitespace", Map.of()), List.of(), Optional.empty())),
                Optional.empty(),
                Optional.empty());
    }

    private static StartSearchParams params(SearchQuery query, int offset, int limit, Optional<List<String>> fields) {
        return new StartSearchParams(query, BM25, List.of(), offset, limit, true, fields);
    }

    private static SortKey fieldKey(String field, SortType type, boolean reverse) {
        return new SortKey(
                Optional.of(new FieldSort(field, type, reverse, SortSelector.MIN, SortMissing.DEFAULT)),
                Optional.empty(),
                Optional.empty());
    }

    private static StartSearchParams sorted(SearchQuery query, SortKey... sort) {
        return new StartSearchParams(query, BM25, List.of(sort), 0, 10, true, Optional.of(List.of()));
    }

    private static List<Explanation> flatten(Explanation explanation) {
        List<Explanation> all = new java.util.ArrayList<>(List.of(explanation));
        explanation.details().forEach(detail -> all.addAll(flatten(detail)));
        return all;
    }

    private SearchResults search(StartSearchParams params) throws Exception {
        return awaitSearch(startSearch.handle(params).jobId()).results().orElseThrow();
    }

    private GetSearchResult awaitSearch(long jobId) throws Exception {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            GetSearchResult result = getSearch.handle(new JobParams(jobId));
            if (result.results().isPresent()) {
                return result;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("job " + jobId + " did not finish");
    }

    private Explanation explain(StartExplainParams params) throws Exception {
        long jobId = startExplain.handle(params).jobId();
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            GetExplainResult result = getExplain.handle(new JobParams(jobId));
            if (result.explanation().isPresent()) {
                return result.explanation().get();
            }
            Thread.sleep(5);
        }
        throw new AssertionError("job " + jobId + " did not finish");
    }

    /**
     * One segment: "red" (id 1), "blue" (id 2) with the binary key 0xff00, and a long "red …" (id 3);
     * each with the int point {@code size}, 10 times its id, and the numeric doc values {@code rank},
     * 100 minus that; and the sorted doc values {@code color}: "red" for id 1, "crimson" for id 3.
     */
    private void openIndex() throws Exception {
        Path dir = tmp.resolve("index");
        try (FSDirectory directory = FSDirectory.open(dir);
                IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig())) {
            int id = 1;
            for (String body : List.of("red", "blue", LONG_BODY)) {
                Document doc = new Document();
                doc.add(new StringField("id", Integer.toString(id), Field.Store.YES));
                doc.add(new TextField("body", body, Field.Store.YES));
                doc.add(new IntPoint("size", id * 10));
                doc.add(new NumericDocValuesField("rank", 100 - id * 10));
                if (id != 2) {
                    doc.add(new SortedDocValuesField("color", new BytesRef(id == 1 ? "red" : "crimson")));
                }
                if (id == 2) {
                    doc.add(new StringField("key", new BytesRef(new byte[] {(byte) 0xff, 0}), Field.Store.NO));
                }
                writer.addDocument(doc);
                id++;
            }
        }
        currentIndex.replace(ReadOnlyIndex.open(dir));
    }

    private static void assertFails(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        RpcException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    private static ErrorCode errorCodeOf(String fixture) throws IOException {
        String json = Files.readString(Path.of(System.getProperty("leia.protocol.dir"))
                .resolve("fixtures")
                .resolve(fixture));
        Response<?> response = ProtocolJson.mapper().readValue(json, ProtocolJson.responseType(Object.class));
        return response.error().orElseThrow().code();
    }
}
