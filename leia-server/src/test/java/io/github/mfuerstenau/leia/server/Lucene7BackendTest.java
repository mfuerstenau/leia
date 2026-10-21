package io.github.mfuerstenau.leia.server;

import static io.github.mfuerstenau.leia.server.RealBackend.STANDARD;
import static io.github.mfuerstenau.leia.server.RealBackend.testIndex;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.check.CheckIndexResult;
import io.github.mfuerstenau.leia.protocol.check.GetCheckIndexParams;
import io.github.mfuerstenau.leia.protocol.check.GetCheckIndexResult;
import io.github.mfuerstenau.leia.protocol.check.StartCheckIndexParams;
import io.github.mfuerstenau.leia.protocol.commits.Commit;
import io.github.mfuerstenau.leia.protocol.commits.GetCommitParams;
import io.github.mfuerstenau.leia.protocol.commits.GetCommitsParams;
import io.github.mfuerstenau.leia.protocol.commits.Segment;
import io.github.mfuerstenau.leia.protocol.document.DocumentField;
import io.github.mfuerstenau.leia.protocol.document.GetDocumentParams;
import io.github.mfuerstenau.leia.protocol.index.Feature;
import io.github.mfuerstenau.leia.protocol.index.GetIndexStatsParams;
import io.github.mfuerstenau.leia.protocol.index.GetIndexStatsResult;
import io.github.mfuerstenau.leia.protocol.index.IndexField;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexParams;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexResult;
import io.github.mfuerstenau.leia.protocol.index.OpenMode;
import io.github.mfuerstenau.leia.protocol.points.GetPointCellParams;
import io.github.mfuerstenau.leia.protocol.points.GetPointCellResult;
import io.github.mfuerstenau.leia.protocol.points.GetPointTreeParams;
import io.github.mfuerstenau.leia.protocol.points.GetPointTreeResult;
import io.github.mfuerstenau.leia.protocol.points.LeafPoint;
import io.github.mfuerstenau.leia.protocol.points.PointCell;
import io.github.mfuerstenau.leia.protocol.points.PointSegment;
import io.github.mfuerstenau.leia.protocol.points.PointValue;
import io.github.mfuerstenau.leia.protocol.search.HighlightOptions;
import io.github.mfuerstenau.leia.protocol.search.StartHighlightParams;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Lucene 6.x and 7.x indexes read through {@link LeiaBackend} with Lucene 7.7 in a class loader of its own (ADR 0048):
 * the created major, points, soft deletes, an index sort, the minimum versions of segments and the
 * check at its levels. The indexes, from leia-lucene7's writers, have two commits; the latest holds
 * documents 1 to 7, with id 2 deleted, in the segments {@code _0} (plain) and {@code _1} (compound).
 * 6.x sorts them by {@code order}; 7.x soft-updates id 3, which adds a document 8 to {@code _1}.
 */
class Lucene7BackendTest {

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

    @ParameterizedTest
    @ValueSource(strings = {"lucene6", "lucene7"})
    void opensTheIndexWithItsFeatures(String name) throws Exception {
        boolean seven = name.equals("lucene7");
        OpenIndexResult opened = open(testIndex(name));
        assertThat(opened.luceneRuntime()).isEqualTo("7.7.3");
        assertThat(opened.numDocs()).hasValue(seven ? 7 : 6);
        assertThat(opened.maxDoc()).hasValue(seven ? 8 : 7);
        assertThat(opened.features())
                .containsExactlyInAnyOrder(
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
                        Feature.SCORING,
                        Feature.DOC_VALUES_SEARCH,
                        Feature.STANDARD_SEARCH,
                        Feature.MORE_LIKE_THIS,
                        Feature.RECONSTRUCT,
                        Feature.POINTS,
                        Feature.COMMITS,
                        Feature.CHECK_INDEX,
                        Feature.CHECK_LEVELS);
        // The created major, which says how the norms are encoded.
        assertThat(opened.indexes().getFirst().reader().orElseThrow().createdVersionMajor())
                .isEqualTo(seven ? 7 : 6);

        GetIndexStatsResult stats = backend.call(Methods.GET_INDEX_STATS, new GetIndexStatsParams());
        assertThat(stats.indexes().getFirst().createdVersionMajor()).isEqualTo(seven ? 7 : 6);
        assertThat(stats.indexes().getFirst().commitLuceneVersion()).isEqualTo(seven ? "7.7.3" : "6.6.6");
        assertThat(stats.fields())
                .filteredOn(field -> field.pointDimensionCount() > 0)
                .extracting(IndexField::name, IndexField::pointDimensionCount, IndexField::pointNumBytes)
                .containsExactly(tuple("pt", 1, 4), tuple("loc", 2, 4), tuple("amount", 1, 8));
    }

    @ParameterizedTest
    @ValueSource(strings = {"lucene6", "lucene7"})
    void scansTheStoredValues(String name) throws Exception {
        OpenIndexResult opened = open(testIndex(name));

        RuntimeStoredScans.scansTheStoredValues(backend, opened.maxDoc().orElseThrow(), true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"lucene6", "lucene7"})
    void filtersTheDocuments(String name) throws Exception {
        open(testIndex(name));

        RuntimeFilters.filtersTheDocuments(backend, true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"lucene6", "lucene7"})
    void sortsAndScores(String name) throws Exception {
        open(testIndex(name));
        RuntimeScorings.sortsAndScores(backend);
    }

    /** 7.7 doesn't highlight (ADR 0063). */
    @Test
    void doesNotHighlight() throws Exception {
        open(testIndex("lucene7"));
        backend.fails(
                Methods.START_HIGHLIGHT,
                new StartHighlightParams(
                        RealBackend.classic("body:beta"),
                        0,
                        Optional.empty(),
                        new HighlightOptions(1, false, STANDARD)),
                ErrorCode.NOT_SUPPORTED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"lucene6", "lucene7"})
    void explainsAHit(String name) throws Exception {
        OpenIndexResult opened = open(testIndex(name));

        RuntimeExplains.explainsAHit(backend, opened.maxDoc().orElseThrow(), false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"lucene6", "lucene7"})
    void findsTheAutoStopWords(String name) throws Exception {
        OpenIndexResult opened = open(testIndex(name));

        RuntimeAutoStopWords.findsTheWords(backend, "body");
        RuntimeAutoStopWords.searchesWithoutTheWords(backend, opened.maxDoc().orElseThrow(), "body", "beta");
    }

    /** The standard parser, More-Like-This and doc values queries (ADR 0074). */
    @ParameterizedTest
    @ValueSource(strings = {"lucene6", "lucene7"})
    void searchesTheQueriesOf912(String name) throws Exception {
        open(testIndex(name));

        RuntimeSearches.searchesTheQueriesOf912(backend, true);
    }

    /** Custom analyzers, shingles and the options of presets (ADR 0075). */
    @Test
    void searchesWithTheAnalyzersOf912() throws Exception {
        open(testIndex("lucene7"));

        RuntimeSearches.searchesWithTheAnalyzersOf912(backend, "7.7.3", tmp);
    }

    @ParameterizedTest
    @ValueSource(strings = {"lucene6", "lucene7"})
    void searchesATerm(String name) throws Exception {
        OpenIndexResult opened = open(testIndex(name));

        RuntimeSearches.searchesATerm(backend, opened.maxDoc().orElseThrow(), "beta", false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"lucene6", "lucene7"})
    void reopensAChangedIndex(String name) throws Exception {
        RuntimeReopens.reopensAChangedIndex(backend, testIndex(name), tmp.resolve(name), "segments_2");
    }

    @ParameterizedTest
    @ValueSource(strings = {"lucene6", "lucene7"})
    void rebuildsADocument(String name) throws Exception {
        OpenIndexResult opened = open(testIndex(name));

        RuntimeRebuilds.rebuildsADocument(backend, opened.maxDoc().orElseThrow(), true, true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"lucene6", "lucene7"})
    void readsTheRawNormsOfTheCreatedMajor(String name) throws Exception {
        open(testIndex(name));
        DocumentField body = backend.call(Methods.GET_DOCUMENT, new GetDocumentParams(0)).fields().stream()
                .filter(field -> field.name().equals("body"))
                .findFirst()
                .orElseThrow();
        // Six tokens: 7.x keeps the length, 6.x a byte of 1 / sqrt(6).
        assertThat(body.norm()).hasValue(name.equals("lucene7") ? 6 : 118);
    }

    @Test
    void readsThePointTree() throws Exception {
        open(testIndex("lucene6"));
        GetPointTreeResult tree = backend.call(Methods.GET_POINT_TREE, new GetPointTreeParams("pt"));
        assertThat(tree.dimensions()).isEqualTo(1);
        assertThat(tree.indexDimensions()).isEqualTo(1);
        assertThat(tree.bytesPerDimension()).isEqualTo(4);
        assertThat(tree.size()).isEqualTo(105);
        assertThat(tree.min()).contains(new PointValue(List.of("80000064")));
        assertThat(tree.segments())
                .extracting(
                        PointSegment::segment,
                        PointSegment::index,
                        PointSegment::name,
                        PointSegment::docBase,
                        PointSegment::size)
                .containsExactly(tuple(0, 0, Optional.of("_0"), 0, 60L), tuple(1, 0, Optional.of("_1"), 4, 45L));

        GetPointCellResult root = backend.call(Methods.GET_POINT_CELL, new GetPointCellParams("pt", 1, List.of()));
        assertThat(root.cell().size()).isEqualTo(45);
        assertThat(root.children())
                .extracting(PointCell::size, PointCell::leaf)
                .containsExactly(tuple(32L, false), tuple(13L, true));
        GetPointCellResult leaf = backend.call(Methods.GET_POINT_CELL, new GetPointCellParams("pt", 1, List.of(1)));
        assertThat(leaf.cell().leaf()).isTrue();
        assertThat(leaf.pointsCut()).isFalse();
        assertThat(leaf.points())
                .hasSize(13)
                .first()
                .isEqualTo(new LeafPoint(6, false, new PointValue(List.of("800002be"))));
        // A deleted document's points are marked.
        assertThat(backend.call(Methods.GET_POINT_CELL, new GetPointCellParams("pt", 0, List.of(0, 1)))
                        .points())
                .extracting(LeafPoint::docId, LeafPoint::deleted)
                .contains(tuple(1, true), tuple(2, false));
        // Two dimensions, each split apart.
        GetPointCellResult loc = backend.call(Methods.GET_POINT_CELL, new GetPointCellParams("loc", 0, List.of(0, 0)));
        assertThat(loc.points().getFirst().value().dimensions()).hasSize(2);

        backend.fails(Methods.GET_POINT_TREE, new GetPointTreeParams("nothing"), ErrorCode.FIELD_NOT_FOUND);
        backend.fails(Methods.GET_POINT_TREE, new GetPointTreeParams("body"), ErrorCode.INVALID_PARAMS);
        backend.fails(Methods.GET_POINT_CELL, new GetPointCellParams("pt", 5, List.of()), ErrorCode.INVALID_PARAMS);
        backend.fails(Methods.GET_POINT_CELL, new GetPointCellParams("pt", 1, List.of(1, 0)), ErrorCode.INVALID_PARAMS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"lucene6", "lucene7"})
    void readsTheCommits(String name) throws Exception {
        boolean seven = name.equals("lucene7");
        String version = seven ? "7.7.3" : "6.6.6";
        open(testIndex(name));
        List<Commit> commits =
                backend.call(Methods.GET_COMMITS, new GetCommitsParams()).commits();
        assertThat(commits)
                .extracting(Commit::segmentsFile, Commit::current, Commit::luceneVersion)
                .containsExactly(tuple("segments_2", true, version), tuple("segments_1", false, version));
        List<Segment> segments = backend.call(Methods.GET_COMMIT, new GetCommitParams(2, OptionalInt.empty()))
                .segments();
        Segment plain = segments.getFirst();
        assertThat(plain.codec()).contains(seven ? "Lucene70" : "Lucene62");
        assertThat(plain.id()).isPresent();
        assertThat(plain.delCount()).isEqualTo(1);
        assertThat(plain.softDelCount()).isEqualTo(seven ? 1 : 0);
        assertThat(plain.minLuceneVersion()).isEqualTo(seven ? Optional.of(version) : Optional.empty());
        assertThat(plain.indexSort()).isEqualTo(seven ? Optional.empty() : Optional.of("<long: \"order\">"));
        assertThat(plain.formats())
                .hasValueSatisfying(formats -> assertThat(formats.points()).contains("Lucene60PointsFormat"));
        assertThat(segments.get(1).softDelCount()).isZero();
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
        open(testIndex("lucene7"));
        Check fast = check(new StartCheckIndexParams(OptionalInt.of(1)));
        assertThat(fast.lines()).contains("Checking with Lucene 7.7.3").noneMatch(line -> line.contains("test: terms"));
        assertThat(fast.result().indexes().getFirst().clean()).isTrue();
        Check slow = check(new StartCheckIndexParams(OptionalInt.of(3)));
        assertThat(slow.lines()).anyMatch(line -> line.contains("test: points"));
        assertThat(slow.result().indexes().getFirst().clean()).isTrue();
    }

    @Test
    void opensWithoutAReaderThroughTheRuntime() throws Exception {
        OpenIndexResult opened = backend.call(
                Methods.OPEN_INDEX,
                new OpenIndexParams(
                        testIndex("lucene6").toString(), Optional.empty(), Optional.of(OpenMode.DIRECTORY_ONLY)));
        assertThat(opened.features())
                .containsExactlyInAnyOrder(Feature.COMMITS, Feature.CHECK_INDEX, Feature.CHECK_LEVELS);
        assertThat(backend.call(Methods.GET_COMMIT, new GetCommitParams(2, OptionalInt.empty()))
                        .segments())
                .allSatisfy(segment -> assertThat(segment.codec()).contains("Lucene62"));
        backend.fails(Methods.GET_POINT_TREE, new GetPointTreeParams("pt"), ErrorCode.NO_READER);
    }

    @Test
    void opensA6xAndA7xIndexAsOne() throws Exception {
        copy(testIndex("lucene6"), tmp.resolve("a"));
        copy(testIndex("lucene7"), tmp.resolve("b"));
        OpenIndexResult opened = open(tmp);
        assertThat(opened.maxDoc()).hasValue(15);
        assertThat(opened.skipped()).isEmpty();
        // Each index keeps its created major.
        assertThat(opened.indexes())
                .extracting(
                        entry -> entry.reader().orElseThrow().docBase(),
                        entry -> entry.reader().orElseThrow().createdVersionMajor())
                .containsExactly(tuple(0, 6), tuple(7, 7));
        assertThat(backend.call(Methods.GET_POINT_TREE, new GetPointTreeParams("pt"))
                        .segments())
                .extracting(PointSegment::index, PointSegment::docBase)
                .containsExactly(tuple(0, 0), tuple(0, 4), tuple(1, 7), tuple(1, 11));
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
