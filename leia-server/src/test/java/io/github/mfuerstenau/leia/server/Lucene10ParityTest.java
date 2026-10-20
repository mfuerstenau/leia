package io.github.mfuerstenau.leia.server;

import static io.github.mfuerstenau.leia.server.RealBackend.BM25;
import static io.github.mfuerstenau.leia.server.RealBackend.DEFAULTS;
import static io.github.mfuerstenau.leia.server.RealBackend.STANDARD;
import static io.github.mfuerstenau.leia.server.RealBackend.classic;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.analysis.AnalysisComponent;
import io.github.mfuerstenau.leia.protocol.analysis.AnalyzeParams;
import io.github.mfuerstenau.leia.protocol.analysis.Analyzer;
import io.github.mfuerstenau.leia.protocol.analysis.Collation;
import io.github.mfuerstenau.leia.protocol.analysis.CollationStrength;
import io.github.mfuerstenau.leia.protocol.analysis.CustomAnalyzer;
import io.github.mfuerstenau.leia.protocol.analysis.FieldAnalyzer;
import io.github.mfuerstenau.leia.protocol.analysis.FieldStopWords;
import io.github.mfuerstenau.leia.protocol.analysis.GetAnalysisComponentsParams;
import io.github.mfuerstenau.leia.protocol.analysis.GetAutoStopWordsResult;
import io.github.mfuerstenau.leia.protocol.analysis.LimitTokenCount;
import io.github.mfuerstenau.leia.protocol.analysis.PresetAnalyzer;
import io.github.mfuerstenau.leia.protocol.analysis.Shingles;
import io.github.mfuerstenau.leia.protocol.analysis.StandardAnalyzer;
import io.github.mfuerstenau.leia.protocol.analysis.StartAutoStopWordsParams;
import io.github.mfuerstenau.leia.protocol.analysis.StemDictionary;
import io.github.mfuerstenau.leia.protocol.analysis.WordSet;
import io.github.mfuerstenau.leia.protocol.check.GetCheckIndexParams;
import io.github.mfuerstenau.leia.protocol.check.GetCheckIndexResult;
import io.github.mfuerstenau.leia.protocol.check.StartCheckIndexParams;
import io.github.mfuerstenau.leia.protocol.commits.Commit;
import io.github.mfuerstenau.leia.protocol.commits.GetCommitParams;
import io.github.mfuerstenau.leia.protocol.commits.GetCommitsParams;
import io.github.mfuerstenau.leia.protocol.document.BytesValue;
import io.github.mfuerstenau.leia.protocol.document.GetDocumentParams;
import io.github.mfuerstenau.leia.protocol.document.GetStoredValueParams;
import io.github.mfuerstenau.leia.protocol.document.GetTermVectorParams;
import io.github.mfuerstenau.leia.protocol.filter.FilterPattern;
import io.github.mfuerstenau.leia.protocol.filter.StartFilterParams;
import io.github.mfuerstenau.leia.protocol.index.GetIndexStatsParams;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexParams;
import io.github.mfuerstenau.leia.protocol.job.JobParams;
import io.github.mfuerstenau.leia.protocol.job.JobStarted;
import io.github.mfuerstenau.leia.protocol.points.GetPointCellParams;
import io.github.mfuerstenau.leia.protocol.points.GetPointTreeParams;
import io.github.mfuerstenau.leia.protocol.search.ClassicQuery;
import io.github.mfuerstenau.leia.protocol.search.ClassicSimilarity;
import io.github.mfuerstenau.leia.protocol.search.DateResolution;
import io.github.mfuerstenau.leia.protocol.search.DocSort;
import io.github.mfuerstenau.leia.protocol.search.DocValuesNumber;
import io.github.mfuerstenau.leia.protocol.search.DocValuesQuery;
import io.github.mfuerstenau.leia.protocol.search.DocValuesRange;
import io.github.mfuerstenau.leia.protocol.search.FieldSort;
import io.github.mfuerstenau.leia.protocol.search.HighlightOptions;
import io.github.mfuerstenau.leia.protocol.search.KnnQuery;
import io.github.mfuerstenau.leia.protocol.search.MoreLikeThisQuery;
import io.github.mfuerstenau.leia.protocol.search.ParserOptions;
import io.github.mfuerstenau.leia.protocol.search.PointField;
import io.github.mfuerstenau.leia.protocol.search.PointType;
import io.github.mfuerstenau.leia.protocol.search.QueryOperator;
import io.github.mfuerstenau.leia.protocol.search.ScoreSort;
import io.github.mfuerstenau.leia.protocol.search.SearchQuery;
import io.github.mfuerstenau.leia.protocol.search.Similarity;
import io.github.mfuerstenau.leia.protocol.search.SortKey;
import io.github.mfuerstenau.leia.protocol.search.SortMissing;
import io.github.mfuerstenau.leia.protocol.search.SortSelector;
import io.github.mfuerstenau.leia.protocol.search.SortType;
import io.github.mfuerstenau.leia.protocol.search.StandardQuery;
import io.github.mfuerstenau.leia.protocol.search.StartExplainParams;
import io.github.mfuerstenau.leia.protocol.search.StartHighlightParams;
import io.github.mfuerstenau.leia.protocol.search.StartSearchParams;
import io.github.mfuerstenau.leia.protocol.search.StartStoredScanParams;
import io.github.mfuerstenau.leia.protocol.search.TermQuery;
import io.github.mfuerstenau.leia.protocol.terms.GetExportTermsResult;
import io.github.mfuerstenau.leia.protocol.terms.GetFieldTermScanResult;
import io.github.mfuerstenau.leia.protocol.terms.GetPositionsParams;
import io.github.mfuerstenau.leia.protocol.terms.GetPostingsParams;
import io.github.mfuerstenau.leia.protocol.terms.GetTermsParams;
import io.github.mfuerstenau.leia.protocol.terms.StartExportTermsParams;
import io.github.mfuerstenau.leia.protocol.terms.StartFieldTermScanParams;
import io.github.mfuerstenau.leia.protocol.terms.TopTerm;
import io.github.mfuerstenau.leia.server.index.Routing;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Lucene 10 through leia-core against Lucene 10 through leia-lucene10's {@code Lucene10Runtime} (ADR
 * 0065): two backends on the same index, one routing as leia does, the other sending indexes of
 * Lucene 9 and 10 to the runtime, make the same calls, and their results are compared. Timings are
 * left out. Each call whose results differ must be in {@link #EXPECTED}, with the reason; the list is
 * what the runtime interface lacks (step 2 of ADR 0065), so a call that no longer differs must leave
 * it.
 *
 * <p>{@code addJars} and {@code getJars} aren't compared: loading jars is global to the JVM, so the
 * second backend's {@code addJars} would find the jars loaded already. {@code JarsBackendTest} calls
 * them with the routing to the runtime (ADR 0068).
 */
class Lucene10ParityTest {

    /** The calls whose results differ, and why. */
    private static final Map<String, String> EXPECTED = Map.ofEntries(
            Map.entry(
                    "openIndex",
                    "the features: SEARCH needs the parser's dates and fuzzy edits, which the interface lacks"
                            + " (step 2d); DOC_VALUES only where a field has doc values, as for"
                            + " every runtime, while leia-core names it always (until step 3)"),
            Map.entry(
                    "getIndexStats",
                    "the Directory class: RuntimeIndex.Stats doesn't name it, so the kind it was opened through"
                            + " stands in for it"),
            Map.entry(
                    "startSearch fuzzyMaxEdits", "Search.ParserOptions has no fuzzy edits: a runtime always allows 2"),
            Map.entry(
                    "startSearch dates",
                    "Search.ParserOptions has no date resolution, locale or time zone (ADR 0026)"));

    /** The prefix of the calls that fail with leia-core, with {@code INVALID_ANALYZER}, as they must with the runtime. */
    private static final String INVALID = "startSearch invalid ";

    /**
     * What the calls on an index use.
     *
     * @param docIds documents to read, a deleted one among them
     * @param point a field with points
     * @param sorts fields to sort by, with their type
     * @param knn a field with KNN vectors, and a document with one
     * @param docValues a field with sorted doc values
     * @param numbers a field with numeric doc values of longs
     * @param dates a field of dates as {@code DateTools} writes them
     */
    private record Index(
            String name,
            List<Integer> docIds,
            String point,
            Map<String, SortType> sorts,
            String knn,
            int knnDoc,
            Optional<String> docValues,
            Optional<String> numbers,
            Optional<String> dates) {}

    private static final Map<String, Index> INDEXES = Map.of(
            "demo",
            new Index(
                    "demo",
                    List.of(0, 3, 7, 37, 42),
                    "quantity",
                    Map.of("category", SortType.STRING, "quantity", SortType.LONG),
                    "embedding",
                    1,
                    Optional.of("category"),
                    Optional.of("quantity"),
                    Optional.of("published")),
            "lucene9",
            new Index(
                    "lucene9",
                    List.of(0, 1, 2),
                    "number",
                    Map.of(),
                    "vector",
                    0,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty()));

    private final Map<String, String> differences = new LinkedHashMap<>();
    /**
     * The calls made, and those that succeeded with leia-core at least once: one that always fails
     * would compare equal for nothing.
     */
    private final Set<String> calls = new LinkedHashSet<>();

    private final Set<String> succeeded = new HashSet<>();

    @ParameterizedTest
    @ValueSource(strings = {"demo", "lucene9"})
    void readsAsLeiaCoreDoes(String name, @TempDir Path dir) throws Exception {
        Index index = INDEXES.get(name);
        String path = RealBackend.testIndex(name).toString();
        try (RealBackend core = RealBackend.start();
                RealBackend runtime = RealBackend.start(new Routing(true))) {
            same("openIndex", core, runtime, Methods.OPEN_INDEX, new OpenIndexParams(path));
            same("getIndexStats", core, runtime, Methods.GET_INDEX_STATS, new GetIndexStatsParams());

            for (int docId : index.docIds()) {
                same("getDocument", core, runtime, Methods.GET_DOCUMENT, new GetDocumentParams(docId));
                same(
                        "getStoredValue",
                        core,
                        runtime,
                        Methods.GET_STORED_VALUE,
                        new GetStoredValueParams(docId, "body", 0));
                same("getTermVector", core, runtime, Methods.GET_TERM_VECTOR, new GetTermVectorParams(docId, "body"));
            }

            same("getTerms", core, runtime, Methods.GET_TERMS, new GetTermsParams("body", Optional.empty(), 50));
            same(
                    "getTerms",
                    core,
                    runtime,
                    Methods.GET_TERMS,
                    new GetTermsParams("body", Optional.of(new BytesValue("n", false)), 20));
            List<TopTerm> top = job(
                            core,
                            Methods.START_FIELD_TERM_SCAN,
                            new StartFieldTermScanParams("body", 10),
                            Methods.GET_FIELD_TERM_SCAN,
                            GetFieldTermScanResult::terms)
                    .topTerms();
            sameJob(
                    "startFieldTermScan",
                    core,
                    runtime,
                    Methods.START_FIELD_TERM_SCAN,
                    new StartFieldTermScanParams("body", 10),
                    Methods.GET_FIELD_TERM_SCAN,
                    GetFieldTermScanResult::terms);
            BytesValue term = new BytesValue(top.getFirst().term(), false);
            String word = top.getFirst().term();
            same(
                    "getPostings",
                    core,
                    runtime,
                    Methods.GET_POSTINGS,
                    new GetPostingsParams("body", term, 0, 100, Optional.empty()));
            for (int docId : index.docIds()) {
                same("getPositions", core, runtime, Methods.GET_POSITIONS, new GetPositionsParams(docId, "body", term));
            }

            same("getPointTree", core, runtime, Methods.GET_POINT_TREE, new GetPointTreeParams(index.point()));
            same(
                    "getPointCell",
                    core,
                    runtime,
                    Methods.GET_POINT_CELL,
                    new GetPointCellParams(index.point(), 0, List.of()));
            same(
                    "getPointCell",
                    core,
                    runtime,
                    Methods.GET_POINT_CELL,
                    new GetPointCellParams(index.point(), 0, List.of(0)));

            Similarity tfIdf = new Similarity(Optional.empty(), Optional.of(new ClassicSimilarity()), Optional.empty());
            SearchQuery classic = classic(word + " OR body:" + top.get(1).term());
            SearchQuery termQuery = query(Optional.empty(), Optional.of(new TermQuery("body", term)), Optional.empty());
            SearchQuery knn = query(
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(new KnnQuery(
                            index.knn(),
                            OptionalInt.of(index.knnDoc()),
                            Optional.empty(),
                            OptionalInt.of(5),
                            false,
                            Optional.empty())));
            SearchQuery exactKnn = query(
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(new KnnQuery(
                            index.knn(),
                            OptionalInt.of(index.knnDoc()),
                            Optional.empty(),
                            OptionalInt.empty(),
                            true,
                            Optional.of(classic(word).classic().orElseThrow()))));
            for (SearchQuery query : List.of(classic, termQuery, knn, exactKnn)) {
                for (Similarity similarity : List.of(BM25, tfIdf)) {
                    same(
                            "startSearch",
                            core,
                            runtime,
                            new StartSearchParams(
                                    query, similarity, List.of(), 0, 20, true, Optional.empty(), Optional.empty()));
                    for (int docId : index.docIds()) {
                        sameJob(
                                "startExplain",
                                core,
                                runtime,
                                Methods.START_EXPLAIN,
                                new StartExplainParams(query, similarity, docId),
                                Methods.GET_EXPLAIN,
                                result -> result.explanation());
                    }
                }
            }
            for (Map.Entry<String, SortType> sort : index.sorts().entrySet()) {
                same(
                        "startSearch sorted",
                        core,
                        runtime,
                        new StartSearchParams(
                                classic,
                                BM25,
                                List.of(
                                        new SortKey(
                                                Optional.of(new FieldSort(
                                                        sort.getKey(),
                                                        sort.getValue(),
                                                        true,
                                                        SortSelector.MIN,
                                                        SortMissing.LAST)),
                                                Optional.empty(),
                                                Optional.empty()),
                                        new SortKey(
                                                Optional.empty(), Optional.of(new ScoreSort(false)), Optional.empty()),
                                        new SortKey(
                                                Optional.empty(), Optional.empty(), Optional.of(new DocSort(false)))),
                                5,
                                10,
                                true,
                                Optional.of(List.of("body")),
                                Optional.empty()));
            }
            for (Map.Entry<String, SearchQuery> query :
                    searches(word, index, dir).entrySet()) {
                same(
                        query.getKey(),
                        core,
                        runtime,
                        new StartSearchParams(
                                query.getValue(), BM25, List.of(), 0, 20, true, Optional.empty(), Optional.empty()));
            }
            HighlightOptions highlight = new HighlightOptions(3, false, STANDARD);
            // The queries of ADR 0066 in each job that takes a query.
            for (Map.Entry<String, SearchQuery> query :
                    searches(word, index, dir).entrySet()) {
                String kind = query.getKey().substring("startSearch ".length());
                if (!OTHER_QUERIES.contains(kind)) {
                    continue;
                }
                same(
                        "startFilter " + kind,
                        core.filter(new StartFilterParams(Optional.of(query.getValue()), Optional.empty(), false)),
                        runtime.filter(new StartFilterParams(Optional.of(query.getValue()), Optional.empty(), false)));
                for (int docId : index.docIds()) {
                    sameJob(
                            "startExplain " + kind,
                            core,
                            runtime,
                            Methods.START_EXPLAIN,
                            new StartExplainParams(query.getValue(), BM25, docId),
                            Methods.GET_EXPLAIN,
                            result -> result.explanation());
                    sameJob(
                            "startHighlight " + kind,
                            core,
                            runtime,
                            Methods.START_HIGHLIGHT,
                            new StartHighlightParams(query.getValue(), docId, Optional.empty(), highlight),
                            Methods.GET_HIGHLIGHT,
                            result -> result.highlight());
                }
                same(
                        "startSearch highlighted " + kind,
                        core,
                        runtime,
                        new StartSearchParams(
                                query.getValue(),
                                BM25,
                                List.of(),
                                0,
                                10,
                                true,
                                Optional.empty(),
                                Optional.of(highlight)));
            }
            same(
                    "startSearch highlighted",
                    core,
                    runtime,
                    new StartSearchParams(
                            classic, BM25, List.of(), 0, 10, true, Optional.empty(), Optional.of(highlight)));
            for (int docId : index.docIds()) {
                sameJob(
                        "startHighlight",
                        core,
                        runtime,
                        Methods.START_HIGHLIGHT,
                        new StartHighlightParams(classic, docId, Optional.empty(), highlight),
                        Methods.GET_HIGHLIGHT,
                        result -> result.highlight());
                sameJob(
                        "startReconstruct",
                        core,
                        runtime,
                        Methods.START_RECONSTRUCT,
                        new io.github.mfuerstenau.leia.protocol.document.StartReconstructParams(
                                docId, Optional.empty()),
                        Methods.GET_RECONSTRUCT,
                        result -> result.result());
            }

            for (boolean deleted : List.of(false, true)) {
                StartStoredScanParams scan = new StartStoredScanParams(word, true, Optional.empty(), deleted);
                same("startStoredScan", core.storedScan(scan), runtime.storedScan(scan));
                same(
                        "startFilter query",
                        core.filter(new StartFilterParams(Optional.of(classic), Optional.empty(), deleted)),
                        runtime.filter(new StartFilterParams(Optional.of(classic), Optional.empty(), deleted)));
                StartFilterParams pattern = new StartFilterParams(
                        Optional.empty(), Optional.of(new FilterPattern(word, true, Optional.empty())), deleted);
                same("startFilter pattern", core.filter(pattern), runtime.filter(pattern));
            }

            Path coreTerms = dir.resolve("core.txt");
            Path runtimeTerms = dir.resolve("runtime.txt");
            GetExportTermsResult coreExport = job(
                    core,
                    Methods.START_EXPORT_TERMS,
                    new StartExportTermsParams("body", coreTerms.toString()),
                    Methods.GET_EXPORT_TERMS,
                    result -> result.result().map(_ -> result));
            GetExportTermsResult runtimeExport = job(
                    runtime,
                    Methods.START_EXPORT_TERMS,
                    new StartExportTermsParams("body", runtimeTerms.toString()),
                    Methods.GET_EXPORT_TERMS,
                    result -> result.result().map(_ -> result));
            same(
                    "startExportTerms",
                    coreExport.result().orElseThrow().terms(),
                    runtimeExport.result().orElseThrow().terms());
            same("startExportTerms file", Files.readString(coreTerms), Files.readString(runtimeTerms));

            sameJob(
                    "startAutoStopWords",
                    core,
                    runtime,
                    Methods.START_AUTO_STOP_WORDS,
                    new StartAutoStopWordsParams(List.of(), OptionalInt.empty(), OptionalDouble.of(0.3)),
                    Methods.GET_AUTO_STOP_WORDS,
                    GetAutoStopWordsResult::stopWords);

            same(
                    "getAnalysisComponents",
                    core,
                    runtime,
                    Methods.GET_ANALYSIS_COMPONENTS,
                    new GetAnalysisComponentsParams());
            for (Map.Entry<String, AnalyzeParams> analyze : analyses(word, dir).entrySet()) {
                same(analyze.getKey(), core, runtime, Methods.ANALYZE, analyze.getValue());
            }

            same("getCommits", core, runtime, Methods.GET_COMMITS, new GetCommitsParams(OptionalInt.empty()));
            for (Commit commit : core.call(Methods.GET_COMMITS, new GetCommitsParams(OptionalInt.empty()))
                    .commits()) {
                same(
                        "getCommit",
                        core,
                        runtime,
                        Methods.GET_COMMIT,
                        new GetCommitParams(commit.generation(), OptionalInt.empty()));
            }
            for (int level = StartCheckIndexParams.MIN_LEVEL; level <= StartCheckIndexParams.MAX_LEVEL; level++) {
                same("startCheckIndex", check(core, level), check(runtime, level));
            }
        }
        assertThat(succeeded)
                .as("the calls that succeeded with leia-core")
                .containsAll(
                        calls.stream().filter(call -> !call.startsWith(INVALID)).toList())
                .noneMatch(call -> call.startsWith(INVALID));
        Set<String> expected = new HashSet<>(EXPECTED.keySet());
        if (index.dates().isEmpty()) {
            expected.remove("startSearch dates");
        }
        assertThat(differences.keySet())
                .as("differences, of which only those of EXPECTED may be:%n%s", report())
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    /** The kinds of {@link #searches} that ADR 0066 adds, which every job that takes a query runs too. */
    private static final Set<String> OTHER_QUERIES = Set.of(
            "standard",
            "standard points",
            "moreLikeThis",
            "moreLikeThis text",
            "docValues",
            "docValues numbers",
            "docValues range");

    /** Searches with what {@code classic} doesn't vary: the parser's other options, analyzers, other queries. */
    private static Map<String, SearchQuery> searches(String word, Index index, Path dir) throws IOException {
        ParserOptions days = new ParserOptions(
                false,
                0,
                0,
                true,
                Optional.of(DateResolution.DAY),
                List.of(),
                Optional.of("en-US"),
                Optional.of("UTC"));
        Analyzer custom = analyzer(
                Optional.empty(),
                Optional.of(new CustomAnalyzer(
                        List.of(),
                        new AnalysisComponent("standard", Map.of()),
                        List.of(new AnalysisComponent("lowercase", Map.of())),
                        Optional.empty())),
                Optional.empty(),
                Optional.empty());
        Analyzer shingles = analyzer(
                Optional.of(new StandardAnalyzer()),
                Optional.empty(),
                Optional.of(new Shingles(2, 2, " ", true, true, "_")),
                Optional.empty());
        Analyzer consumeAll = analyzer(
                Optional.of(new StandardAnalyzer()),
                Optional.empty(),
                Optional.empty(),
                Optional.of(new LimitTokenCount(1, true)));
        Analyzer english = analyzer(
                Optional.of(new PresetAnalyzer(
                        "org.apache.lucene.analysis.en.EnglishAnalyzer",
                        Optional.of(new WordSet(Optional.of(List.of("and", word)), Optional.empty())),
                        Optional.empty(),
                        OptionalInt.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty())),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
        Analyzer excluded = analyzer(
                Optional.of(new PresetAnalyzer(
                        "org.apache.lucene.analysis.en.EnglishAnalyzer",
                        Optional.empty(),
                        Optional.of(new WordSet(Optional.of(List.of("chairs")), Optional.empty())),
                        OptionalInt.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty())),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
        Map<String, SearchQuery> searches = new LinkedHashMap<>();
        // A '#' line is a word too, as Lucene 10's WordlistLoader reads it.
        Path stopwords = Files.writeString(dir.resolve("stopwords.txt"), "# not a comment\n  and \n\n" + word + "\n");
        Path config = Files.createDirectories(dir.resolve("config"));
        Files.writeString(config.resolve("mapping.txt"), "\"chairs\" => \"" + word + "\"\n");
        Files.writeString(config.resolve("stop.txt"), word + "\n");
        searches.put("startSearch preset", classicWith(word + " chairs", "body", 2, false, english, DEFAULTS));
        searches.put(
                "startSearch preset stop words file",
                classicWith(
                        word + " and chairs",
                        "body",
                        2,
                        false,
                        preset(
                                "org.apache.lucene.analysis.en.EnglishAnalyzer",
                                Optional.of(new WordSet(Optional.empty(), Optional.of(stopwords.toString()))),
                                Optional.empty(),
                                Optional.empty()),
                        DEFAULTS));
        searches.put(
                "startSearch preset stem override",
                classicWith(
                        word + " fietsen",
                        "body",
                        2,
                        false,
                        preset(
                                "org.apache.lucene.analysis.nl.DutchAnalyzer",
                                Optional.empty(),
                                Optional.of(new StemDictionary(Optional.of(Map.of(word, "chair")), Optional.empty())),
                                Optional.empty()),
                        DEFAULTS));
        searches.put(
                "startSearch preset collation",
                classicWith(
                        word,
                        "body",
                        2,
                        false,
                        preset(
                                "org.apache.lucene.collation.CollationKeyAnalyzer",
                                Optional.empty(),
                                Optional.empty(),
                                Optional.of(
                                        new Collation("de", Optional.of(CollationStrength.PRIMARY), Optional.empty()))),
                        DEFAULTS));
        searches.put(
                "startSearch custom analyzer config",
                classicWith(
                        word + " chairs",
                        "body",
                        2,
                        false,
                        analyzer(
                                Optional.empty(),
                                Optional.of(new CustomAnalyzer(
                                        List.of(new AnalysisComponent("mapping", Map.of("mapping", "mapping.txt"))),
                                        new AnalysisComponent("standard", Map.of()),
                                        List.of(
                                                new AnalysisComponent("lowercase", Map.of()),
                                                new AnalysisComponent("stop", Map.of("words", "stop.txt"))),
                                        Optional.of(config.toString()))),
                                Optional.empty(),
                                Optional.empty()),
                        DEFAULTS));
        searches.put(
                "startSearch shingles limited",
                classicWith(
                        "\"" + word + " chairs x\"",
                        "body",
                        2,
                        false,
                        analyzer(
                                Optional.of(new StandardAnalyzer()),
                                Optional.empty(),
                                Optional.of(new Shingles(2, 3, "_", false, true, "-")),
                                Optional.of(new LimitTokenCount(3, false))),
                        DEFAULTS));
        searches.put(
                INVALID + "unknown filter",
                classicWith(
                        word,
                        "body",
                        2,
                        false,
                        analyzer(
                                Optional.empty(),
                                Optional.of(new CustomAnalyzer(
                                        List.of(),
                                        new AnalysisComponent("standard", Map.of()),
                                        List.of(new AnalysisComponent("noSuchFilter", Map.of())),
                                        Optional.empty())),
                                Optional.empty(),
                                Optional.empty()),
                        DEFAULTS));
        searches.put(
                INVALID + "shingle size",
                classicWith(
                        word,
                        "body",
                        2,
                        false,
                        analyzer(
                                Optional.of(new StandardAnalyzer()),
                                Optional.empty(),
                                Optional.of(new Shingles(1, 2, " ", true, true, "_")),
                                Optional.empty()),
                        DEFAULTS));
        searches.put(
                INVALID + "stop words file",
                classicWith(
                        word,
                        "body",
                        2,
                        false,
                        preset(
                                "org.apache.lucene.analysis.en.EnglishAnalyzer",
                                Optional.of(new WordSet(
                                        Optional.empty(),
                                        Optional.of(dir.resolve("none.txt").toString()))),
                                Optional.empty(),
                                Optional.empty()),
                        DEFAULTS));
        searches.put(
                "startSearch preset stem exclusion",
                classicWith(word + " chairs", "body", 2, false, excluded, DEFAULTS));
        searches.put("startSearch fuzzyMaxEdits", classicWith(word + "~", "body", 1, false, STANDARD, DEFAULTS));
        searches.put(
                "startSearch splitOnWhitespace",
                classicWith("\"" + word + " x\" " + word, "body", 2, true, STANDARD, DEFAULTS));
        index.dates()
                .ifPresent(field -> searches.put(
                        "startSearch dates",
                        // Dates in the short format of the locale, which the resolution turns into terms.
                        classicWith("[1/1/26 TO 3/1/26]", field, 2, false, STANDARD, days)));
        searches.put("startSearch custom analyzer", classicWith(word, "body", 2, false, custom, DEFAULTS));
        searches.put("startSearch shingles", classicWith(word, "body", 2, false, shingles, DEFAULTS));
        searches.put("startSearch consumeAllTokens", classicWith(word + " x", "body", 2, false, consumeAll, DEFAULTS));
        searches.put(
                "startSearch standard",
                other(
                        Optional.of(new StandardQuery(
                                word,
                                "body",
                                QueryOperator.OR,
                                DEFAULTS,
                                List.of(new PointField(index.point(), PointType.INT)),
                                STANDARD)),
                        Optional.empty(),
                        Optional.empty()));
        searches.put(
                "startSearch moreLikeThis",
                other(
                        Optional.empty(),
                        Optional.of(new MoreLikeThisQuery(
                                OptionalInt.of(index.docIds().get(1)),
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
                                List.of())),
                        Optional.empty()));
        searches.put(
                "startSearch standard points",
                other(
                        Optional.of(new StandardQuery(
                                index.point() + ":[0 TO 30] OR " + word,
                                "body",
                                QueryOperator.OR,
                                DEFAULTS,
                                List.of(new PointField(index.point(), PointType.INT)),
                                STANDARD)),
                        Optional.empty(),
                        Optional.empty()));
        searches.put(
                "startSearch moreLikeThis text",
                other(
                        Optional.empty(),
                        Optional.of(new MoreLikeThisQuery(
                                OptionalInt.empty(),
                                Optional.of(word + " " + word + " chairs"),
                                List.of("body", "id"),
                                1,
                                1,
                                OptionalInt.empty(),
                                25,
                                true,
                                STANDARD,
                                0,
                                OptionalInt.empty(),
                                List.of())),
                        Optional.empty()));
        index.numbers().ifPresent(field -> {
            searches.put(
                    "startSearch docValues numbers",
                    other(
                            Optional.empty(),
                            Optional.empty(),
                            Optional.of(new DocValuesQuery(
                                    field,
                                    Optional.of(DocValuesNumber.LONG),
                                    Optional.of(List.of(new BytesValue("1", false), new BytesValue("2", false))),
                                    Optional.empty(),
                                    Optional.empty()))));
            searches.put(
                    "startSearch docValues range",
                    other(
                            Optional.empty(),
                            Optional.empty(),
                            Optional.of(new DocValuesQuery(
                                    field,
                                    Optional.of(DocValuesNumber.LONG),
                                    Optional.empty(),
                                    Optional.of(new DocValuesRange(
                                            Optional.empty(), Optional.of(new BytesValue("5", false)), true, false)),
                                    Optional.empty()))));
        });
        index.docValues()
                .ifPresent(field -> searches.put(
                        "startSearch docValues",
                        other(
                                Optional.empty(),
                                Optional.empty(),
                                Optional.of(new DocValuesQuery(
                                        field,
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.of(new BytesValue("a", false)))))));
        return searches;
    }

    /**
     * The calls of the analysis tab: each kind of analyzer, and a field's own analyzer and auto stop
     * words.
     */
    private static Map<String, AnalyzeParams> analyses(String word, Path dir) throws IOException {
        Path config = Files.createDirectories(dir.resolve("analysis"));
        Files.writeString(config.resolve("mapping.txt"), "\"ß\" => \"ss\"\n");
        String text = "Die Straße and the " + word + " chairs";
        Analyzer custom = analyzer(
                Optional.empty(),
                Optional.of(new CustomAnalyzer(
                        List.of(
                                new AnalysisComponent("mapping", Map.of("mapping", "mapping.txt")),
                                new AnalysisComponent("htmlStrip", Map.of())),
                        new AnalysisComponent("whitespace", Map.of()),
                        List.of(
                                new AnalysisComponent("lowercase", Map.of()),
                                new AnalysisComponent("porterStem", Map.of())),
                        Optional.of(config.toString()))),
                Optional.empty(),
                Optional.empty());
        Analyzer wrapped = analyzer(
                Optional.of(new StandardAnalyzer()),
                Optional.empty(),
                Optional.of(new Shingles(2, 2, " ", true, true, "_")),
                Optional.of(new LimitTokenCount(4, true)));
        Analyzer english = preset(
                "org.apache.lucene.analysis.en.EnglishAnalyzer", Optional.empty(), Optional.empty(), Optional.empty());
        Analyzer perField = new Analyzer(
                Optional.of(new StandardAnalyzer()),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of(new FieldAnalyzer("title", english)),
                List.of(new FieldStopWords("title", List.of("chair")), new FieldStopWords("body", List.of(word))));
        Map<String, AnalyzeParams> analyses = new LinkedHashMap<>();
        analyses.put("analyze standard", new AnalyzeParams(STANDARD, text, Optional.empty()));
        analyses.put("analyze preset", new AnalyzeParams(english, text, Optional.empty()));
        analyses.put("analyze custom", new AnalyzeParams(custom, "<b>" + text + "</b>", Optional.empty()));
        analyses.put("analyze wrapped", new AnalyzeParams(wrapped, text, Optional.empty()));
        analyses.put("analyze per field", new AnalyzeParams(perField, text, Optional.of("title")));
        analyses.put("analyze per field default", new AnalyzeParams(perField, text, Optional.of("body")));
        return analyses;
    }

    /** A preset with these word sets and options, and no others. */
    private static Analyzer preset(
            String className,
            Optional<WordSet> stopwords,
            Optional<StemDictionary> stemOverride,
            Optional<Collation> collation) {
        return analyzer(
                Optional.of(new PresetAnalyzer(
                        className,
                        stopwords,
                        Optional.empty(),
                        OptionalInt.empty(),
                        stemOverride,
                        Optional.empty(),
                        Optional.empty(),
                        collation,
                        Optional.empty())),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    /** An analyzer of {@code base}: a {@link StandardAnalyzer} or a {@link PresetAnalyzer}, or empty. */
    private static Analyzer analyzer(
            Optional<?> base,
            Optional<CustomAnalyzer> custom,
            Optional<Shingles> shingles,
            Optional<LimitTokenCount> limit) {
        return new Analyzer(
                base.filter(StandardAnalyzer.class::isInstance).map(StandardAnalyzer.class::cast),
                base.filter(PresetAnalyzer.class::isInstance).map(PresetAnalyzer.class::cast),
                custom,
                shingles,
                limit,
                List.of(),
                List.of());
    }

    private static SearchQuery classicWith(
            String expression,
            String field,
            int fuzzyMaxEdits,
            boolean splitOnWhitespace,
            Analyzer analyzer,
            ParserOptions options) {
        return query(
                Optional.of(new ClassicQuery(
                        expression,
                        field,
                        QueryOperator.OR,
                        options,
                        fuzzyMaxEdits,
                        splitOnWhitespace,
                        false,
                        analyzer)),
                Optional.empty(),
                Optional.empty());
    }

    /** A query of the standard parser, More-Like-This or doc values. */
    private static SearchQuery other(
            Optional<StandardQuery> standard,
            Optional<MoreLikeThisQuery> moreLikeThis,
            Optional<DocValuesQuery> docValues) {
        return new SearchQuery(Optional.empty(), standard, Optional.empty(), moreLikeThis, docValues, Optional.empty());
    }

    private String report() {
        StringBuilder text = new StringBuilder();
        differences.forEach((call, difference) -> text.append("--- ")
                .append(call)
                .append(":\n")
                .append(difference)
                .append('\n'));
        return text.toString();
    }

    private static SearchQuery query(Optional<ClassicQuery> classic, Optional<TermQuery> term, Optional<KnnQuery> knn) {
        return new SearchQuery(classic, Optional.empty(), term, Optional.empty(), Optional.empty(), knn);
    }

    /** Calls {@code method} on both, and compares the results, or the failures. */
    private <P, R> void same(String call, RealBackend core, RealBackend runtime, RpcMethod<P, R> method, P params) {
        same(call, outcome(() -> core.call(method, params)), outcome(() -> runtime.call(method, params)));
    }

    private void same(String call, RealBackend core, RealBackend runtime, StartSearchParams params) {
        same(call, outcome(() -> core.search(params)), outcome(() -> runtime.search(params)));
    }

    private <P, G, T> void sameJob(
            String call,
            RealBackend core,
            RealBackend runtime,
            RpcMethod<P, JobStarted> start,
            P params,
            RpcMethod<JobParams, G> get,
            Function<G, Optional<T>> done) {
        same(
                call,
                outcome(() -> job(core, start, params, get, done)),
                outcome(() -> job(runtime, start, params, get, done)));
    }

    private static <P, G, T> T job(
            RealBackend backend,
            RpcMethod<P, JobStarted> start,
            P params,
            RpcMethod<JobParams, G> get,
            Function<G, Optional<T>> done)
            throws Exception {
        return backend.job(start, params, get, done);
    }

    /** The outcome of a check at {@code level}, without its report, whose lines name the Lucene. */
    private static Object check(RealBackend backend, int level) throws Exception {
        long jobId = backend.call(Methods.START_CHECK_INDEX, new StartCheckIndexParams(OptionalInt.of(level)))
                .jobId();
        int lines = 0;
        while (true) {
            GetCheckIndexResult poll = backend.call(Methods.GET_CHECK_INDEX, new GetCheckIndexParams(jobId, lines));
            lines += poll.lines().size();
            if (poll.result().isPresent()) {
                return poll.result().get();
            }
            Thread.sleep(5);
        }
    }

    private static final String FAILS = "fails with ";

    @FunctionalInterface
    private interface Call {
        Object call() throws Exception;
    }

    /** What {@code call} returns, or the code and message of the {@link RpcException} it fails with. */
    private static Object outcome(Call call) {
        try {
            return call.call();
        } catch (RpcException e) {
            return FAILS + e.code() + ": " + e.getMessage();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    /** Compares two outcomes, timings and filter ids left out, and keeps the first difference of each call. */
    private void same(String call, Object core, Object runtime) {
        calls.add(call);
        if (!(core instanceof String text && text.startsWith(FAILS))) {
            succeeded.add(call);
        }
        if (call.startsWith(INVALID)) {
            assertThat(core).as(call).asString().startsWith(FAILS + ErrorCode.INVALID_ANALYZER);
        }
        try {
            // Swapped, since an unsorted map of the runtime must equal a sorted one of leia-core: the
            // UI orders the maps it shows itself. The message calls leia-core's outcome "actual".
            assertThat(core)
                    .usingRecursiveComparison()
                    .ignoringFieldsMatchingRegexes(".*tookMillis", ".*filterId")
                    .isEqualTo(runtime);
        } catch (AssertionError e) {
            differences.putIfAbsent(call, e.getMessage());
        }
    }
}
