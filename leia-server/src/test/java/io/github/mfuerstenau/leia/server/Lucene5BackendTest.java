package io.github.mfuerstenau.leia.server;

import static io.github.mfuerstenau.leia.server.RealBackend.BM25;
import static io.github.mfuerstenau.leia.server.RealBackend.classic;
import static io.github.mfuerstenau.leia.server.RealBackend.testIndex;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.analysis.Analyzer;
import io.github.mfuerstenau.leia.protocol.analysis.PresetAnalyzer;
import io.github.mfuerstenau.leia.protocol.check.CheckIndexResult;
import io.github.mfuerstenau.leia.protocol.check.GetCheckIndexParams;
import io.github.mfuerstenau.leia.protocol.check.GetCheckIndexResult;
import io.github.mfuerstenau.leia.protocol.check.IndexCheck;
import io.github.mfuerstenau.leia.protocol.check.StartCheckIndexParams;
import io.github.mfuerstenau.leia.protocol.commits.Commit;
import io.github.mfuerstenau.leia.protocol.commits.FieldFormats;
import io.github.mfuerstenau.leia.protocol.commits.GetCommitParams;
import io.github.mfuerstenau.leia.protocol.commits.GetCommitResult;
import io.github.mfuerstenau.leia.protocol.commits.GetCommitsParams;
import io.github.mfuerstenau.leia.protocol.commits.Segment;
import io.github.mfuerstenau.leia.protocol.document.BytesValue;
import io.github.mfuerstenau.leia.protocol.document.DocValues;
import io.github.mfuerstenau.leia.protocol.document.DocumentField;
import io.github.mfuerstenau.leia.protocol.document.GetDocumentParams;
import io.github.mfuerstenau.leia.protocol.document.GetTermVectorParams;
import io.github.mfuerstenau.leia.protocol.document.StoredType;
import io.github.mfuerstenau.leia.protocol.document.StoredValue;
import io.github.mfuerstenau.leia.protocol.document.TermPosition;
import io.github.mfuerstenau.leia.protocol.document.TermVectorTerm;
import io.github.mfuerstenau.leia.protocol.index.DocValuesType;
import io.github.mfuerstenau.leia.protocol.index.Feature;
import io.github.mfuerstenau.leia.protocol.index.GetIndexStatsParams;
import io.github.mfuerstenau.leia.protocol.index.GetIndexStatsResult;
import io.github.mfuerstenau.leia.protocol.index.IndexEntry;
import io.github.mfuerstenau.leia.protocol.index.IndexField;
import io.github.mfuerstenau.leia.protocol.index.IndexOptions;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexParams;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexResult;
import io.github.mfuerstenau.leia.protocol.index.OpenMode;
import io.github.mfuerstenau.leia.protocol.index.SkippedIndex;
import io.github.mfuerstenau.leia.protocol.index.TermStats;
import io.github.mfuerstenau.leia.protocol.search.StartSearchParams;
import io.github.mfuerstenau.leia.protocol.terms.ExportTermsResult;
import io.github.mfuerstenau.leia.protocol.terms.FieldTerms;
import io.github.mfuerstenau.leia.protocol.terms.GetExportTermsResult;
import io.github.mfuerstenau.leia.protocol.terms.GetFieldTermScanResult;
import io.github.mfuerstenau.leia.protocol.terms.GetPositionsParams;
import io.github.mfuerstenau.leia.protocol.terms.GetPostingsParams;
import io.github.mfuerstenau.leia.protocol.terms.GetPostingsResult;
import io.github.mfuerstenau.leia.protocol.terms.GetTermsParams;
import io.github.mfuerstenau.leia.protocol.terms.GetTermsResult;
import io.github.mfuerstenau.leia.protocol.terms.Posting;
import io.github.mfuerstenau.leia.protocol.terms.StartExportTermsParams;
import io.github.mfuerstenau.leia.protocol.terms.StartFieldTermScanParams;
import io.github.mfuerstenau.leia.protocol.terms.TopTerm;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Lucene 4.x and 5.x indexes read through {@link LeiaBackend} with the relocated Lucene 5.5 (ADR 0046):
 * doc values, binary terms, deleted postings, positions with offsets and payloads, commits with codecs,
 * the check at its levels, and old indexes of several versions under one path. The indexes, from
 * leia-lucene5's writers, have two commits; the latest holds documents 1 to 7, with id 2 deleted, in
 * the segments {@code _0} (plain) and {@code _1} (compound). 5.x also set the {@code dv_num} of id 4
 * to 400.
 */
class Lucene5BackendTest {

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

    private static BytesValue text(String text) {
        return new BytesValue(text, false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"lucene4", "lucene5"})
    void opensTheIndexWithItsFeatures(String name) throws Exception {
        OpenIndexResult opened = open(testIndex(name));
        assertThat(opened.luceneRuntime()).isEqualTo("5.5.5");
        assertThat(opened.numDocs()).hasValue(6);
        assertThat(opened.maxDoc()).hasValue(7);
        assertThat(opened.features())
                .containsExactlyInAnyOrder(
                        Feature.INDEX_STATS,
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
                        Feature.CLASSIC_SEARCH,
                        Feature.TERM_SEARCH,
                        Feature.FILTER,
                        Feature.EXPLAIN,
                        Feature.RECONSTRUCT,
                        Feature.COMMITS,
                        Feature.CHECK_INDEX,
                        Feature.CHECK_LEVELS);
        assertThat(opened.indexes().getFirst().reader().orElseThrow().createdVersionMajor())
                .isEqualTo(name.equals("lucene4") ? 4 : 5);

        GetIndexStatsResult stats = backend.call(Methods.GET_INDEX_STATS, new GetIndexStatsParams());
        assertThat(stats.indexes().getFirst().commitLuceneVersion())
                .isEqualTo(name.equals("lucene4") ? "4.10.4" : "5.5.5");
        IndexField body = stats.fields().get(1);
        assertThat(body.indexOptions()).isEqualTo(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS);
        assertThat(body.hasPayloads()).isTrue();
        assertThat(body.terms()).contains(new TermStats(7, 26, 27));
        assertThat(stats.fields())
                .filteredOn(field -> field.name().startsWith("dv_"))
                .extracting(IndexField::docValuesType)
                .containsExactly(
                        DocValuesType.NUMERIC,
                        DocValuesType.BINARY,
                        DocValuesType.SORTED,
                        DocValuesType.SORTED_SET,
                        DocValuesType.SORTED_NUMERIC);
    }

    @ParameterizedTest
    @ValueSource(strings = {"lucene4", "lucene5"})
    void scansTheStoredValues(String name) throws Exception {
        OpenIndexResult opened = open(testIndex(name));

        RuntimeStoredScans.scansTheStoredValues(backend, opened.maxDoc().orElseThrow(), true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"lucene4", "lucene5"})
    void filtersTheDocuments(String name) throws Exception {
        open(testIndex(name));

        RuntimeFilters.filtersTheDocuments(backend, true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"lucene4", "lucene5"})
    void explainsAHit(String name) throws Exception {
        OpenIndexResult opened = open(testIndex(name));

        RuntimeExplains.explainsAHit(backend, opened.maxDoc().orElseThrow(), true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"lucene4", "lucene5"})
    void searchesATerm(String name) throws Exception {
        OpenIndexResult opened = open(testIndex(name));

        RuntimeSearches.searchesATerm(backend, opened.maxDoc().orElseThrow(), "beta", true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"lucene4", "lucene5"})
    void rebuildsADocument(String name) throws Exception {
        OpenIndexResult opened = open(testIndex(name));

        RuntimeRebuilds.rebuildsADocument(backend, opened.maxDoc().orElseThrow(), false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"lucene4", "lucene5"})
    void readsDocumentsWithDocValues(String name) throws Exception {
        open(testIndex(name));
        Map<String, DocumentField> first = fields(0);
        assertThat(first.get("dv_num").docValues())
                .contains(new DocValues(DocValuesType.NUMERIC, List.of(1L), List.of()));
        assertThat(first.get("dv_set").docValues())
                .contains(new DocValues(DocValuesType.SORTED_SET, List.of(), List.of(text("x"), text("y1"))));
        assertThat(first.get("dv_sorted_num").docValues().orElseThrow().numbers())
                .containsExactly(-1L, 1L);
        assertThat(first.get("blob").stored()).containsExactly(new StoredValue(StoredType.BINARY, "fffe01", true));
        assertThat(first.get("price").stored()).containsExactly(new StoredValue(StoredType.INT, "10", false));
        assertThat(fields(2).get("dv_num").docValues()).isEmpty();
        assertThat(fields(3).get("dv_num").docValues().orElseThrow().numbers())
                .containsExactly(name.equals("lucene4") ? 4L : 400L);

        List<TermVectorTerm> vector = backend.call(Methods.GET_TERM_VECTOR, new GetTermVectorParams(0, "body"))
                .terms();
        assertThat(vector)
                .filteredOn(term -> term.term().equals("alpha"))
                .singleElement()
                .satisfies(alpha -> assertThat(alpha.positions())
                        .containsExactly(new TermPosition(
                                OptionalInt.of(0), OptionalInt.of(0), OptionalInt.of(8), Optional.of("6131"))));
    }

    private Map<String, DocumentField> fields(int docId) throws Exception {
        Map<String, DocumentField> fields = new HashMap<>();
        for (DocumentField field :
                backend.call(Methods.GET_DOCUMENT, new GetDocumentParams(docId)).fields()) {
            fields.put(field.name(), field);
        }
        return fields;
    }

    @ParameterizedTest
    @ValueSource(strings = {"lucene4", "lucene5"})
    void readsTermsPostingsAndPositions(String name) throws Exception {
        open(testIndex(name));
        // Trie-encoded terms are bytes; paging goes on from the next one, whatever it is.
        GetTermsResult price = backend.call(Methods.GET_TERMS, new GetTermsParams("price", Optional.empty(), 2));
        assertThat(price.terms()).hasSize(2);
        GetTermsResult rest = backend.call(Methods.GET_TERMS, new GetTermsParams("price", price.next(), 1));
        assertThat(rest.terms().getFirst().term())
                .isEqualTo(price.next().orElseThrow().value());
        GetTermsResult ids = backend.call(Methods.GET_TERMS, new GetTermsParams("id", Optional.of(text("6")), 10));
        assertThat(ids.terms()).extracting(term -> term.term()).containsExactly("6", "7");

        GetPostingsResult alpha = backend.call(
                Methods.GET_POSTINGS, new GetPostingsParams("body", text("alpha"), 0, 2, Optional.empty()));
        assertThat(alpha.docFreq()).isEqualTo(7);
        assertThat(alpha.postings())
                .containsExactly(
                        new Posting(0, false, OptionalInt.of(1), Optional.empty()),
                        new Posting(1, true, OptionalInt.of(1), Optional.empty()));
        assertThat(alpha.nextDoc()).hasValue(2);
        backend.fails(
                Methods.GET_POSTINGS,
                new GetPostingsParams("body", text("nothing"), 0, 2, Optional.empty()),
                ErrorCode.TERM_NOT_FOUND);

        assertThat(backend.call(Methods.GET_POSITIONS, new GetPositionsParams(0, "body", text("gamma")))
                        .positions())
                .containsExactly(
                        new TermPosition(OptionalInt.of(4), OptionalInt.of(25), OptionalInt.of(30), Optional.empty()),
                        new TermPosition(OptionalInt.of(5), OptionalInt.of(31), OptionalInt.of(36), Optional.empty()));
        // A deleted document's too.
        assertThat(backend.call(Methods.GET_POSITIONS, new GetPositionsParams(1, "body", text("alpha")))
                        .positions())
                .extracting(TermPosition::payload)
                .containsExactly(Optional.of("6132"));
        backend.fails(
                Methods.GET_POSITIONS, new GetPositionsParams(1, "body", text("gamma")), ErrorCode.TERM_NOT_FOUND);
        backend.fails(Methods.GET_POSITIONS, new GetPositionsParams(9, "body", text("alpha")), ErrorCode.DOC_NOT_FOUND);

        FieldTerms top = backend.job(
                Methods.START_FIELD_TERM_SCAN,
                new StartFieldTermScanParams("body", 2),
                Methods.GET_FIELD_TERM_SCAN,
                GetFieldTermScanResult::terms);
        assertThat(top.uniqueTerms()).isEqualTo(11);
        assertThat(top.topTerms())
                .containsExactly(new TopTerm("alpha", false, 7, 7), new TopTerm("document", false, 7, 7));
    }

    @ParameterizedTest
    @ValueSource(strings = {"lucene4", "lucene5"})
    void exportsTrieEncodedTermsAsTheTermsScreenShowsThem(String name) throws Exception {
        open(testIndex(name));
        Path file = tmp.resolve(name + "-price.tsv");
        ExportTermsResult result = exportTerms("price", file);
        List<String> expected = termLines("price");
        assertThat(expected).isNotEmpty();
        // Trie terms use 7 bits a byte, so they are UTF-8 and written as they are, a newline too.
        assertThat(Files.readString(file, StandardCharsets.UTF_8))
                .isEqualTo(expected.stream().map(line -> line + "\n").collect(Collectors.joining()));
        assertThat(result.terms()).isEqualTo(expected.size());
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
    void searchesWithALanguageAnalyzer() throws Exception {
        open(testIndex("lucene5"));
        Analyzer english = new Analyzer(
                Optional.empty(),
                Optional.of(new PresetAnalyzer(
                        "org.apache.lucene.analysis.en.EnglishAnalyzer",
                        Optional.empty(),
                        Optional.empty(),
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
        assertThat(backend.search(new StartSearchParams(
                                classic("documents AND gamma", english),
                                BM25,
                                List.of(),
                                0,
                                10,
                                true,
                                Optional.of(List.of()),
                                Optional.empty()))
                        .totalHits())
                .isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"lucene4", "lucene5"})
    void readsTheCommits(String name) throws Exception {
        boolean five = name.equals("lucene5");
        open(testIndex(name));
        List<Commit> commits =
                backend.call(Methods.GET_COMMITS, new GetCommitsParams()).commits();
        assertThat(commits)
                .extracting(Commit::segmentsFile, Commit::current, Commit::luceneVersion)
                .containsExactly(
                        tuple("segments_2", true, five ? "5.5.5" : "4.10.4"),
                        tuple("segments_1", false, five ? "5.5.5" : "4.10.4"));
        GetCommitResult details = backend.call(Methods.GET_COMMIT, new GetCommitParams(2, OptionalInt.empty()));
        Segment plain = details.segments().getFirst();
        assertThat(plain.codec()).contains(five ? "Lucene54" : "Lucene410");
        assertThat(plain.id().isPresent()).isEqualTo(five);
        assertThat(plain.formats()).hasValueSatisfying(formats -> {
            assertThat(formats.points()).isEmpty();
            assertThat(formats.knnVectors()).isEmpty();
        });
        assertThat(plain.fieldFormats())
                .filteredOn(formats -> formats.field().equals("dv_num"))
                .extracting(FieldFormats::docValues)
                .containsExactly(Optional.of(five ? "Lucene54" : "Lucene410"));
        assertThat(plain.docValuesGen().isPresent()).isEqualTo(five);
        assertThat(plain.fieldInfosGen().isPresent()).isEqualTo(five);
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
        open(testIndex("lucene4"));
        Check fast = check(new StartCheckIndexParams(OptionalInt.of(1)));
        assertThat(fast.lines()).contains("Checking with Lucene 5.5.5").noneMatch(line -> line.contains("test: terms"));
        IndexCheck index = fast.result().indexes().getFirst();
        assertThat(index.clean()).isTrue();
        assertThat(index.segments())
                .allSatisfy(segment -> assertThat(segment.codec()).contains("Lucene410"));
        Check slow = check(new StartCheckIndexParams(OptionalInt.of(3)));
        assertThat(slow.lines()).anyMatch(line -> line.contains("test: terms"));
        assertThat(slow.result().indexes().getFirst().clean()).isTrue();
    }

    @Test
    void opensWithoutAReaderThroughTheRuntime() throws Exception {
        OpenIndexResult opened = backend.call(
                Methods.OPEN_INDEX,
                new OpenIndexParams(
                        testIndex("lucene4").toString(), Optional.empty(), Optional.of(OpenMode.DIRECTORY_ONLY)));
        // Lucene 5.5 checks it at the levels.
        assertThat(opened.features())
                .containsExactlyInAnyOrder(Feature.COMMITS, Feature.CHECK_INDEX, Feature.CHECK_LEVELS);
        // Read by Lucene 5.5: Lucene 10 would list them as unreadable.
        assertThat(backend.call(Methods.GET_COMMITS, new GetCommitsParams()).commits())
                .extracting(Commit::segmentsFile, Commit::current)
                .containsExactly(tuple("segments_2", false), tuple("segments_1", false));
        assertThat(backend.call(Methods.GET_COMMIT, new GetCommitParams(2, OptionalInt.empty()))
                        .segments())
                .allSatisfy(segment -> assertThat(segment.codec()).contains("Lucene410"));
        Check check = check(new StartCheckIndexParams(OptionalInt.of(2)));
        assertThat(check.lines()).contains("Checking with Lucene 5.5.5");
        assertThat(check.result().indexes().getFirst().clean()).isTrue();
        backend.fails(Methods.GET_DOCUMENT, new GetDocumentParams(0), ErrorCode.NO_READER);
    }

    @Test
    void opensA4xAndA5xIndexAsOne() throws Exception {
        copy(testIndex("lucene4"), tmp.resolve("a"));
        copy(testIndex("lucene5"), tmp.resolve("b"));
        OpenIndexResult opened = open(tmp);
        assertThat(opened.maxDoc()).hasValue(14);
        assertThat(opened.skipped()).isEmpty();
        assertThat(opened.indexes())
                .extracting(
                        IndexEntry::path, entry -> entry.reader().orElseThrow().docBase())
                .containsExactly(
                        tuple(tmp.resolve("a").toString(), 0),
                        tuple(tmp.resolve("b").toString(), 7));
        assertThat(fields(10).get("dv_num").docValues().orElseThrow().numbers()).containsExactly(400L);
        assertThat(check(new StartCheckIndexParams()).result().indexes())
                .extracting(IndexCheck::clean)
                .containsExactly(true, true);
    }

    @Test
    void a3xFirstIndexSkipsA4xOne() throws Exception {
        copy(testIndex("lucene3"), tmp.resolve("a"));
        copy(testIndex("lucene4"), tmp.resolve("b"));
        OpenIndexResult opened = open(tmp);
        assertThat(opened.luceneRuntime()).isEqualTo("3.6.2");
        assertThat(opened.skipped())
                .extracting(SkippedIndex::path)
                .containsExactly(tmp.resolve("b").toString());
        assertThat(opened.skipped().getFirst().message()).contains("Lucene 4", "open it on its own");
        // Each index is checked by the Lucene that reads it.
        Check check = check(new StartCheckIndexParams());
        assertThat(check.lines())
                .contains("Checking with Lucene 3.6.2, which has no levels", "Checking with Lucene 5.5.5");
        assertThat(check.result().indexes())
                .extracting(IndexCheck::skipped, IndexCheck::clean)
                .containsExactly(tuple(false, true), tuple(true, true));
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
