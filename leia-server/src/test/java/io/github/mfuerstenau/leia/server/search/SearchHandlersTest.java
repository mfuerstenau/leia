package io.github.mfuerstenau.leia.server.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.github.mfuerstenau.leia.core.ReadOnlyIndex;
import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.analysis.AnalysisComponent;
import io.github.mfuerstenau.leia.protocol.analysis.Analyzer;
import io.github.mfuerstenau.leia.protocol.analysis.CustomAnalyzer;
import io.github.mfuerstenau.leia.protocol.analysis.StandardAnalyzer;
import io.github.mfuerstenau.leia.protocol.document.BytesValue;
import io.github.mfuerstenau.leia.protocol.document.KnnVector;
import io.github.mfuerstenau.leia.protocol.document.StoredType;
import io.github.mfuerstenau.leia.protocol.document.StoredValue;
import io.github.mfuerstenau.leia.protocol.index.VectorEncoding;
import io.github.mfuerstenau.leia.protocol.job.JobParams;
import io.github.mfuerstenau.leia.protocol.search.Bm25Similarity;
import io.github.mfuerstenau.leia.protocol.search.BooleanSimilarity;
import io.github.mfuerstenau.leia.protocol.search.ClassicQuery;
import io.github.mfuerstenau.leia.protocol.search.ClassicSimilarity;
import io.github.mfuerstenau.leia.protocol.search.DateResolution;
import io.github.mfuerstenau.leia.protocol.search.DocSort;
import io.github.mfuerstenau.leia.protocol.search.DocValuesNumber;
import io.github.mfuerstenau.leia.protocol.search.DocValuesQuery;
import io.github.mfuerstenau.leia.protocol.search.DocValuesRange;
import io.github.mfuerstenau.leia.protocol.search.DocumentHighlight;
import io.github.mfuerstenau.leia.protocol.search.Explanation;
import io.github.mfuerstenau.leia.protocol.search.FieldDateResolution;
import io.github.mfuerstenau.leia.protocol.search.FieldSort;
import io.github.mfuerstenau.leia.protocol.search.GetExplainResult;
import io.github.mfuerstenau.leia.protocol.search.GetHighlightResult;
import io.github.mfuerstenau.leia.protocol.search.GetSearchResult;
import io.github.mfuerstenau.leia.protocol.search.HighlightOptions;
import io.github.mfuerstenau.leia.protocol.search.HighlightPassage;
import io.github.mfuerstenau.leia.protocol.search.Hit;
import io.github.mfuerstenau.leia.protocol.search.HitField;
import io.github.mfuerstenau.leia.protocol.search.HitHighlight;
import io.github.mfuerstenau.leia.protocol.search.KnnQuery;
import io.github.mfuerstenau.leia.protocol.search.LikeTerm;
import io.github.mfuerstenau.leia.protocol.search.MatchSpan;
import io.github.mfuerstenau.leia.protocol.search.MoreLikeThisQuery;
import io.github.mfuerstenau.leia.protocol.search.OffsetSource;
import io.github.mfuerstenau.leia.protocol.search.ParserOptions;
import io.github.mfuerstenau.leia.protocol.search.PointField;
import io.github.mfuerstenau.leia.protocol.search.PointType;
import io.github.mfuerstenau.leia.protocol.search.QueryOperator;
import io.github.mfuerstenau.leia.protocol.search.ScoreSort;
import io.github.mfuerstenau.leia.protocol.search.SearchQuery;
import io.github.mfuerstenau.leia.protocol.search.SearchResults;
import io.github.mfuerstenau.leia.protocol.search.Similarity;
import io.github.mfuerstenau.leia.protocol.search.Snippet;
import io.github.mfuerstenau.leia.protocol.search.SortKey;
import io.github.mfuerstenau.leia.protocol.search.SortMissing;
import io.github.mfuerstenau.leia.protocol.search.SortSelector;
import io.github.mfuerstenau.leia.protocol.search.SortType;
import io.github.mfuerstenau.leia.protocol.search.SortValue;
import io.github.mfuerstenau.leia.protocol.search.StandardQuery;
import io.github.mfuerstenau.leia.protocol.search.StartExplainParams;
import io.github.mfuerstenau.leia.protocol.search.StartHighlightParams;
import io.github.mfuerstenau.leia.protocol.search.StartSearchParams;
import io.github.mfuerstenau.leia.protocol.search.TermQuery;
import io.github.mfuerstenau.leia.protocol.search.TotalHitsRelation;
import io.github.mfuerstenau.leia.protocol.search.ValueHighlight;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.filter.Filters;
import io.github.mfuerstenau.leia.server.index.CurrentIndex;
import io.github.mfuerstenau.leia.server.job.Jobs;
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
import org.apache.lucene.document.KnnByteVectorField;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.VectorSimilarityFunction;
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
    private static final ParserOptions DEFAULTS = options(false, 0, 0, true);
    private static final Analyzer STANDARD = new Analyzer(
            Optional.of(new StandardAnalyzer()),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            List.of(),
            List.of());
    private static final Similarity BM25 =
            new Similarity(Optional.of(new Bm25Similarity(1.2f, 0.75f)), Optional.empty(), Optional.empty());
    private static final Optional<List<String>> NONE = Optional.of(List.of());
    private static final Similarity BOOLEAN =
            new Similarity(Optional.empty(), Optional.empty(), Optional.of(new BooleanSimilarity()));

    @TempDir
    Path tmp;

    private final Jobs jobs = new Jobs();
    private final CurrentIndex currentIndex = new CurrentIndex(jobs, new Filters());
    private final StartSearchHandler startSearch = new StartSearchHandler(currentIndex, jobs);
    private final GetSearchHandler getSearch = new GetSearchHandler(jobs);
    private final StartExplainHandler startExplain = new StartExplainHandler(currentIndex, jobs);
    private final GetExplainHandler getExplain = new GetExplainHandler(jobs);
    private final StartHighlightHandler startHighlight = new StartHighlightHandler(currentIndex, jobs);
    private final GetHighlightHandler getHighlight = new GetHighlightHandler(jobs);

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
    void highlightsTheHits() throws Exception {
        openIndex();

        SearchResults results = search(
                new StartSearchParams(classic("red"), BM25, List.of(), 0, 10, true, Optional.empty(), highlight(3)));

        assertThat(results.hits()).extracting(Hit::docId).containsExactly(0, 2);
        HitHighlight red = results.hits().getFirst().highlights().getFirst();
        assertThat(red.field()).isEqualTo("body");
        // TextField has no offsets in its postings.
        assertThat(red.offsetSource()).isEqualTo(OffsetSource.ANALYSIS);
        assertThat(red.passages()).hasSize(1);
        HighlightPassage passage = red.passages().getFirst();
        assertThat(passage.valueIndex()).isZero();
        assertThat(passage.snippet()).isEqualTo(new Snippet(0, "red"));
        assertThat(passage.spans()).containsExactly(new MatchSpan(0, 3));
        // The long value's passage is cut, unlike its stored value.
        HighlightPassage cut =
                results.hits().get(1).highlights().getFirst().passages().getFirst();
        assertThat(cut.snippet().text()).hasSize(HighlightOptions.MAX_PASSAGE_CHARS);
        assertThat(cut.spans()).containsExactly(new MatchSpan(0, 3));
    }

    @Test
    void highlightsNothingForKnnQueriesAndChecksThePassages() throws Exception {
        openIndex();

        SearchResults results = search(new StartSearchParams(
                knn("vec", OptionalInt.empty(), floats(1, 0), OptionalInt.of(2), false),
                BM25,
                List.of(),
                0,
                10,
                true,
                Optional.empty(),
                highlight(3)));

        assertThat(results.hits())
                .isNotEmpty()
                .allSatisfy(hit -> assertThat(hit.highlights()).isEmpty());
        for (int passages : new int[] {0, HighlightOptions.MAX_PASSAGES + 1}) {
            assertFails(
                    () -> startSearch.handle(new StartSearchParams(
                            classic("red"), BM25, List.of(), 0, 10, true, Optional.empty(), highlight(passages))),
                    ErrorCode.INVALID_PARAMS);
        }
    }

    @Test
    void highlightsEveryMatchOfADocumentsField() throws Exception {
        openIndex();

        DocumentHighlight highlight = highlight(new StartHighlightParams(classic("red"), 2, "body", options(1)));

        assertThat(highlight)
                .isEqualTo(new DocumentHighlight(
                        OffsetSource.ANALYSIS, List.of(new ValueHighlight(0, 1, List.of(new MatchSpan(0, 3)))), false));
        assertThat(highlight(new StartHighlightParams(classic("blue"), 2, "body", options(1)))
                        .values())
                .isEmpty();
        assertThat(highlight(new StartHighlightParams(
                        knn("vec", OptionalInt.empty(), floats(1, 0), OptionalInt.of(2), false),
                        0,
                        "body",
                        options(1))))
                .isEqualTo(new DocumentHighlight(OffsetSource.NONE, List.of(), false));
    }

    @Test
    void checksTheHighlightParams() throws Exception {
        assertFails(
                () -> startHighlight.handle(new StartHighlightParams(classic("red"), 0, "body", options(1))),
                ErrorCode.NO_INDEX_OPEN);
        openIndex();

        assertFails(
                () -> startHighlight.handle(new StartHighlightParams(classic("red"), 3, "body", options(1))),
                ErrorCode.DOC_NOT_FOUND);
        assertFails(
                () -> startHighlight.handle(new StartHighlightParams(classic("red"), 0, "nope", options(1))),
                ErrorCode.FIELD_NOT_FOUND);
        assertFails(
                () -> startHighlight.handle(new StartHighlightParams(classic("body:(red"), 0, "body", options(1))),
                ErrorCode.INVALID_QUERY);
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
    }

    @Test
    void checksTheFields() throws Exception {
        openIndex();
        SearchQuery unknownDefault = new SearchQuery(
                Optional.of(new ClassicQuery("red", "bdy", QueryOperator.OR, DEFAULTS, 2, false, false, STANDARD)),
                Optional.empty(),
                Optional.empty(),
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
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
        SearchQuery neither = new SearchQuery(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());

        assertFails(() -> startSearch.handle(params(both, 0, 10, Optional.empty())), ErrorCode.INVALID_PARAMS);
        assertFails(() -> startSearch.handle(params(neither, 0, 10, Optional.empty())), ErrorCode.INVALID_PARAMS);
        assertFails(
                () -> startSearch.handle(params(term("key", new BytesValue("zz", true)), 0, 10, Optional.empty())),
                ErrorCode.INVALID_PARAMS);
    }

    @Test
    void searchesByDocValues() throws Exception {
        openIndex();

        SearchResults ranks = search(params(
                docValues("rank", DocValuesNumber.LONG, Optional.empty(), Optional.of(range("75", null))),
                0,
                10,
                Optional.of(List.of())));
        SearchResults colors = search(params(
                docValues("color", null, Optional.of(List.of(text("crimson"), text("blue"))), Optional.empty()),
                0,
                10,
                Optional.of(List.of())));

        // rank: 90, 80, 70
        assertThat(ranks.hits()).extracting(Hit::docId).containsExactly(0, 1);
        assertThat(ranks.query()).isEqualTo("rank:[75 TO 9223372036854775807]");
        assertThat(colors.hits()).extracting(Hit::docId).containsExactly(2);
    }

    @Test
    void searchesByAPrefixOfDocValues() throws Exception {
        openIndex();

        SearchResults cr = search(params(docValuesPrefix("color", null, text("cr")), 0, 10, Optional.of(List.of())));
        SearchResults any = search(params(docValuesPrefix("color", null, text("")), 0, 10, Optional.of(List.of())));
        SearchResults hex = search(
                params(docValuesPrefix("color", null, new BytesValue("72", true)), 0, 10, Optional.of(List.of())));

        // color: "red" (doc 0), "crimson" (doc 2)
        assertThat(cr.hits()).extracting(Hit::docId).containsExactly(2);
        assertThat(cr.query()).isEqualTo("color:[[63 72] TO [63 73]}");
        assertThat(any.hits()).extracting(Hit::docId).containsExactly(0, 2);
        assertThat(hex.hits()).extracting(Hit::docId).containsExactly(0);
    }

    @Test
    void checksTheDocValuesQuery() throws Exception {
        openIndex();
        Optional<List<BytesValue>> one = Optional.of(List.of(text("80")));

        assertFails(
                () -> search(params(docValues("nope", null, one, Optional.empty()), 0, 10, Optional.empty())),
                ErrorCode.FIELD_NOT_FOUND);
        for (SearchQuery query : List.of(
                // no doc values
                docValues("body", null, one, Optional.empty()),
                // numbers without an encoding, bytes with one
                docValues("rank", null, one, Optional.empty()),
                docValues("color", DocValuesNumber.LONG, one, Optional.empty()),
                // not exactly one of values, range and prefix
                docValues("rank", DocValuesNumber.LONG, one, Optional.of(range("1", "2"))),
                docValues("rank", DocValuesNumber.LONG, Optional.empty(), Optional.empty()),
                docValues("rank", DocValuesNumber.LONG, Optional.of(List.of()), Optional.empty()),
                // values that don't parse
                docValues("rank", DocValuesNumber.LONG, Optional.of(List.of(text("8x"))), Optional.empty()),
                docValues("rank", DocValuesNumber.DOUBLE, Optional.of(List.of(text("NaN"))), Optional.empty()),
                docValues(
                        "rank",
                        DocValuesNumber.LONG,
                        Optional.of(List.of(new BytesValue("50", true))),
                        Optional.empty()),
                docValues("color", null, Optional.of(List.of(new BytesValue("zz", true))), Optional.empty()),
                // a prefix: with values, of numbers, in bad hex
                new SearchQuery(
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(new DocValuesQuery(
                                "color", Optional.empty(), one, Optional.empty(), Optional.of(text("r")))),
                        Optional.empty()),
                docValuesPrefix("rank", DocValuesNumber.LONG, text("8")),
                docValuesPrefix("rank", null, text("8")),
                docValuesPrefix("color", null, new BytesValue("7", true)))) {
            assertFails(() -> startSearch.handle(params(query, 0, 10, Optional.empty())), ErrorCode.INVALID_PARAMS);
        }
        assertThatThrownBy(() -> startSearch.handle(
                        params(docValues("rank", null, one, Optional.empty()), 0, 10, Optional.empty())))
                .hasMessage("Invalid params: the field 'rank' has NUMERIC doc values, which hold numbers: say how"
                        + " they are encoded");
    }

    @Test
    void searchesByKnn() throws Exception {
        openIndex();

        SearchResults approximate =
                search(params(knn("vec", OptionalInt.empty(), floats(3, 0), OptionalInt.of(1), false), 0, 10, NONE));
        SearchResults exact =
                search(params(knn("vec", OptionalInt.empty(), floats(3, 0), OptionalInt.empty(), true), 0, 10, NONE));
        SearchResults fromDocument =
                search(params(knn("bvec", OptionalInt.of(0), null, OptionalInt.empty(), true), 0, 10, NONE));

        // vec: [1, 0] (doc 0), none (doc 1), [3, 0] (doc 2)
        assertThat(approximate.hits()).extracting(Hit::docId).containsExactly(2);
        assertThat(approximate.hits().getFirst().score()).isEqualTo(1f);
        assertThat(approximate.query()).isEqualTo("KnnFloatVectorQuery:vec[3.0,...][1]");
        assertThat(exact.hits()).extracting(Hit::docId).containsExactly(2, 0);
        assertThat(exact.totalHits()).isEqualTo(2);
        assertThat(exact.query()).isEqualTo("ExactKnnQuery:vec[3.0,...]");
        // bvec: [1, 1], [2, 1], [3, 1], cosine; every document has one.
        assertThat(fromDocument.hits()).extracting(Hit::docId).containsExactly(0, 1, 2);
        assertThat(fromDocument.hits().getFirst().score()).isEqualTo(1f);
    }

    @Test
    void searchesByKnnThroughAFilter() throws Exception {
        openIndex();
        // body: "red" (doc 0), "blue" (doc 1), "red …" (doc 2)
        ClassicQuery red = new ClassicQuery("red", "body", QueryOperator.OR, DEFAULTS, 2, true, false, STANDARD);

        for (boolean exact : List.of(true, false)) {
            SearchQuery query = new SearchQuery(
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(new KnnQuery(
                            "bvec",
                            OptionalInt.of(1),
                            Optional.empty(),
                            exact ? OptionalInt.empty() : OptionalInt.of(3),
                            exact,
                            Optional.of(red))));
            assertThat(search(params(query, 0, 10, NONE)).hits())
                    .extracting(Hit::docId)
                    .containsExactlyInAnyOrder(0, 2);
        }
    }

    @Test
    void explainsAKnnHit() throws Exception {
        openIndex();

        for (boolean exact : List.of(true, false)) {
            SearchQuery query =
                    knn("vec", OptionalInt.of(0), null, exact ? OptionalInt.empty() : OptionalInt.of(2), exact);
            assertThat(explain(new StartExplainParams(query, BM25, 0)).match()).isTrue();
            assertThat(explain(new StartExplainParams(query, BM25, 1)).match()).isFalse();
        }
    }

    @Test
    void checksTheKnnQuery() throws Exception {
        openIndex();
        OptionalInt none = OptionalInt.empty();
        OptionalInt ten = OptionalInt.of(10);

        assertFails(
                () -> search(params(knn("nope", none, floats(1, 0), ten, false), 0, 10, NONE)),
                ErrorCode.FIELD_NOT_FOUND);
        assertFails(
                () -> search(params(knn("vec", OptionalInt.of(3), null, ten, false), 0, 10, NONE)),
                ErrorCode.DOC_NOT_FOUND);
        for (SearchQuery query : List.of(
                // no vectors
                knn("body", none, floats(1, 0), ten, false),
                // not exactly one target
                knn("vec", OptionalInt.of(0), floats(1, 0), ten, false),
                knn("vec", none, null, ten, false),
                // k: missing, out of range, or with an exact query
                knn("vec", none, floats(1, 0), none, false),
                knn("vec", none, floats(1, 0), OptionalInt.of(0), false),
                knn("vec", none, floats(1, 0), OptionalInt.of(KnnQuery.MAX_K + 1), false),
                knn("vec", none, floats(1, 0), ten, true),
                // a target that doesn't fit
                knn("vec", none, floats(1, 0, 0), ten, false),
                knn("vec", none, floats(1, Float.NaN), ten, false),
                knn(
                        "vec",
                        none,
                        new KnnVector(VectorEncoding.BYTE, List.of(), List.of((byte) 1, (byte) 0)),
                        ten,
                        false),
                knn(
                        "bvec",
                        none,
                        new KnnVector(VectorEncoding.BYTE, List.of(), List.of((byte) 0, (byte) 0)),
                        ten,
                        false),
                // a document without a vector
                knn("vec", OptionalInt.of(1), null, ten, false))) {
            assertFails(() -> startSearch.handle(params(query, 0, 10, NONE)), ErrorCode.INVALID_PARAMS);
        }
        assertThatThrownBy(
                        () -> startSearch.handle(params(knn("vec", OptionalInt.of(1), null, ten, false), 0, 10, NONE)))
                .hasMessage("Invalid params: document 1 has no vector in the field 'vec'");
        assertThatThrownBy(() -> startSearch.handle(params(knn("vec", none, floats(1, 0, 0), ten, false), 0, 10, NONE)))
                .hasMessage("Invalid params: the field 'vec' has vectors of 2 dimensions, not 3");
        SearchQuery badFilter = new SearchQuery(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(new KnnQuery(
                        "vec",
                        OptionalInt.of(0),
                        Optional.empty(),
                        ten,
                        false,
                        Optional.of(new ClassicQuery(
                                "red AND (", "body", QueryOperator.OR, DEFAULTS, 2, true, false, STANDARD)))));
        assertFails(() -> startSearch.handle(params(badFilter, 0, 10, NONE)), ErrorCode.INVALID_QUERY);
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
        ParserOptions options = options(true, 2, 1, true);

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
                classic("red", options(false, -1, 0, true), 2, false, false),
                standard("red", options(false, 0, -1, true), List.of()),
                classic("red", DEFAULTS, 3, false, false),
                classic("red", DEFAULTS, 2, false, true))) {
            assertFails(() -> startSearch.handle(params(query, 0, 10, Optional.empty())), ErrorCode.INVALID_PARAMS);
        }
    }

    @Test
    void readsRangeBoundsAsDates() throws Exception {
        openIndex();
        ParserOptions dates = dates(Optional.of(DateResolution.DAY), Optional.of("en-US"), Optional.of("UTC"));

        assertThat(search(params(
                                classic("published:[1/1/26 TO 1/31/26]", dates, 2, false, false),
                                0,
                                10,
                                Optional.empty()))
                        .query())
                .isEqualTo("published:[20260101 TO 20260131]");
        ParserOptions berlin =
                dates(Optional.of(DateResolution.DAY), Optional.of("de-DE"), Optional.of("Europe/Berlin"));
        assertThat(search(params(
                                standard("published:[01.01.26 TO 31.01.26]", berlin, List.of()),
                                0,
                                10,
                                Optional.empty()))
                        .query())
                .isEqualTo("published:[20251231 TO 20260131]");
    }

    @Test
    void rejectsBadLocalesAndTimeZones() throws Exception {
        openIndex();

        for (ParserOptions options : List.of(
                dates(Optional.empty(), Optional.of("en_US"), Optional.empty()),
                dates(Optional.empty(), Optional.of(" "), Optional.empty()),
                dates(Optional.empty(), Optional.empty(), Optional.of("Mars/Olympus")),
                dates(Optional.empty(), Optional.empty(), Optional.of("")))) {
            assertFails(
                    () -> startSearch.handle(params(classic("red", options, 2, false, false), 0, 10, Optional.empty())),
                    ErrorCode.INVALID_PARAMS);
        }
    }

    @Test
    void aFieldsOwnDateResolutionWins() throws Exception {
        openIndex();
        ParserOptions hours = dates(
                Optional.of(DateResolution.DAY),
                List.of(new FieldDateResolution("id", DateResolution.HOUR)),
                Optional.of("en-US"),
                Optional.of("UTC"));

        assertThat(search(params(
                                standard("+id:[1/1/26 TO 1/2/26] +body:[1/1/26 TO 1/31/26]", hours, List.of()),
                                0,
                                10,
                                Optional.empty()))
                        .query())
                .isEqualTo("+id:[2026010100 TO 2026010223] +body:[20260101 TO 20260131]");
    }

    @Test
    void rejectsDateResolutionsOfUnknownOrRepeatedFields() throws Exception {
        openIndex();
        ParserOptions unknown = dates(
                Optional.empty(),
                List.of(new FieldDateResolution("nothing", DateResolution.DAY)),
                Optional.empty(),
                Optional.empty());
        ParserOptions twice = dates(
                Optional.empty(),
                List.of(
                        new FieldDateResolution("id", DateResolution.DAY),
                        new FieldDateResolution("id", DateResolution.HOUR)),
                Optional.empty(),
                Optional.empty());

        assertFails(
                () -> startSearch.handle(params(classic("red", unknown, 2, false, false), 0, 10, Optional.empty())),
                ErrorCode.FIELD_NOT_FOUND);
        assertFails(
                () -> startSearch.handle(params(standard("red", twice, List.of()), 0, 10, Optional.empty())),
                ErrorCode.INVALID_PARAMS);
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

        SearchResults results = search(
                new StartSearchParams(query, BOOLEAN, List.of(), 0, 10, true, Optional.empty(), Optional.empty()));

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
    void searchesForDocumentsLikeATextAndGivesTheChosenTerms() throws Exception {
        openIndex();
        SearchQuery like = moreLikeText("Red red, blue and green", 0, OptionalInt.empty(), List.of("blue"));

        SearchResults results = search(params(like, 0, 10, Optional.empty()));

        assertThat(results.query())
                .isEqualTo("like(text \"Red red, blue and green\", fields [body], minTermFreq 1, minDocFreq 1, "
                        + "maxQueryTerms 25, stopWords [blue])");
        // "and" and "green" are in no document, and "blue" is a stop word.
        assertThat(results.moreLikeThisTerms().orElseThrow())
                .extracting(LikeTerm::field, LikeTerm::term, LikeTerm::tf, LikeTerm::docFreq)
                .containsExactly(tuple("body", "red", 2, 2));
        LikeTerm red = results.moreLikeThisTerms().orElseThrow().getFirst();
        assertThat(red.score()).isEqualTo(red.tf() * red.idf());
        assertThat(results.hits()).extracting(Hit::docId).containsExactlyInAnyOrder(0, 2);

        Explanation explanation = explain(new StartExplainParams(like, BM25, 2));
        assertThat(Float.parseFloat(explanation.value()))
                .isEqualTo(results.hits().stream()
                        .filter(hit -> hit.docId() == 2)
                        .findFirst()
                        .orElseThrow()
                        .score());

        // Like a document gives the terms too, other queries don't.
        assertThat(search(params(moreLikeThis(0, List.of("body"), 25), 0, 10, Optional.empty()))
                        .moreLikeThisTerms()
                        .orElseThrow())
                .extracting(LikeTerm::term)
                .containsExactly("red");
        assertThat(search(params(term("body", new BytesValue("red", false)), 0, 10, Optional.empty()))
                        .moreLikeThisTerms())
                .isEmpty();
    }

    @Test
    void checksTheMoreLikeTextParams() throws Exception {
        openIndex();
        var neither = moreLikeThis(new MoreLikeThisQuery(
                OptionalInt.empty(),
                Optional.empty(),
                List.of("body"),
                1,
                1,
                OptionalInt.empty(),
                25,
                false,
                STANDARD,
                0,
                OptionalInt.empty(),
                List.of()));
        var both = moreLikeThis(new MoreLikeThisQuery(
                OptionalInt.of(99),
                Optional.of("red"),
                List.of("body"),
                1,
                1,
                OptionalInt.empty(),
                25,
                false,
                STANDARD,
                0,
                OptionalInt.empty(),
                List.of()));
        for (SearchQuery invalid : List.of(neither, both)) {
            assertThatThrownBy(() -> startSearch.handle(params(invalid, 0, 10, Optional.empty())))
                    .isInstanceOfSatisfying(
                            RpcException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_PARAMS))
                    .hasMessageContaining("exactly one of docId and text");
        }
        // 65,536 bytes of UTF-8 are fine, one more is not: ä has two.
        String longest = "ä".repeat(MoreLikeThisQuery.MAX_TEXT_LENGTH / 2);
        assertThat(search(params(moreLikeText(longest, 0, OptionalInt.empty(), List.of()), 0, 10, Optional.empty()))
                        .hits())
                .isEmpty();
        assertThatThrownBy(() -> startSearch.handle(params(
                        moreLikeText(longest + "x", 0, OptionalInt.empty(), List.of()), 0, 10, Optional.empty())))
                .isInstanceOfSatisfying(
                        RpcException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_PARAMS))
                .hasMessageContaining("65537 bytes");
        for (SearchQuery invalid : List.of(
                moreLikeText("red", -1, OptionalInt.empty(), List.of()),
                moreLikeText("red", 0, OptionalInt.of(0), List.of()),
                moreLikeText("red", 5, OptionalInt.of(4), List.of()))) {
            assertFails(() -> startSearch.handle(params(invalid, 0, 10, Optional.empty())), ErrorCode.INVALID_PARAMS);
        }
        // A word length shorter than "red" leaves it out.
        assertThat(search(params(moreLikeText("red", 0, OptionalInt.of(2), List.of()), 0, 10, Optional.empty()))
                        .moreLikeThisTerms())
                .contains(List.of());
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
                    () -> startSearch.handle(new StartSearchParams(
                            query, similarity, List.of(), 0, 10, true, Optional.empty(), Optional.empty())),
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
                Optional.empty(),
                List.of(),
                List.of());
        var both = new Analyzer(
                STANDARD.standard(),
                Optional.empty(),
                whitespace().custom(),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                List.of());

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

    private static ParserOptions options(
            boolean allowLeadingWildcard, int phraseSlop, int fuzzyPrefixLength, boolean enablePositionIncrements) {
        return new ParserOptions(
                allowLeadingWildcard,
                phraseSlop,
                fuzzyPrefixLength,
                enablePositionIncrements,
                Optional.empty(),
                List.of(),
                Optional.empty(),
                Optional.empty());
    }

    /** Lucene's defaults, with these date settings. */
    private static ParserOptions dates(
            Optional<DateResolution> dateResolution, Optional<String> locale, Optional<String> timeZone) {
        return dates(dateResolution, List.of(), locale, timeZone);
    }

    /** Lucene's defaults, with these date settings and resolutions per field. */
    private static ParserOptions dates(
            Optional<DateResolution> dateResolution,
            List<FieldDateResolution> fieldDateResolutions,
            Optional<String> locale,
            Optional<String> timeZone) {
        return new ParserOptions(false, 0, 0, true, dateResolution, fieldDateResolutions, locale, timeZone);
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
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static SearchQuery standard(String expression, ParserOptions options, List<PointField> points) {
        return new SearchQuery(
                Optional.empty(),
                Optional.of(new StandardQuery(expression, "body", QueryOperator.OR, options, points, STANDARD)),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static SearchQuery term(String field, BytesValue term) {
        return new SearchQuery(
                Optional.empty(),
                Optional.empty(),
                Optional.of(new TermQuery(field, term)),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static SearchQuery docValues(
            String field, DocValuesNumber number, Optional<List<BytesValue>> values, Optional<DocValuesRange> range) {
        return new SearchQuery(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(new DocValuesQuery(field, Optional.ofNullable(number), values, range, Optional.empty())),
                Optional.empty());
    }

    private static SearchQuery docValuesPrefix(String field, DocValuesNumber number, BytesValue prefix) {
        return new SearchQuery(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(new DocValuesQuery(
                        field, Optional.ofNullable(number), Optional.empty(), Optional.empty(), Optional.of(prefix))),
                Optional.empty());
    }

    private static SearchQuery knn(String field, OptionalInt docId, KnnVector vector, OptionalInt k, boolean exact) {
        return new SearchQuery(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(new KnnQuery(field, docId, Optional.ofNullable(vector), k, exact, Optional.empty())));
    }

    private static KnnVector floats(float... values) {
        List<Float> list = new java.util.ArrayList<>();
        for (float value : values) {
            list.add(value);
        }
        return new KnnVector(VectorEncoding.FLOAT32, list, List.of());
    }

    private static DocValuesRange range(String lower, String upper) {
        return new DocValuesRange(
                Optional.ofNullable(lower).map(SearchHandlersTest::text),
                Optional.ofNullable(upper).map(SearchHandlersTest::text),
                true,
                true);
    }

    private static BytesValue text(String text) {
        return new BytesValue(text, false);
    }

    private static SearchQuery moreLikeThis(int docId, List<String> fields, int maxQueryTerms) {
        return moreLikeThis(docId, fields, maxQueryTerms, STANDARD);
    }

    private static SearchQuery moreLikeThis(int docId, List<String> fields, int maxQueryTerms, Analyzer analyzer) {
        return moreLikeThis(new MoreLikeThisQuery(
                OptionalInt.of(docId),
                Optional.empty(),
                fields,
                1,
                1,
                OptionalInt.empty(),
                maxQueryTerms,
                false,
                analyzer,
                0,
                OptionalInt.empty(),
                List.of()));
    }

    private static SearchQuery moreLikeText(
            String text, int minWordLen, OptionalInt maxWordLen, List<String> stopWords) {
        return moreLikeThis(new MoreLikeThisQuery(
                OptionalInt.empty(),
                Optional.of(text),
                List.of("body"),
                1,
                1,
                OptionalInt.empty(),
                25,
                false,
                STANDARD,
                minWordLen,
                maxWordLen,
                stopWords));
    }

    private static SearchQuery moreLikeThis(MoreLikeThisQuery like) {
        return new SearchQuery(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(like),
                Optional.empty(),
                Optional.empty());
    }

    private static Analyzer whitespace() {
        return new Analyzer(
                Optional.empty(),
                Optional.empty(),
                Optional.of(new CustomAnalyzer(
                        List.of(), new AnalysisComponent("whitespace", Map.of()), List.of(), Optional.empty())),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                List.of());
    }

    private static StartSearchParams params(SearchQuery query, int offset, int limit, Optional<List<String>> fields) {
        return new StartSearchParams(query, BM25, List.of(), offset, limit, true, fields, Optional.empty());
    }

    private static SortKey fieldKey(String field, SortType type, boolean reverse) {
        return new SortKey(
                Optional.of(new FieldSort(field, type, reverse, SortSelector.MIN, SortMissing.DEFAULT)),
                Optional.empty(),
                Optional.empty());
    }

    private static StartSearchParams sorted(SearchQuery query, SortKey... sort) {
        return new StartSearchParams(query, BM25, List.of(sort), 0, 10, true, Optional.of(List.of()), Optional.empty());
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

    private DocumentHighlight highlight(StartHighlightParams params) throws Exception {
        long jobId = startHighlight.handle(params).jobId();
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            GetHighlightResult result = getHighlight.handle(new JobParams(jobId));
            if (result.highlight().isPresent()) {
                return result.highlight().get();
            }
            Thread.sleep(5);
        }
        throw new AssertionError("job " + jobId + " did not finish");
    }

    private static HighlightOptions options(int maxPassages) {
        return new HighlightOptions(maxPassages, true, STANDARD);
    }

    private static Optional<HighlightOptions> highlight(int maxPassages) {
        return Optional.of(options(maxPassages));
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
     * 100 minus that; and the sorted doc values {@code color}: "red" for id 1, "crimson" for id 3;
     * the euclidean float vector {@code vec}, [id, 0], for ids 1 and 3; and the cosine byte vector
     * {@code bvec}, [id, 1], for each.
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
                if (id != 2) {
                    doc.add(new KnnFloatVectorField("vec", new float[] {id, 0}, VectorSimilarityFunction.EUCLIDEAN));
                }
                doc.add(new KnnByteVectorField("bvec", new byte[] {(byte) id, 1}, VectorSimilarityFunction.COSINE));
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
}
