package io.github.mfuerstenau.leia.server;

import static io.github.mfuerstenau.leia.server.RealBackend.BM25;
import static io.github.mfuerstenau.leia.server.RealBackend.classic;
import static io.github.mfuerstenau.leia.server.RealBackend.testIndex;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.analysis.AnalysisComponent;
import io.github.mfuerstenau.leia.protocol.analysis.Analyzer;
import io.github.mfuerstenau.leia.protocol.analysis.CustomAnalyzer;
import io.github.mfuerstenau.leia.protocol.analysis.FieldAnalyzer;
import io.github.mfuerstenau.leia.protocol.analysis.LimitTokenCount;
import io.github.mfuerstenau.leia.protocol.analysis.PresetAnalyzer;
import io.github.mfuerstenau.leia.protocol.analysis.Shingles;
import io.github.mfuerstenau.leia.protocol.analysis.StandardAnalyzer;
import io.github.mfuerstenau.leia.protocol.analysis.WordSet;
import io.github.mfuerstenau.leia.protocol.check.CheckIndexResult;
import io.github.mfuerstenau.leia.protocol.check.CheckTarget;
import io.github.mfuerstenau.leia.protocol.check.CheckTargetIndex;
import io.github.mfuerstenau.leia.protocol.check.GetCheckIndexParams;
import io.github.mfuerstenau.leia.protocol.check.GetCheckIndexResult;
import io.github.mfuerstenau.leia.protocol.check.GetCheckTargetsParams;
import io.github.mfuerstenau.leia.protocol.check.IndexCheck;
import io.github.mfuerstenau.leia.protocol.check.SegmentCheck;
import io.github.mfuerstenau.leia.protocol.check.StartCheckIndexParams;
import io.github.mfuerstenau.leia.protocol.commits.Commit;
import io.github.mfuerstenau.leia.protocol.commits.CommitFile;
import io.github.mfuerstenau.leia.protocol.commits.GetCommitParams;
import io.github.mfuerstenau.leia.protocol.commits.GetCommitResult;
import io.github.mfuerstenau.leia.protocol.commits.GetCommitsParams;
import io.github.mfuerstenau.leia.protocol.commits.Segment;
import io.github.mfuerstenau.leia.protocol.document.BytesValue;
import io.github.mfuerstenau.leia.protocol.document.GetDocumentParams;
import io.github.mfuerstenau.leia.protocol.document.StoredType;
import io.github.mfuerstenau.leia.protocol.document.StoredValue;
import io.github.mfuerstenau.leia.protocol.document.TermPosition;
import io.github.mfuerstenau.leia.protocol.index.Feature;
import io.github.mfuerstenau.leia.protocol.index.GetIndexStatsParams;
import io.github.mfuerstenau.leia.protocol.index.IndexCommitStats;
import io.github.mfuerstenau.leia.protocol.index.IndexEntry;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexParams;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexResult;
import io.github.mfuerstenau.leia.protocol.index.OpenMode;
import io.github.mfuerstenau.leia.protocol.index.SkippedIndex;
import io.github.mfuerstenau.leia.protocol.job.JobParams;
import io.github.mfuerstenau.leia.protocol.search.SearchQuery;
import io.github.mfuerstenau.leia.protocol.search.StartSearchParams;
import io.github.mfuerstenau.leia.protocol.terms.ExportTermsResult;
import io.github.mfuerstenau.leia.protocol.terms.FieldTerms;
import io.github.mfuerstenau.leia.protocol.terms.GetExportTermsResult;
import io.github.mfuerstenau.leia.protocol.terms.GetFieldTermScanResult;
import io.github.mfuerstenau.leia.protocol.terms.GetPositionsParams;
import io.github.mfuerstenau.leia.protocol.terms.GetTermsParams;
import io.github.mfuerstenau.leia.protocol.terms.GetTermsResult;
import io.github.mfuerstenau.leia.protocol.terms.StartExportTermsParams;
import io.github.mfuerstenau.leia.protocol.terms.StartFieldTermScanParams;
import io.github.mfuerstenau.leia.protocol.terms.TermExportFormat;
import io.github.mfuerstenau.leia.protocol.terms.TopTerm;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A Lucene 3.x index read through {@link LeiaBackend} with Lucene 3.6 in a class loader of its own (ADR 0045): its
 * commits, the check, top terms, several old indexes as one, and the analyzers of 3.6. The index, from
 * leia-lucene3's {@code Lucene3IndexWriter}, has two commits; the latest holds documents 1 to 7, with
 * id 2 deleted, in the segments {@code _0} (plain) and {@code _1} (compound).
 */
class Lucene3BackendTest {

    private static final String PATH = testIndex("lucene3").toString();
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @TempDir
    Path tmp;

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

    @Test
    void opensTheIndexWithItsFeatures() throws Exception {
        OpenIndexResult opened = open(Path.of(PATH));
        assertThat(opened.numDocs()).hasValue(6);
        assertThat(opened.maxDoc()).hasValue(7);
        assertThat(opened.features())
                .containsExactlyInAnyOrder(
                        Feature.INDEX_STATS,
                        Feature.REOPEN,
                        Feature.DOCUMENTS,
                        Feature.STORED_VALUES,
                        Feature.STORED_SCAN,
                        Feature.STORED_FIELDS,
                        Feature.TERM_VECTORS,
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
                        Feature.COMMITS,
                        Feature.CHECK_INDEX,
                        Feature.RECONSTRUCT);
        assertThat(opened.indexes().getFirst().reader().orElseThrow().createdVersionMajor())
                .isEqualTo(3);
    }

    /** 3.6 doesn't know how many terms a field has, so the rebuild has no total (ADR 0062). */
    @Test
    void rebuildsADocument() throws Exception {
        OpenIndexResult opened = open(Path.of(PATH));
        RuntimeRebuilds.rebuildsADocument(backend, opened.maxDoc().orElseThrow(), false, false);
    }

    @Test
    void readsTheCommitsAndMarksTheOpenedOne() throws Exception {
        open(Path.of(PATH));
        List<Commit> commits =
                backend.call(Methods.GET_COMMITS, new GetCommitsParams()).commits();
        assertThat(commits)
                .extracting(Commit::segmentsFile, Commit::current, Commit::maxDoc, Commit::userData)
                .containsExactly(
                        tuple("segments_2", true, 7, Map.of("commit", "second")),
                        tuple("segments_1", false, 4, Map.of("commit", "first")));

        GetCommitResult details = backend.call(Methods.GET_COMMIT, new GetCommitParams(2, OptionalInt.empty()));
        assertThat(details.commit()).isEqualTo(commits.getFirst());
        assertThat(details.segments()).extracting(Segment::name).containsExactly("_0", "_1");
        Segment plain = details.segments().getFirst();
        assertThat(plain.id()).isEmpty();
        assertThat(plain.codec()).isEmpty();
        assertThat(plain.formats()).isEmpty();
        assertThat(plain.delGen()).hasValue(1);
        assertThat(plain.attributes()).containsKey("docStoreSegment");
        assertThat(details.segments().get(1).compound()).isTrue();
        backend.fails(Methods.GET_COMMIT, new GetCommitParams(7, OptionalInt.empty()), ErrorCode.COMMIT_NOT_FOUND);
        backend.fails(Methods.GET_COMMITS, new GetCommitsParams(OptionalInt.of(1)), ErrorCode.INVALID_PARAMS);
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
    void checksOnlyTheSegmentsAskedFor() throws Exception {
        open(Path.of(PATH));
        CheckTargetIndex target = backend.call(Methods.GET_CHECK_TARGETS, new GetCheckTargetsParams())
                .indexes()
                .getFirst();
        assertThat(target.error()).isEmpty();
        String last = target.segments().getLast().name();

        Check check = check(new StartCheckIndexParams(
                OptionalInt.empty(),
                OptionalInt.of(1),
                Optional.of(List.of(new CheckTarget(target.path(), Optional.of(List.of(last, "_zz")))))));

        IndexCheck index = check.result().indexes().getFirst();
        assertThat(index.onlySegments()).contains(List.of(last, "_zz"));
        assertThat(index.segments()).extracting(SegmentCheck::name).containsExactly(last);
        assertThat(index.segmentCount()).isEqualTo(target.segments().size());
        assertThat(index.segmentsNotFound()).containsExactly("_zz");
        assertThat(index.clean()).isFalse();
        assertThat(check.lines()).anyMatch(line -> line.contains("only these segments") && line.contains(last));
    }

    @Test
    void checksTheIndexInAJob() throws Exception {
        open(Path.of(PATH));
        // The level is Lucene 10's; Lucene 3.6 has one.
        Check check = check(new StartCheckIndexParams(OptionalInt.of(3)));
        assertThat(check.lines())
                .contains("Checking " + PATH, "Checking with Lucene 3.6.2, which has no levels")
                .anyMatch(line -> line.contains("No problems were detected"));
        IndexCheck index = check.result().indexes().getFirst();
        assertThat(index.path()).isEqualTo(PATH);
        assertThat(index.clean()).isTrue();
        assertThat(index.skipped()).isFalse();
        assertThat(index.segmentsFile()).contains("segments_2");
        assertThat(index.segments())
                .hasSize(2)
                .allSatisfy(segment -> assertThat(segment.codec()).isEmpty());
    }

    @Test
    void cancelsACheck() throws Exception {
        open(Path.of(PATH));
        long jobId = backend.call(Methods.START_CHECK_INDEX, new StartCheckIndexParams())
                .jobId();
        backend.call(Methods.CANCEL_JOB, new JobParams(jobId));
        backend.fails(Methods.GET_CHECK_INDEX, new GetCheckIndexParams(jobId, 0), ErrorCode.JOB_NOT_FOUND);
    }

    @Test
    void readsPositionsWithoutOffsets() throws Exception {
        open(Path.of(PATH));
        assertThat(backend.call(
                                Methods.GET_POSITIONS,
                                new GetPositionsParams(0, "body", new BytesValue("gamma", false)))
                        .positions())
                .containsExactly(new TermPosition(
                        OptionalInt.of(4), OptionalInt.empty(), OptionalInt.empty(), Optional.empty()));
        // 3.6 skips deleted documents.
        backend.fails(
                Methods.GET_POSITIONS,
                new GetPositionsParams(1, "body", new BytesValue("alpha", false)),
                ErrorCode.TERM_NOT_FOUND);
        backend.fails(
                Methods.GET_POSITIONS,
                new GetPositionsParams(0, "body", new BytesValue("zz", true)),
                ErrorCode.INVALID_PARAMS);
    }

    @Test
    void findsTheTopTerms() throws Exception {
        open(Path.of(PATH));
        FieldTerms terms = backend.job(
                Methods.START_FIELD_TERM_SCAN,
                new StartFieldTermScanParams("body", 3),
                Methods.GET_FIELD_TERM_SCAN,
                GetFieldTermScanResult::terms);
        assertThat(terms.uniqueTerms()).isEqualTo(11);
        assertThat(terms.topTerms())
                .containsExactly(
                        new TopTerm("alpha", false, 7, 6),
                        new TopTerm("document", false, 7, 6),
                        new TopTerm("beta", false, 4, 4));
        backend.fails(
                Methods.START_FIELD_TERM_SCAN, new StartFieldTermScanParams("nothing", 3), ErrorCode.FIELD_NOT_FOUND);
    }

    @Test
    void scansTheStoredValues() throws Exception {
        OpenIndexResult opened = open(Path.of(PATH));

        RuntimeStoredScans.scansTheStoredValues(backend, opened.maxDoc().orElseThrow(), false);
    }

    @Test
    void findsTheStoredFields() throws Exception {
        RuntimeStoredFields.findsTheStoredFields(backend, open(Path.of(PATH)), false);
    }

    @Test
    void scansAndFiltersThroughTheStoredFieldsCache() throws Exception {
        RuntimeStoredFields.scansAndFiltersThroughTheCache(Path.of(PATH), List.of("body"));
    }

    @Test
    void keepsTheStoredFieldsOnDisk() throws Exception {
        // 3.6 records no segment ids: the cache knows the segments by their files.
        RuntimeStoredFields.keepsTheCacheOnDisk(Path.of(PATH), tmp);
    }

    @Test
    void filtersTheDocuments() throws Exception {
        open(Path.of(PATH));

        RuntimeFilters.filtersTheDocuments(backend, false);
    }

    /** 3.6 doesn't sort (ADR 0064, ADR 0076). */
    @Test
    void doesNotSort() throws Exception {
        open(Path.of(PATH));
        RuntimeScorings.doesNotSort(backend);
    }

    @Test
    void explainsAHit() throws Exception {
        OpenIndexResult opened = open(Path.of(PATH));

        RuntimeExplains.explainsAHit(backend, opened.maxDoc().orElseThrow(), true);
    }

    @Test
    void reopensAChangedIndex() throws Exception {
        RuntimeReopens.reopensAChangedIndex(backend, Path.of(PATH), tmp.resolve("copy"), "segments_2");
    }

    @Test
    void findsTheAutoStopWords() throws Exception {
        OpenIndexResult opened = open(Path.of(PATH));

        RuntimeAutoStopWords.findsTheWords(backend, "body");
        RuntimeAutoStopWords.searchesWithoutTheWords(backend, opened.maxDoc().orElseThrow(), "body", "beta");
    }

    @Test
    void searchesWithAStopWordsFile() throws Exception {
        open(Path.of(PATH));

        RuntimeSearches.searchesWithTheAnalyzersOfAnOldRuntime(backend, "3.6.2", tmp);
    }

    @Test
    void searchesATerm() throws Exception {
        OpenIndexResult opened = open(Path.of(PATH));

        RuntimeSearches.searchesATerm(backend, opened.maxDoc().orElseThrow(), "beta", true);
    }

    @Test
    void exportsTheTermsOfAField() throws Exception {
        open(Path.of(PATH));
        Path file = tmp.resolve("body.tsv");
        ExportTermsResult result = exportTerms("body", file);
        assertThat(result.terms()).isEqualTo(11);
        assertThat(result.bytes()).isEqualTo(Files.size(file));
        assertThat(Files.readAllLines(file))
                .hasSize(11)
                .containsSubsequence("1\t1", "7\t1", "alpha\t7", "beta\t4", "document\t7", "gamma\t1")
                .isEqualTo(termLines("body"));
        backend.fails(
                Methods.START_EXPORT_TERMS,
                new StartExportTermsParams("nothing", tmp.resolve("x.tsv").toString()),
                ErrorCode.FIELD_NOT_FOUND);
        backend.fails(
                Methods.START_EXPORT_TERMS,
                new StartExportTermsParams("body", Path.of(PATH, "body.tsv").toString()),
                ErrorCode.OUTPUT_NOT_WRITABLE);
    }

    @Test
    void exportsTheFullFormatWithTheTotalTermFrequency() throws Exception {
        open(Path.of(PATH));
        Path file = tmp.resolve("body.full");
        backend.job(
                Methods.START_EXPORT_TERMS,
                new StartExportTermsParams(
                        "body",
                        file.toString(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(TermExportFormat.FULL)),
                Methods.GET_EXPORT_TERMS,
                GetExportTermsResult::result);

        List<String> expected = new ArrayList<>();
        Optional<BytesValue> from = Optional.empty();
        do {
            GetTermsResult page = backend.call(Methods.GET_TERMS, new GetTermsParams("body", from, 3));
            page.terms()
                    .forEach(term ->
                            expected.add("t\t" + term.docFreq() + "\t" + term.totalTermFreq() + "\t" + term.term()));
            from = page.next();
        } while (from.isPresent());
        assertThat(Files.readAllLines(file)).isEqualTo(expected);
        assertThat(expected)
                .contains("t\t7\t6\talpha")
                .anySatisfy(line -> assertThat(line.split("\t")[2]).isNotEqualTo("0"));
    }

    /** The lines an export of {@code field} should write: the terms screen's pages, tab-separated. */
    private List<String> termLines(String field) throws Exception {
        List<String> lines = new ArrayList<>();
        Optional<BytesValue> from = Optional.empty();
        do {
            GetTermsResult page = backend.call(Methods.GET_TERMS, new GetTermsParams(field, from, 3));
            page.terms().forEach(term -> lines.add(term.term() + "\t" + term.docFreq()));
            from = page.next();
        } while (from.isPresent());
        return lines;
    }

    private ExportTermsResult exportTerms(String field, Path file) throws Exception {
        return backend.job(
                Methods.START_EXPORT_TERMS,
                new StartExportTermsParams(field, file.toString()),
                Methods.GET_EXPORT_TERMS,
                GetExportTermsResult::result);
    }

    @Test
    void opensWithoutAReaderWhenAFileIsMissing() throws Exception {
        Path copy = copy(Path.of(PATH), tmp.resolve("broken"));
        Files.delete(copy.resolve("_0.frq"));
        OpenIndexResult opened = backend.call(
                Methods.OPEN_INDEX,
                new OpenIndexParams(copy.toString(), Optional.empty(), Optional.of(OpenMode.DIRECTORY_ONLY)));
        assertThat(opened.features()).containsExactlyInAnyOrder(Feature.COMMITS, Feature.CHECK_INDEX);
        assertThat(backend.call(Methods.GET_COMMITS, new GetCommitsParams()).commits())
                .extracting(Commit::segmentsFile, Commit::current)
                .containsExactly(tuple("segments_2", false), tuple("segments_1", false));
        GetCommitResult details = backend.call(Methods.GET_COMMIT, new GetCommitParams(2, OptionalInt.empty()));
        // Lucene 3.6 lists only the files there are.
        assertThat(details.segments()).hasSize(2);
        assertThat(details.files())
                .extracting(CommitFile::name)
                .doesNotContain("_0.frq")
                .contains("_0.tis");
        IndexCheck check = check(new StartCheckIndexParams()).result().indexes().getFirst();
        assertThat(check.clean()).isFalse();
        assertThat(check.badSegments()).isEqualTo(1);
        assertThat(check.segments().getFirst().openReaderPassed()).isFalse();
        backend.fails(Methods.GET_DOCUMENT, new GetDocumentParams(0), ErrorCode.NO_READER);
    }

    @Test
    void opensSeveralOldIndexesAsOne() throws Exception {
        copy(testIndex("lucene24"), tmp.resolve("a"));
        copy(Path.of(PATH), tmp.resolve("b"));
        OpenIndexResult opened = open(tmp);
        assertThat(opened.maxDoc()).hasValue(10);
        assertThat(opened.indexes())
                .extracting(
                        IndexEntry::path, entry -> entry.reader().orElseThrow().docBase())
                .containsExactly(
                        tuple(tmp.resolve("a").toString(), 0),
                        tuple(tmp.resolve("b").toString(), 3));
        assertThat(opened.skipped()).isEmpty();
        assertThat(backend.call(Methods.GET_DOCUMENT, new GetDocumentParams(3))
                        .fields()
                        .getFirst()
                        .stored())
                .containsExactly(new StoredValue(StoredType.STRING, "1", false));
        assertThat(backend.call(Methods.GET_INDEX_STATS, new GetIndexStatsParams())
                        .indexes())
                .extracting(IndexCommitStats::docBase, IndexCommitStats::segmentCount)
                .containsExactly(tuple(0, 1), tuple(3, 2));
        assertThat(backend.call(Methods.GET_COMMITS, new GetCommitsParams(OptionalInt.of(1)))
                        .commits())
                .hasSize(2);
        assertThat(check(new StartCheckIndexParams()).result().indexes())
                .extracting(IndexCheck::path, IndexCheck::clean)
                .containsExactly(
                        tuple(tmp.resolve("a").toString(), true),
                        tuple(tmp.resolve("b").toString(), true));
    }

    @Test
    void anOldFirstIndexSkipsANewOne() throws Exception {
        copy(Path.of(PATH), tmp.resolve("a"));
        copy(testIndex("lucene9"), tmp.resolve("b"));
        OpenIndexResult opened = open(tmp);
        assertThat(opened.luceneRuntime()).isEqualTo("3.6.2");
        assertThat(opened.indexes())
                .extracting(IndexEntry::path)
                .containsExactly(tmp.resolve("a").toString());
        assertThat(opened.skipped())
                .extracting(SkippedIndex::path)
                .containsExactly(tmp.resolve("b").toString());
        assertThat(opened.skipped().getFirst().message()).contains("Lucene 9", "open it on its own");
        // Each index is checked by the Lucene that reads it: the skipped one by Lucene 10.
        assertThat(check(new StartCheckIndexParams()).result().indexes())
                .extracting(IndexCheck::skipped, IndexCheck::clean)
                .containsExactly(tuple(false, true), tuple(true, true));
    }

    @Test
    void aNewFirstIndexSkipsAnOldOne() throws Exception {
        copy(testIndex("lucene9"), tmp.resolve("a"));
        copy(Path.of(PATH), tmp.resolve("b"));
        OpenIndexResult opened = open(tmp);
        assertThat(opened.luceneRuntime()).startsWith("10.");
        assertThat(opened.skipped())
                .extracting(SkippedIndex::path)
                .containsExactly(tmp.resolve("b").toString());
        Check check = check(new StartCheckIndexParams());
        assertThat(check.result().indexes())
                .extracting(IndexCheck::skipped, IndexCheck::clean)
                .containsExactly(tuple(false, true), tuple(true, true));
        assertThat(check.lines()).contains("Checking with Lucene 3.6.2, which has no levels");
    }

    private static Analyzer preset(String className, @Nullable WordSet stopwords) {
        return analyzer(
                null,
                new PresetAnalyzer(
                        className,
                        Optional.ofNullable(stopwords),
                        Optional.empty(),
                        OptionalInt.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()),
                null,
                null,
                List.of());
    }

    private static Analyzer analyzer(
            @Nullable StandardAnalyzer standard,
            @Nullable PresetAnalyzer preset,
            @Nullable CustomAnalyzer custom,
            @Nullable Shingles shingles,
            List<FieldAnalyzer> fields) {
        return new Analyzer(
                Optional.ofNullable(standard),
                Optional.ofNullable(preset),
                Optional.ofNullable(custom),
                Optional.ofNullable(shingles),
                Optional.empty(),
                fields,
                List.of());
    }

    private long hits(SearchQuery query) throws Exception {
        return backend.search(new StartSearchParams(
                        query, BM25, List.of(), 0, 10, true, Optional.of(List.of()), Optional.empty()))
                .totalHits();
    }

    @Test
    void searchesWithTheAnalyzersOf36() throws Exception {
        open(Path.of(PATH));
        Analyzer whitespace = preset("org.apache.lucene.analysis.core.WhitespaceAnalyzer", null);
        assertThat(hits(classic("Alpha", whitespace))).isZero();
        assertThat(hits(classic("alpha", whitespace))).isEqualTo(6);
        assertThat(hits(classic("Alpha", RealBackend.STANDARD))).isEqualTo(6);
        Analyzer noAlpha = preset(
                "org.apache.lucene.analysis.standard.StandardAnalyzer",
                new WordSet(Optional.of(List.of("alpha")), Optional.empty()));
        assertThat(hits(classic("alpha", noAlpha))).isZero();
        Analyzer limited = new Analyzer(
                Optional.of(new StandardAnalyzer()),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(new LimitTokenCount(1, false)),
                List.of(new FieldAnalyzer("id", preset("org.apache.lucene.analysis.core.KeywordAnalyzer", null))),
                List.of());
        assertThat(hits(classic("\"gamma nothing\" id:7", limited))).isEqualTo(2);

        RpcException english = backend.fails(
                Methods.START_SEARCH,
                params(classic("alpha", preset("org.apache.lucene.analysis.en.EnglishAnalyzer", null))),
                ErrorCode.NOT_SUPPORTED);
        assertThat(english.getMessage()).contains("Lucene 3.6.2", "EnglishAnalyzer", "WhitespaceAnalyzer");
        backend.fails(
                Methods.START_SEARCH,
                params(classic(
                        "alpha",
                        analyzer(
                                null,
                                null,
                                new CustomAnalyzer(
                                        List.of(),
                                        new AnalysisComponent("whitespace", Map.of()),
                                        List.of(),
                                        Optional.empty()),
                                null,
                                List.of()))),
                ErrorCode.NOT_SUPPORTED);
        backend.fails(
                Methods.START_SEARCH,
                params(classic(
                        "alpha",
                        analyzer(
                                new StandardAnalyzer(),
                                null,
                                null,
                                new Shingles(2, 2, " ", true, false, "_"),
                                List.of()))),
                ErrorCode.NOT_SUPPORTED);
    }

    private static StartSearchParams params(SearchQuery query) {
        return new StartSearchParams(query, BM25, List.of(), 0, 10, true, Optional.empty(), Optional.empty());
    }

    private static Path copy(Path from, Path to) throws IOException {
        Files.createDirectories(to);
        try (Stream<Path> files = Files.list(from)) {
            for (Path file : files.toList()) {
                Files.copy(file, to.resolve(file.getFileName()));
            }
        }
        return to;
    }
}
