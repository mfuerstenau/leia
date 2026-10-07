package io.github.mfuerstenau.leia.server.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mfuerstenau.leia.core.ReadOnlyIndex;
import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.ProtocolJson;
import io.github.mfuerstenau.leia.protocol.Response;
import io.github.mfuerstenau.leia.protocol.document.BytesValue;
import io.github.mfuerstenau.leia.protocol.document.StoredType;
import io.github.mfuerstenau.leia.protocol.document.StoredValue;
import io.github.mfuerstenau.leia.protocol.job.JobParams;
import io.github.mfuerstenau.leia.protocol.search.ClassicQuery;
import io.github.mfuerstenau.leia.protocol.search.Explanation;
import io.github.mfuerstenau.leia.protocol.search.GetExplainResult;
import io.github.mfuerstenau.leia.protocol.search.GetSearchResult;
import io.github.mfuerstenau.leia.protocol.search.Hit;
import io.github.mfuerstenau.leia.protocol.search.HitField;
import io.github.mfuerstenau.leia.protocol.search.ParserOptions;
import io.github.mfuerstenau.leia.protocol.search.PointField;
import io.github.mfuerstenau.leia.protocol.search.PointType;
import io.github.mfuerstenau.leia.protocol.search.QueryOperator;
import io.github.mfuerstenau.leia.protocol.search.SearchQuery;
import io.github.mfuerstenau.leia.protocol.search.SearchResults;
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
import java.util.Optional;
import java.util.OptionalLong;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.IntPoint;
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
        assertFails(() -> startExplain.handle(new StartExplainParams(broken, 0)), ErrorCode.INVALID_QUERY);
        assertThat(errorCodeOf("startSearch.error-invalid-query.json")).isEqualTo(ErrorCode.INVALID_QUERY);
        assertThat(errorCodeOf("getSearch.error-invalid-query.json")).isEqualTo(ErrorCode.INVALID_QUERY);
    }

    @Test
    void checksTheFields() throws Exception {
        openIndex();
        SearchQuery unknownDefault = new SearchQuery(
                Optional.of(new ClassicQuery("red", "bdy", QueryOperator.OR, DEFAULTS, 2, false, false)),
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
                Optional.of(new TermQuery("body", new BytesValue("red", false))));
        SearchQuery neither = new SearchQuery(Optional.empty(), Optional.empty(), Optional.empty());

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
    void failsWithoutOpenIndex() {
        assertFails(() -> startSearch.handle(params(classic("red"), 0, 10, Optional.empty())), ErrorCode.NO_INDEX_OPEN);
        assertFails(() -> startExplain.handle(new StartExplainParams(classic("red"), 0)), ErrorCode.NO_INDEX_OPEN);
    }

    @Test
    void explainsADocument() throws Exception {
        openIndex();
        SearchQuery query = term("body", new BytesValue("red", false));

        Explanation match = explain(new StartExplainParams(query, 0));
        assertThat(match.match()).isTrue();
        assertThat(match.description()).startsWith("weight(body:red in 0)");
        // Counts are longs and come as whole numbers; the score is a float.
        assertThat(flatten(match)).anySatisfy(node -> {
            assertThat(node.description()).startsWith("n, number of documents containing term");
            assertThat(node.value()).isEqualTo("2");
        });
        assertThat(match.value()).contains(".");

        Explanation miss = explain(new StartExplainParams(query, 1));
        assertThat(miss.match()).isFalse();
    }

    @Test
    void explainsOnlyDocumentsOfTheIndex() throws Exception {
        openIndex();

        assertFails(() -> startExplain.handle(new StartExplainParams(classic("red"), 3)), ErrorCode.DOC_NOT_FOUND);
        assertThat(errorCodeOf("startExplain.error-doc-not-found.json")).isEqualTo(ErrorCode.DOC_NOT_FOUND);
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

    private static SearchQuery classic(
            String expression,
            ParserOptions options,
            int fuzzyMaxEdits,
            boolean splitOnWhitespace,
            boolean autoGeneratePhraseQueries) {
        return new SearchQuery(
                Optional.of(new ClassicQuery(
                        expression,
                        "body",
                        QueryOperator.OR,
                        options,
                        fuzzyMaxEdits,
                        splitOnWhitespace,
                        autoGeneratePhraseQueries)),
                Optional.empty(),
                Optional.empty());
    }

    private static SearchQuery standard(String expression, ParserOptions options, List<PointField> points) {
        return new SearchQuery(
                Optional.empty(),
                Optional.of(new StandardQuery(expression, "body", QueryOperator.OR, options, points)),
                Optional.empty());
    }

    private static SearchQuery term(String field, BytesValue term) {
        return new SearchQuery(Optional.empty(), Optional.empty(), Optional.of(new TermQuery(field, term)));
    }

    private static StartSearchParams params(SearchQuery query, int offset, int limit, Optional<List<String>> fields) {
        return new StartSearchParams(query, offset, limit, true, fields);
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
     * each with the int point {@code size}, 10 times its id.
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
