package io.github.mfuerstenau.leia.server;

import static io.github.mfuerstenau.leia.server.RealBackend.BM25;
import static io.github.mfuerstenau.leia.server.RealBackend.DEFAULTS;
import static io.github.mfuerstenau.leia.server.RealBackend.STANDARD;
import static io.github.mfuerstenau.leia.server.RealBackend.testIndex;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.analysis.AnalysisComponent;
import io.github.mfuerstenau.leia.protocol.analysis.Analyzer;
import io.github.mfuerstenau.leia.protocol.analysis.Collation;
import io.github.mfuerstenau.leia.protocol.analysis.CollationStrength;
import io.github.mfuerstenau.leia.protocol.analysis.CustomAnalyzer;
import io.github.mfuerstenau.leia.protocol.analysis.JapaneseOptions;
import io.github.mfuerstenau.leia.protocol.analysis.LimitTokenCount;
import io.github.mfuerstenau.leia.protocol.analysis.PresetAnalyzer;
import io.github.mfuerstenau.leia.protocol.analysis.Shingles;
import io.github.mfuerstenau.leia.protocol.analysis.StandardAnalyzer;
import io.github.mfuerstenau.leia.protocol.analysis.StemDictionary;
import io.github.mfuerstenau.leia.protocol.analysis.WordSet;
import io.github.mfuerstenau.leia.protocol.check.CheckIndexResult;
import io.github.mfuerstenau.leia.protocol.check.GetCheckIndexParams;
import io.github.mfuerstenau.leia.protocol.check.GetCheckIndexResult;
import io.github.mfuerstenau.leia.protocol.check.StartCheckIndexParams;
import io.github.mfuerstenau.leia.protocol.commits.Commit;
import io.github.mfuerstenau.leia.protocol.commits.FieldFormats;
import io.github.mfuerstenau.leia.protocol.commits.GetCommitParams;
import io.github.mfuerstenau.leia.protocol.commits.GetCommitsParams;
import io.github.mfuerstenau.leia.protocol.commits.Segment;
import io.github.mfuerstenau.leia.protocol.document.BytesValue;
import io.github.mfuerstenau.leia.protocol.document.DocumentField;
import io.github.mfuerstenau.leia.protocol.document.GetDocumentParams;
import io.github.mfuerstenau.leia.protocol.document.GetDocumentResult;
import io.github.mfuerstenau.leia.protocol.document.KnnVector;
import io.github.mfuerstenau.leia.protocol.filter.FilterResult;
import io.github.mfuerstenau.leia.protocol.filter.StartFilterParams;
import io.github.mfuerstenau.leia.protocol.index.Feature;
import io.github.mfuerstenau.leia.protocol.index.GetIndexStatsParams;
import io.github.mfuerstenau.leia.protocol.index.GetIndexStatsResult;
import io.github.mfuerstenau.leia.protocol.index.IndexField;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexParams;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexResult;
import io.github.mfuerstenau.leia.protocol.index.OpenMode;
import io.github.mfuerstenau.leia.protocol.index.VectorEncoding;
import io.github.mfuerstenau.leia.protocol.index.VectorSimilarity;
import io.github.mfuerstenau.leia.protocol.points.GetPointCellParams;
import io.github.mfuerstenau.leia.protocol.points.GetPointCellResult;
import io.github.mfuerstenau.leia.protocol.points.GetPointTreeParams;
import io.github.mfuerstenau.leia.protocol.points.GetPointTreeResult;
import io.github.mfuerstenau.leia.protocol.points.PointSegment;
import io.github.mfuerstenau.leia.protocol.search.ClassicQuery;
import io.github.mfuerstenau.leia.protocol.search.DateResolution;
import io.github.mfuerstenau.leia.protocol.search.DocValuesNumber;
import io.github.mfuerstenau.leia.protocol.search.DocValuesQuery;
import io.github.mfuerstenau.leia.protocol.search.DocValuesRange;
import io.github.mfuerstenau.leia.protocol.search.Explanation;
import io.github.mfuerstenau.leia.protocol.search.FieldDateResolution;
import io.github.mfuerstenau.leia.protocol.search.HighlightOptions;
import io.github.mfuerstenau.leia.protocol.search.Hit;
import io.github.mfuerstenau.leia.protocol.search.KnnQuery;
import io.github.mfuerstenau.leia.protocol.search.LikeTerm;
import io.github.mfuerstenau.leia.protocol.search.MoreLikeThisQuery;
import io.github.mfuerstenau.leia.protocol.search.ParserOptions;
import io.github.mfuerstenau.leia.protocol.search.PointField;
import io.github.mfuerstenau.leia.protocol.search.PointType;
import io.github.mfuerstenau.leia.protocol.search.QueryOperator;
import io.github.mfuerstenau.leia.protocol.search.ScoreSort;
import io.github.mfuerstenau.leia.protocol.search.SearchQuery;
import io.github.mfuerstenau.leia.protocol.search.SearchResults;
import io.github.mfuerstenau.leia.protocol.search.SortKey;
import io.github.mfuerstenau.leia.protocol.search.StandardQuery;
import io.github.mfuerstenau.leia.protocol.search.StartHighlightParams;
import io.github.mfuerstenau.leia.protocol.search.StartSearchParams;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Indexes created by Lucene 8.x read through {@link LeiaBackend} with Lucene 9.12 in a class loader of its own (ADR
 * 0049). {@code lucene8}, from leia-lucene9's writer on 8.11, has documents 1 to 7 and the soft update
 * of id 3 (document 4, by the index sort) with id 2 deleted; {@code lucene8to9} is a copy to which
 * 9.12 added ids 9 to 11 (documents 8 to 10) with the KNN vectors {@code embedding} (4 floats, cosine:
 * 9 is (1, 0, 0, 0), 10 (0.8, 0.6, 0, 0), 11 (0, 1, 0, 0)) and {@code code} (4 bytes, Euclidean, 9 and
 * 10 only), and deleted id 5. {@code Lucene9BackendTest} is the Lucene 9 index that Lucene 10 reads.
 */
class Lucene8BackendTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private static final KnnVector NORTH = new KnnVector(VectorEncoding.FLOAT32, List.of(1f, 0f, 0f, 0f), List.of());

    private RealBackend backend;

    @BeforeEach
    void start() throws Exception {
        backend = RealBackend.start();
    }

    @AfterEach
    void close() {
        backend.close();
    }

    private OpenIndexResult open(Path path) throws Exception {
        return backend.call(Methods.OPEN_INDEX, new OpenIndexParams(path.toString()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"lucene8", "lucene8to9"})
    void opensTheIndexWithItsFeatures(String name) throws Exception {
        boolean nine = name.equals("lucene8to9");
        OpenIndexResult opened = open(testIndex(name));
        assertThat(opened.luceneRuntime()).isEqualTo("9.12.3");
        assertThat(opened.numDocs()).hasValue(nine ? 9 : 7);
        assertThat(opened.maxDoc()).hasValue(nine ? 11 : 8);
        List<Feature> features = new ArrayList<>(List.of(
                Feature.INDEX_STATS,
                Feature.REOPEN,
                Feature.DOCUMENTS,
                Feature.STORED_VALUES,
                Feature.STORED_SCAN,
                Feature.TERM_VECTORS,
                Feature.DOC_VALUES,
                Feature.TERMS,
                Feature.POSTINGS,
                Feature.POSITIONS,
                Feature.TOP_TERMS,
                Feature.EXPORT_TERMS,
                Feature.AUTO_STOP_WORDS,
                Feature.CLASSIC_SEARCH,
                Feature.TERM_SEARCH,
                Feature.FILTER,
                Feature.EXPLAIN,
                Feature.HIGHLIGHT,
                Feature.SCORING,
                Feature.DOC_VALUES_SEARCH,
                Feature.STANDARD_SEARCH,
                Feature.MORE_LIKE_THIS,
                Feature.RECONSTRUCT,
                Feature.POINTS,
                Feature.COMMITS,
                Feature.CHECK_INDEX,
                Feature.CHECK_LEVELS));
        // Only with a field that has vectors, which only 9.x writes.
        if (nine) {
            features.add(Feature.KNN_VECTORS);
        }
        assertThat(opened.features()).containsExactlyInAnyOrderElementsOf(features);
        // Created by 8, so its norms are those of 8, though 9 wrote the commit.
        assertThat(opened.indexes().getFirst().reader().orElseThrow().createdVersionMajor())
                .isEqualTo(8);

        GetIndexStatsResult stats = backend.call(Methods.GET_INDEX_STATS, new GetIndexStatsParams());
        assertThat(stats.indexes().getFirst().commitLuceneVersion()).isEqualTo(nine ? "9.12.3" : "8.11.4");
        assertThat(stats.indexes().getFirst().minSegmentLuceneVersion()).contains("8.11.4");
        assertThat(stats.fields())
                .filteredOn(field -> field.vectorDimension() > 0)
                .extracting(
                        IndexField::name,
                        IndexField::vectorDimension,
                        IndexField::vectorEncoding,
                        IndexField::vectorSimilarity)
                .containsExactlyElementsOf(
                        nine
                                ? List.of(
                                        tuple(
                                                "embedding",
                                                4,
                                                Optional.of(VectorEncoding.FLOAT32),
                                                Optional.of(VectorSimilarity.COSINE)),
                                        tuple(
                                                "code",
                                                4,
                                                Optional.of(VectorEncoding.BYTE),
                                                Optional.of(VectorSimilarity.EUCLIDEAN)))
                                : List.of());
        assertThat(stats.fields())
                .filteredOn(field -> field.name().equals("body"))
                .singleElement()
                .satisfies(body -> {
                    assertThat(body.vectorEncoding()).isEmpty();
                    assertThat(body.vectorSimilarity()).isEmpty();
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"lucene8", "lucene8to9"})
    void scansTheStoredValues(String name) throws Exception {
        OpenIndexResult opened = open(testIndex(name));

        RuntimeStoredScans.scansTheStoredValues(backend, opened.maxDoc().orElseThrow(), true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"lucene8", "lucene8to9"})
    void filtersTheDocuments(String name) throws Exception {
        open(testIndex(name));

        RuntimeFilters.filtersTheDocuments(backend, true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"lucene8", "lucene8to9"})
    void explainsAHit(String name) throws Exception {
        OpenIndexResult opened = open(testIndex(name));

        RuntimeExplains.explainsAHit(backend, opened.maxDoc().orElseThrow(), true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"lucene8", "lucene8to9"})
    void findsTheAutoStopWords(String name) throws Exception {
        OpenIndexResult opened = open(testIndex(name));

        RuntimeAutoStopWords.findsTheWords(backend, "body");
        RuntimeAutoStopWords.searchesWithoutTheWords(backend, opened.maxDoc().orElseThrow(), "body", "beta");
    }

    @ParameterizedTest
    @ValueSource(strings = {"lucene8", "lucene8to9"})
    void highlightsTheMatches(String name) throws Exception {
        OpenIndexResult opened = open(testIndex(name));
        RuntimeHighlights.highlightsTheMatches(backend, opened.maxDoc().orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {"lucene8", "lucene8to9"})
    void sortsAndScores(String name) throws Exception {
        open(testIndex(name));
        RuntimeScorings.sortsAndScores(backend);
    }

    @Test
    void marksNothingForANearestVector() throws Exception {
        open(testIndex("lucene8to9"));
        RuntimeHighlights.marksNothingWithoutTerms(
                backend, knn("embedding", NORTH, OptionalInt.of(2), Optional.empty()), 8);
    }

    @ParameterizedTest
    @ValueSource(strings = {"lucene8", "lucene8to9"})
    void searchesATerm(String name) throws Exception {
        OpenIndexResult opened = open(testIndex(name));

        RuntimeSearches.searchesATerm(backend, opened.maxDoc().orElseThrow(), "beta", true);
    }

    @Test
    void reopensAChangedIndex(@TempDir Path tmp) throws Exception {
        RuntimeReopens.reopensAChangedIndex(backend, testIndex("lucene8"), tmp.resolve("lucene8"), "segments_2");
    }

    /** The commit of 9.12 adds the KNN vectors, so the reopen reports them (ADR 0060). */
    @Test
    void reopensWithTheFeaturesOfTheNewCommit(@TempDir Path tmp) throws Exception {
        RuntimeReopens.Reopened reopened = RuntimeReopens.reopensAChangedIndex(
                backend, testIndex("lucene8to9"), tmp.resolve("lucene8to9"), "segments_3");
        assertThat(reopened.opened().features()).doesNotContain(Feature.KNN_VECTORS);
        assertThat(reopened.changed().features()).contains(Feature.KNN_VECTORS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"lucene8", "lucene8to9"})
    void rebuildsADocument(String name) throws Exception {
        OpenIndexResult opened = open(testIndex(name));

        RuntimeRebuilds.rebuildsADocument(backend, opened.maxDoc().orElseThrow(), true, true);
    }

    @Test
    void readsTheNormsAndTheVectorsOfDocuments() throws Exception {
        open(testIndex("lucene8to9"));
        // Six tokens: 8.x keeps the length.
        assertThat(field(document(0), "body").norm()).hasValue(6);
        assertThat(field(document(0), "embedding").vector()).isEmpty();
        GetDocumentResult ten = document(9);
        assertThat(field(ten, "embedding").vector())
                .contains(new KnnVector(VectorEncoding.FLOAT32, List.of(0.8f, 0.6f, 0f, 0f), List.of()));
        assertThat(field(ten, "code").vector())
                .contains(
                        new KnnVector(VectorEncoding.BYTE, List.of(), List.of((byte) 4, (byte) 3, (byte) 2, (byte) 1)));
        assertThat(field(document(10), "code").vector()).isEmpty();
        assertThat(document(5).deleted()).isTrue();
    }

    private GetDocumentResult document(int docId) throws Exception {
        return backend.call(Methods.GET_DOCUMENT, new GetDocumentParams(docId));
    }

    private static DocumentField field(GetDocumentResult document, String name) {
        return document.fields().stream()
                .filter(field -> field.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static SearchQuery knn(KnnQuery knn) {
        return new SearchQuery(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(knn));
    }

    private static SearchQuery knn(String field, KnnVector target, OptionalInt k, Optional<String> filter) {
        return knn(new KnnQuery(
                field,
                OptionalInt.empty(),
                Optional.of(target),
                k,
                k.isEmpty(),
                filter.map(expression ->
                        new ClassicQuery(expression, "title", QueryOperator.OR, DEFAULTS, 2, false, false, STANDARD))));
    }

    @Test
    void explainsANearestVector() throws Exception {
        open(testIndex("lucene8to9"));
        for (SearchQuery query : List.of(
                knn("embedding", NORTH, OptionalInt.of(2), Optional.empty()),
                knn("embedding", NORTH, OptionalInt.empty(), Optional.empty()))) {
            for (Hit hit : backend.search(query, 2).hits()) {
                RuntimeExplains.explainsTheHit(backend, query, hit, true);
            }
        }
    }

    @Test
    void searchesTheNearestVectors() throws Exception {
        open(testIndex("lucene8to9"));
        SearchResults approximate = backend.search(knn("embedding", NORTH, OptionalInt.of(2), Optional.empty()), 10);
        assertThat(approximate.totalHits()).isEqualTo(2);
        assertThat(approximate.hits()).extracting(Hit::docId).containsExactly(8, 9);
        assertThat(approximate.hits().getFirst().score()).isEqualTo(1f);

        SearchResults exact = backend.search(knn("embedding", NORTH, OptionalInt.empty(), Optional.empty()), 10);
        assertThat(exact.hits())
                .extracting(Hit::docId, Hit::score)
                .containsExactly(
                        tuple(8, approximate.hits().getFirst().score()),
                        tuple(9, approximate.hits().get(1).score()),
                        tuple(10, 0.5f));

        // Only the documents that match the filter are searched.
        assertThat(backend.search(knn("embedding", NORTH, OptionalInt.of(3), Optional.of("east")), 10)
                        .hits())
                .extracting(Hit::docId)
                .containsExactly(9, 10);
        assertThat(backend.search(knn("embedding", NORTH, OptionalInt.empty(), Optional.of("east")), 10)
                        .hits())
                .extracting(Hit::docId)
                .containsExactly(9, 10);

        // The vector of a document as the target: that of id 10 in code.
        SearchResults byDocument = backend.search(
                knn(new KnnQuery(
                        "code", OptionalInt.of(9), Optional.empty(), OptionalInt.of(1), false, Optional.empty())),
                10);
        assertThat(byDocument.hits()).extracting(Hit::docId).containsExactly(9);

        // With the stored fields asked for.
        SearchResults withFields = backend.search(new StartSearchParams(
                knn("embedding", NORTH, OptionalInt.of(1), Optional.empty()),
                BM25,
                List.of(new SortKey(Optional.empty(), Optional.of(new ScoreSort(false)), Optional.empty())),
                0,
                10,
                true,
                Optional.of(List.of("title")),
                Optional.empty()));
        assertThat(withFields.hits().getFirst().fields())
                .singleElement()
                .satisfies(title -> assertThat(title.name()).isEqualTo("title"));
    }

    @Test
    void refusesKnnQueriesThatDoNotFit() throws Exception {
        open(testIndex("lucene8to9"));
        fails(knn("nothing", NORTH, OptionalInt.of(2), Optional.empty()), ErrorCode.FIELD_NOT_FOUND);
        assertThat(fails(knn("body", NORTH, OptionalInt.of(2), Optional.empty()), ErrorCode.INVALID_PARAMS)
                        .getMessage())
                .isEqualTo("Invalid params: the field 'body' has no KNN vectors");
        fails(knn("embedding", NORTH, OptionalInt.of(0), Optional.empty()), ErrorCode.INVALID_PARAMS);
        fails(
                knn(new KnnQuery(
                        "embedding",
                        OptionalInt.empty(),
                        Optional.empty(),
                        OptionalInt.of(2),
                        false,
                        Optional.empty())),
                ErrorCode.INVALID_PARAMS);
        fails(
                knn(new KnnQuery(
                        "embedding",
                        OptionalInt.empty(),
                        Optional.of(NORTH),
                        OptionalInt.of(2),
                        true,
                        Optional.empty())),
                ErrorCode.INVALID_PARAMS);
        assertThat(fails(
                                knn(
                                        "embedding",
                                        new KnnVector(VectorEncoding.FLOAT32, List.of(1f, 0f, 0f), List.of()),
                                        OptionalInt.of(2),
                                        Optional.empty()),
                                ErrorCode.INVALID_PARAMS)
                        .getMessage())
                .isEqualTo("Invalid params: the field 'embedding' has vectors of 4 dimensions, not 3");
        assertThat(fails(knn("code", NORTH, OptionalInt.of(2), Optional.empty()), ErrorCode.INVALID_PARAMS)
                        .getMessage())
                .isEqualTo("Invalid params: the field 'code' has vectors of bytes, not of floats");
        fails(
                knn(
                        "embedding",
                        new KnnVector(VectorEncoding.FLOAT32, List.of(Float.NaN, 0f, 0f, 0f), List.of()),
                        OptionalInt.of(2),
                        Optional.empty()),
                ErrorCode.INVALID_PARAMS);
        assertThat(fails(
                                knn(
                                        "embedding",
                                        new KnnVector(VectorEncoding.FLOAT32, List.of(0f, 0f, 0f, 0f), List.of()),
                                        OptionalInt.of(2),
                                        Optional.empty()),
                                ErrorCode.INVALID_PARAMS)
                        .getMessage())
                .contains("compares by cosine");
        fails(
                knn(new KnnQuery(
                        "embedding", OptionalInt.of(11), Optional.empty(), OptionalInt.of(2), false, Optional.empty())),
                ErrorCode.DOC_NOT_FOUND);
        assertThat(fails(
                                knn(new KnnQuery(
                                        "code",
                                        OptionalInt.of(10),
                                        Optional.empty(),
                                        OptionalInt.of(2),
                                        false,
                                        Optional.empty())),
                                ErrorCode.INVALID_PARAMS)
                        .getMessage())
                .isEqualTo("Invalid params: document 10 has no vector in the field 'code'");
        fails(knn("embedding", NORTH, OptionalInt.of(2), Optional.of("title:(")), ErrorCode.INVALID_QUERY);
    }

    @Test
    void refusesKnnOnAnIndexWithoutVectors() throws Exception {
        open(testIndex("lucene7"));
        fails(knn("body", NORTH, OptionalInt.of(2), Optional.empty()), ErrorCode.NOT_SUPPORTED);
    }

    private RpcException fails(SearchQuery query, ErrorCode code) {
        return backend.fails(
                Methods.START_SEARCH,
                new StartSearchParams(query, BM25, List.of(), 0, 10, true, Optional.empty(), Optional.empty()),
                code);
    }

    @Test
    void readsThePointTree() throws Exception {
        open(testIndex("lucene8to9"));
        GetPointTreeResult tree = backend.call(Methods.GET_POINT_TREE, new GetPointTreeParams("pt"));
        assertThat(tree.size()).isEqualTo(120);
        // The 9.x segment has no points.
        assertThat(tree.segments())
                .extracting(PointSegment::segment, PointSegment::name, PointSegment::docBase, PointSegment::size)
                .containsExactly(tuple(0, Optional.of("_0"), 0, 60L), tuple(1, Optional.of("_1"), 4, 60L));
        GetPointCellResult root = backend.call(Methods.GET_POINT_CELL, new GetPointCellParams("pt", 0, List.of()));
        assertThat(root.cell().size()).isEqualTo(60);
        assertThat(root.children()).isNotEmpty();
        backend.fails(Methods.GET_POINT_CELL, new GetPointCellParams("pt", 2, List.of()), ErrorCode.INVALID_PARAMS);
    }

    @Test
    void readsTheCommits() throws Exception {
        open(testIndex("lucene8to9"));
        List<Commit> commits =
                backend.call(Methods.GET_COMMITS, new GetCommitsParams()).commits();
        assertThat(commits)
                .extracting(Commit::segmentsFile, Commit::current, Commit::luceneVersion)
                .containsExactly(
                        tuple("segments_3", true, "9.12.3"),
                        tuple("segments_2", false, "8.11.4"),
                        tuple("segments_1", false, "8.11.4"));
        List<Segment> segments = backend.call(Methods.GET_COMMIT, new GetCommitParams(3, OptionalInt.empty()))
                .segments();
        assertThat(segments)
                .extracting(Segment::name, Segment::codec, Segment::luceneVersion)
                .containsExactly(
                        tuple("_0", Optional.of("Lucene87"), "8.11.4"),
                        tuple("_1", Optional.of("Lucene87"), "8.11.4"),
                        tuple("_2", Optional.of("Lucene912"), "9.12.3"));
        assertThat(segments.getFirst().softDelCount()).isEqualTo(1);
        assertThat(segments.getFirst().indexSort()).contains("<long: \"order\">");
        assertThat(segments.get(2).fieldFormats())
                .filteredOn(formats -> formats.field().equals("embedding"))
                .extracting(FieldFormats::knnVectors)
                .containsExactly(Optional.of("Lucene99HnswVectorsFormat"));
    }

    /** The lines and the outcome of a check, polled as the check screen does. */
    private record Check(List<String> lines, CheckIndexResult result) {}

    private Check check(StartCheckIndexParams params) throws Exception {
        long jobId = backend.call(Methods.START_CHECK_INDEX, params).jobId();
        List<String> lines = new ArrayList<>();
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            GetCheckIndexResult poll =
                    backend.call(Methods.GET_CHECK_INDEX, new GetCheckIndexParams(jobId, lines.size()));
            lines.addAll(poll.lines());
            if (poll.result().isPresent()) {
                return new Check(lines, poll.result().get());
            }
            Thread.sleep(5);
        }
        throw new AssertionError("check " + jobId + " did not finish");
    }

    @Test
    void checksAtTheLevels() throws Exception {
        open(testIndex("lucene8to9"));
        Check fast = check(new StartCheckIndexParams(OptionalInt.of(1)));
        assertThat(fast.lines())
                .contains("Checking with Lucene 9.12.3")
                .noneMatch(line -> line.contains("test: terms"));
        assertThat(fast.result().indexes().getFirst().clean()).isTrue();
        Check slow = check(new StartCheckIndexParams(OptionalInt.of(3)));
        assertThat(slow.lines())
                .anyMatch(line -> line.contains("test: points"))
                .anyMatch(line -> line.contains("test: vectors"));
        assertThat(slow.result().indexes().getFirst().clean()).isTrue();
    }

    @Test
    void opensWithoutAReaderThroughTheRuntime() throws Exception {
        OpenIndexResult opened = backend.call(
                Methods.OPEN_INDEX,
                new OpenIndexParams(
                        testIndex("lucene8to9").toString(), Optional.empty(), Optional.of(OpenMode.DIRECTORY_ONLY)));
        assertThat(opened.features())
                .containsExactlyInAnyOrder(Feature.COMMITS, Feature.CHECK_INDEX, Feature.CHECK_LEVELS);
        assertThat(backend.call(Methods.GET_COMMIT, new GetCommitParams(3, OptionalInt.empty()))
                        .segments())
                .hasSize(3);
        backend.fails(Methods.GET_POINT_TREE, new GetPointTreeParams("pt"), ErrorCode.NO_READER);
    }

    private static StartSearchParams params(SearchQuery query) {
        return new StartSearchParams(query, BM25, List.of(), 0, 10, true, Optional.empty(), Optional.empty());
    }

    private static List<Integer> docIds(SearchResults results) {
        return results.hits().stream().map(Hit::docId).toList();
    }

    /** The search, the filter and the explanation of the first hit agree; returns the hits' ids. */
    private List<Integer> searchFilterAndExplain(SearchQuery query) throws Exception {
        SearchResults results = backend.search(query, 10);
        assertThat(results.hits()).isNotEmpty();
        FilterResult filter = backend.filter(new StartFilterParams(Optional.of(query), Optional.empty(), false));
        assertThat(filter.matchCount()).isEqualTo(results.totalHits());
        assertThat(filter.query()).isEqualTo(results.query());
        Hit first = results.hits().getFirst();
        Explanation explanation = backend.explain(query, BM25, first.docId());
        assertThat(explanation.match()).isTrue();
        assertThat(Float.parseFloat(explanation.value())).isEqualTo(first.score());
        return docIds(results);
    }

    private static final List<PointField> POINTS =
            List.of(new PointField("pt", PointType.INT), new PointField("amount", PointType.LONG));

    /** The standard parser, with point fields (ADR 0066). */
    @Test
    void searchesWithTheStandardParserAndPoints() throws Exception {
        open(testIndex("lucene8"));
        SearchQuery query = RealBackend.standard("pt:[100 TO 114] OR amount:5000 OR gamma", QueryOperator.OR, POINTS);
        assertThat(searchFilterAndExplain(query)).containsExactlyInAnyOrder(0, 5);
        assertThat(backend.search(query, 10).query()).isEqualTo("pt:[100 TO 114] amount:[5000 TO 5000] body:gamma");
        assertThat(backend.highlight(new StartHighlightParams(
                                RealBackend.standard("gamma", QueryOperator.OR, List.of()),
                                0,
                                Optional.of("body"),
                                new HighlightOptions(2, false, STANDARD)))
                        .fields())
                .singleElement()
                .satisfies(field ->
                        assertThat(field.values().getFirst().matchCount()).isEqualTo(2));

        backend.fails(
                Methods.START_SEARCH,
                params(RealBackend.standard("pt:1", QueryOperator.OR, List.of(new PointField("pt", PointType.LONG)))),
                ErrorCode.INVALID_PARAMS);
        backend.fails(
                Methods.START_SEARCH,
                params(RealBackend.standard(
                        "pt:1",
                        QueryOperator.OR,
                        List.of(new PointField("pt", PointType.INT), new PointField("pt", PointType.INT)))),
                ErrorCode.INVALID_PARAMS);
        backend.fails(
                Methods.START_SEARCH,
                params(RealBackend.standard("x", QueryOperator.OR, List.of(new PointField("nothing", PointType.INT)))),
                ErrorCode.FIELD_NOT_FOUND);
        RpcException syntax = backend.fails(
                Methods.START_SEARCH,
                params(RealBackend.standard("body:(", QueryOperator.OR, List.of())),
                ErrorCode.INVALID_QUERY);
        assertThat(syntax).hasMessage("Invalid query: Cannot parse 'body:(': Encountered \"<EOF>\" at column 6.");
    }

    /**
     * The parsers' fuzzy edits, split, auto-generated phrases and dates, on 9.12 (ADR 0069), checked
     * as for Lucene 10.
     */
    @Test
    void searchesWithTheParsersOptions() throws Exception {
        open(testIndex("lucene8"));
        ParserOptions days = new ParserOptions(
                false,
                0,
                0,
                true,
                Optional.of(DateResolution.YEAR),
                List.of(new FieldDateResolution("body", DateResolution.DAY)),
                Optional.of("en-US"),
                Optional.of("UTC"));
        assertThat(backend.search(classic("[1/15/26 TO 1/31/26]", days, 2, false, false), 10)
                        .query())
                .isEqualTo("body:[20260115 TO 20260131]");
        assertThat(backend.search(
                                new SearchQuery(
                                        Optional.empty(),
                                        Optional.of(new StandardQuery(
                                                "[1/15/26 TO 1/31/26]",
                                                "body",
                                                QueryOperator.OR,
                                                days,
                                                List.of(),
                                                STANDARD)),
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty()),
                                10)
                        .query())
                .isEqualTo("body:[20260115 TO 20260131]");
        assertThat(backend.search(classic("gamma~", RealBackend.DEFAULTS, 1, false, false), 10)
                        .query())
                .isEqualTo("body:gamma~1");
        // 9.12 refused auto-generated phrases while it didn't split (ADR 0069).
        assertThat(backend.search(classic("wi-fi", RealBackend.DEFAULTS, 2, true, true), 10)
                        .query())
                .isEqualTo("body:\"wi fi\"");

        assertThat(backend.fails(
                        Methods.START_SEARCH,
                        params(classic("x", RealBackend.DEFAULTS, 3, false, false)),
                        ErrorCode.INVALID_PARAMS))
                .hasMessage("Invalid params: fuzzyMaxEdits must be 0 to 2, not 3");
        assertThat(backend.fails(
                        Methods.START_SEARCH,
                        params(classic("x", RealBackend.DEFAULTS, 2, false, true)),
                        ErrorCode.INVALID_PARAMS))
                .hasMessage("Invalid params: autoGeneratePhraseQueries needs splitOnWhitespace");
        assertThat(backend.fails(
                        Methods.START_SEARCH,
                        params(classic("x", dates(List.of(), Optional.of("en_US"), Optional.empty()), 2, false, false)),
                        ErrorCode.INVALID_PARAMS))
                .hasMessage("Invalid params: 'en_US' is not a BCP 47 language tag, such as en-US");
        assertThat(backend.fails(
                        Methods.START_SEARCH,
                        params(classic("x", dates(List.of(), Optional.empty(), Optional.of("Mars")), 2, false, false)),
                        ErrorCode.INVALID_PARAMS))
                .hasMessage("Invalid params: 'Mars' is not a time zone, such as UTC, Europe/Berlin or +02:00");
        FieldDateResolution body = new FieldDateResolution("body", DateResolution.DAY);
        assertThat(backend.fails(
                        Methods.START_SEARCH,
                        params(classic(
                                "x", dates(List.of(body, body), Optional.empty(), Optional.empty()), 2, false, false)),
                        ErrorCode.INVALID_PARAMS))
                .hasMessage("Invalid params: the field 'body' has its date resolution listed twice");
        backend.fails(
                Methods.START_SEARCH,
                params(classic(
                        "x",
                        dates(
                                List.of(new FieldDateResolution("nothing", DateResolution.DAY)),
                                Optional.empty(),
                                Optional.empty()),
                        2,
                        false,
                        false)),
                ErrorCode.FIELD_NOT_FOUND);
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
                        autoGeneratePhraseQueries,
                        STANDARD)),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static ParserOptions dates(
            List<FieldDateResolution> fields, Optional<String> locale, Optional<String> timeZone) {
        return new ParserOptions(false, 0, 0, true, Optional.empty(), fields, locale, timeZone);
    }

    /** More-Like-This, by a document and by a text, with the terms it chose (ADR 0066). */
    @Test
    void findsSimilarDocuments() throws Exception {
        open(testIndex("lucene8"));
        SearchQuery byDoc = RealBackend.moreLikeThis(OptionalInt.of(0), Optional.empty());
        assertThat(searchFilterAndExplain(byDoc)).startsWith(0);
        SearchResults results = backend.search(byDoc, 10);
        assertThat(results.query())
                .isEqualTo("like(doc 0, fields [body], minTermFreq 1, minDocFreq 1, maxQueryTerms 25)");
        assertThat(results.moreLikeThisTerms().orElseThrow().getFirst())
                .extracting(LikeTerm::term, LikeTerm::tf, LikeTerm::docFreq)
                .containsExactly("gamma", 2, 1);

        SearchQuery byText = RealBackend.moreLikeThis(OptionalInt.empty(), Optional.of("gamma"));
        assertThat(searchFilterAndExplain(byText)).containsExactly(0);
        assertThat(backend.search(byText, 10).moreLikeThisTerms().orElseThrow())
                .extracting(LikeTerm::term)
                .containsExactly("gamma");
        assertThat(backend.highlight(new StartHighlightParams(
                                byText, 0, Optional.of("body"), new HighlightOptions(2, false, STANDARD)))
                        .fields())
                .singleElement()
                .satisfies(field ->
                        assertThat(field.values().getFirst().matchCount()).isEqualTo(2));
        // Other queries choose no terms.
        assertThat(backend.search(RealBackend.classic("gamma"), 10).moreLikeThisTerms())
                .isEmpty();

        backend.fails(
                Methods.START_SEARCH,
                params(RealBackend.moreLikeThis(OptionalInt.of(0), Optional.of("gamma"))),
                ErrorCode.INVALID_PARAMS);
        backend.fails(
                Methods.START_SEARCH,
                params(RealBackend.moreLikeThis(OptionalInt.of(8), Optional.empty())),
                ErrorCode.DOC_NOT_FOUND);
        backend.fails(Methods.START_SEARCH, params(like("nothing")), ErrorCode.FIELD_NOT_FOUND);
        RpcException stored = backend.fails(Methods.START_SEARCH, params(like("price")), ErrorCode.INVALID_PARAMS);
        assertThat(stored)
                .hasMessage("Invalid params: the field 'price' is not indexed, so it has no terms to search for");
    }

    /**
     * Custom analyzers, shingles and the options of presets, which 9.12 builds as Lucene 10 does (ADR
     * 0067); a component that only Lucene 10 has and the options of Kuromoji aren't supported.
     */
    @Test
    void searchesWithTheAnalyzersOf912(@TempDir Path tmp) throws Exception {
        open(testIndex("lucene8"));
        Files.writeString(tmp.resolve("stop.txt"), "the\n");
        Analyzer custom = analyzer(new CustomAnalyzer(
                List.of(),
                new AnalysisComponent("whitespace", Map.of()),
                List.of(
                        new AnalysisComponent("lowercase", Map.of()),
                        new AnalysisComponent("stop", Map.of("words", "stop.txt")),
                        new AnalysisComponent("porterStem", Map.of())),
                Optional.of(tmp.toString())));
        assertThat(parsed("Documents The beta", custom)).isEqualTo("body:document body:beta");
        Analyzer shingles = new Analyzer(
                Optional.of(new StandardAnalyzer()),
                Optional.empty(),
                Optional.empty(),
                Optional.of(new Shingles(2, 2, "_", false, false, "-")),
                Optional.of(new LimitTokenCount(1, true)),
                List.of(),
                List.of());
        assertThat(parsed("alpha document gamma", shingles)).isEqualTo("body:alpha_document");
        WordSet documents = new WordSet(Optional.of(List.of("documents")), Optional.empty());
        assertThat(parsed("documents", preset(ENGLISH, Optional.empty(), Optional.empty(), Optional.empty())))
                .isEqualTo("body:document");
        assertThat(parsed("documents", preset(ENGLISH, Optional.of(documents), Optional.empty(), Optional.empty())))
                .isEqualTo("body:documents");
        StemDictionary gamma = new StemDictionary(Optional.of(Map.of("alpha", "gamma")), Optional.empty());
        assertThat(parsed(
                        "alpha",
                        preset(
                                "org.apache.lucene.analysis.nl.DutchAnalyzer",
                                Optional.empty(),
                                Optional.of(gamma),
                                Optional.empty())))
                .isEqualTo("body:gamma");
        Analyzer collation = preset(
                "org.apache.lucene.collation.CollationKeyAnalyzer",
                Optional.empty(),
                Optional.empty(),
                Optional.of(new Collation("de", Optional.of(CollationStrength.PRIMARY), Optional.empty())));
        assertThat(parsed("Alpha", collation)).isEqualTo(parsed("alpha", collation));

        RpcException romanian = backend.fails(
                Methods.START_SEARCH,
                params(RealBackend.classic(
                        "alpha",
                        analyzer(new CustomAnalyzer(
                                List.of(),
                                new AnalysisComponent("standard", Map.of()),
                                List.of(new AnalysisComponent("romanianNormalization", Map.of())),
                                Optional.empty())))),
                ErrorCode.NOT_SUPPORTED);
        assertThat(romanian)
                .hasMessage(
                        "Not supported with Lucene 9.12.3: Lucene 9.12.3 has no token filter 'romanianNormalization'");
        RpcException japanese = backend.fails(
                Methods.START_SEARCH,
                params(RealBackend.classic(
                        "alpha",
                        analyzer(new PresetAnalyzer(
                                "org.apache.lucene.analysis.ja.JapaneseAnalyzer",
                                Optional.empty(),
                                Optional.empty(),
                                OptionalInt.empty(),
                                Optional.empty(),
                                Optional.of(new JapaneseOptions(Optional.empty(), Optional.empty(), Optional.empty())),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty())))),
                ErrorCode.NOT_SUPPORTED);
        assertThat(japanese).hasMessageContaining("Lucene 9.12.3 has no analyzer JapaneseAnalyzer");
        RpcException size = backend.fails(
                Methods.START_SEARCH,
                params(RealBackend.classic(
                        "alpha",
                        new Analyzer(
                                Optional.of(new StandardAnalyzer()),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.of(new Shingles(1, 2, " ", true, true, "_")),
                                Optional.empty(),
                                List.of(),
                                List.of()))),
                ErrorCode.INVALID_ANALYZER);
        assertThat(size).hasMessage("Invalid analyzer: shingles: the minimum size must be at least 2, not 1");
        RpcException exclusions = backend.fails(
                Methods.START_SEARCH,
                params(RealBackend.classic(
                        "alpha",
                        preset(
                                "org.apache.lucene.analysis.core.KeywordAnalyzer",
                                Optional.of(documents),
                                Optional.empty(),
                                Optional.empty()))),
                ErrorCode.INVALID_ANALYZER);
        assertThat(exclusions).hasMessage("Invalid analyzer: KeywordAnalyzer takes no stem exclusions");
    }

    private static final String ENGLISH = "org.apache.lucene.analysis.en.EnglishAnalyzer";

    /** The query that a classic search of {@code expression} in {@code body} parses to. */
    private String parsed(String expression, Analyzer analyzer) throws Exception {
        return backend.search(RealBackend.classic(expression, analyzer), 10).query();
    }

    private static Analyzer analyzer(CustomAnalyzer custom) {
        return new Analyzer(
                Optional.empty(),
                Optional.empty(),
                Optional.of(custom),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                List.of());
    }

    private static Analyzer analyzer(PresetAnalyzer preset) {
        return new Analyzer(
                Optional.empty(),
                Optional.of(preset),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                List.of());
    }

    /** A preset with these stem exclusions, stem overrides and collation, and no other options. */
    private static Analyzer preset(
            String className,
            Optional<WordSet> stemExclusion,
            Optional<StemDictionary> stemOverride,
            Optional<Collation> collation) {
        return analyzer(new PresetAnalyzer(
                className,
                Optional.empty(),
                stemExclusion,
                OptionalInt.empty(),
                stemOverride,
                Optional.empty(),
                Optional.empty(),
                collation,
                Optional.empty()));
    }

    /** More-Like-This by text from {@code field}. */
    private static SearchQuery like(String field) {
        MoreLikeThisQuery body = RealBackend.moreLikeThis(OptionalInt.empty(), Optional.of("gamma"))
                .moreLikeThis()
                .orElseThrow();
        return new SearchQuery(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(new MoreLikeThisQuery(
                        body.docId(),
                        body.text(),
                        List.of(field),
                        body.minTermFreq(),
                        body.minDocFreq(),
                        body.maxDocFreq(),
                        body.maxQueryTerms(),
                        body.boost(),
                        body.analyzer(),
                        body.minWordLen(),
                        body.maxWordLen(),
                        body.stopWords())),
                Optional.empty(),
                Optional.empty());
    }

    private static BytesValue text(String text) {
        return new BytesValue(text, false);
    }

    private static SearchQuery docValues(
            String field,
            Optional<DocValuesNumber> number,
            Optional<List<BytesValue>> values,
            Optional<DocValuesRange> range,
            Optional<BytesValue> prefix) {
        return RealBackend.docValues(new DocValuesQuery(field, number, values, range, prefix));
    }

    /** Doc values queries of each type (ADR 0066), on what leia-lucene9's writer puts in them. */
    @Test
    void searchesDocValues() throws Exception {
        open(testIndex("lucene8"));
        Optional<DocValuesNumber> longs = Optional.of(DocValuesNumber.LONG);
        // dv_num is i, but 400 for document 4 (3 of the index), and none for document 3.
        assertThat(searchFilterAndExplain(docValues(
                        "dv_num",
                        longs,
                        Optional.empty(),
                        Optional.of(new DocValuesRange(Optional.of(text("2")), Optional.of(text("6")), true, true)),
                        Optional.empty())))
                .containsExactlyInAnyOrder(5, 6);
        assertThat(searchFilterAndExplain(docValues(
                        "dv_num", longs, Optional.of(List.of(text("400"))), Optional.empty(), Optional.empty())))
                .containsExactly(3);
        assertThat(searchFilterAndExplain(docValues(
                        "dv_sorted_num",
                        longs,
                        Optional.of(List.of(text("-5"), text("-7"))),
                        Optional.empty(),
                        Optional.empty())))
                .containsExactlyInAnyOrder(5, 7);
        assertThat(searchFilterAndExplain(docValues(
                        "dv_num",
                        Optional.of(DocValuesNumber.SORTABLE_DOUBLE),
                        Optional.empty(),
                        Optional.of(new DocValuesRange(Optional.of(text("0")), Optional.of(text("1")), true, true)),
                        Optional.empty())))
                // As sortable bits, a small long is a tiny positive double.
                .containsExactlyInAnyOrder(0, 3, 5, 6, 7);
        assertThat(searchFilterAndExplain(docValues(
                        "dv_sorted",
                        Optional.empty(),
                        Optional.of(List.of(text("s1"))),
                        Optional.empty(),
                        Optional.empty())))
                .containsExactlyInAnyOrder(0, 3, 7);
        assertThat(searchFilterAndExplain(docValues(
                        "dv_set", Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(text("y5")))))
                .containsExactly(5);
        // dv_bin is b<i>: 62 is b, 35 to 37 are 5 to 7.
        assertThat(searchFilterAndExplain(docValues(
                        "dv_bin",
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(new DocValuesRange(
                                Optional.of(new BytesValue("6235", true)), Optional.of(text("b7")), true, false)),
                        Optional.empty())))
                .containsExactlyInAnyOrder(5, 6);

        backend.fails(
                Methods.START_SEARCH,
                params(docValues(
                        "nothing", longs, Optional.of(List.of(text("1"))), Optional.empty(), Optional.empty())),
                ErrorCode.FIELD_NOT_FOUND);
        RpcException none = backend.fails(
                Methods.START_SEARCH,
                params(docValues("body", longs, Optional.of(List.of(text("1"))), Optional.empty(), Optional.empty())),
                ErrorCode.INVALID_PARAMS);
        assertThat(none).hasMessage("Invalid params: the field 'body' has no doc values");
        backend.fails(
                Methods.START_SEARCH,
                params(docValues("dv_num", longs, Optional.empty(), Optional.empty(), Optional.empty())),
                ErrorCode.INVALID_PARAMS);
        backend.fails(
                Methods.START_SEARCH,
                params(docValues("dv_num", longs, Optional.empty(), Optional.empty(), Optional.of(text("1")))),
                ErrorCode.INVALID_PARAMS);
        backend.fails(
                Methods.START_SEARCH,
                params(docValues(
                        "dv_num",
                        longs,
                        Optional.of(List.of(new BytesValue("01", true))),
                        Optional.empty(),
                        Optional.empty())),
                ErrorCode.INVALID_PARAMS);
        backend.fails(
                Methods.START_SEARCH,
                params(docValues(
                        "dv_bin",
                        Optional.empty(),
                        Optional.of(List.of(new BytesValue("zz", true))),
                        Optional.empty(),
                        Optional.empty())),
                ErrorCode.INVALID_PARAMS);
        RpcException binary = backend.fails(
                Methods.START_SEARCH,
                params(docValues("dv_bin", longs, Optional.of(List.of(text("1"))), Optional.empty(), Optional.empty())),
                ErrorCode.INVALID_PARAMS);
        assertThat(binary)
                .hasMessage("Invalid params: the field 'dv_bin' has BINARY doc values, which hold bytes, not numbers");
    }
}
