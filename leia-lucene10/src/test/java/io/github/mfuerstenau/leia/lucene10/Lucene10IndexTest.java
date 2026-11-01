package io.github.mfuerstenau.leia.lucene10;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.github.mfuerstenau.leia.runtime.Check;
import io.github.mfuerstenau.leia.runtime.Commits;
import io.github.mfuerstenau.leia.runtime.DirectoryKind;
import io.github.mfuerstenau.leia.runtime.DocValuesSearch;
import io.github.mfuerstenau.leia.runtime.Documents;
import io.github.mfuerstenau.leia.runtime.Highlight;
import io.github.mfuerstenau.leia.runtime.Knn;
import io.github.mfuerstenau.leia.runtime.LuceneRuntime;
import io.github.mfuerstenau.leia.runtime.MoreLikeThisSearch;
import io.github.mfuerstenau.leia.runtime.Points;
import io.github.mfuerstenau.leia.runtime.Rebuild;
import io.github.mfuerstenau.leia.runtime.RuntimeIndex;
import io.github.mfuerstenau.leia.runtime.Scoring;
import io.github.mfuerstenau.leia.runtime.Search;
import io.github.mfuerstenau.leia.runtime.StandardSearch;
import io.github.mfuerstenau.leia.runtime.Terms;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.function.IntUnaryOperator;
import java.util.stream.Stream;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Reads the indexes of leia-core's build through the runtime interface: {@code lucene9}, written by
 * 9.12 with 3 documents ("document number 1" to 3, ids 1 to 3, KNN vectors [i, 0, 1] and int points
 * i × 10), of which document 1 is deleted; and the demo index of Lucene 10 (docs/demo-index.md). The
 * results are checked against leia-core in leia-server's {@code Lucene10ParityTest}; this test checks
 * that each group answers, and that nothing is written.
 */
class Lucene10IndexTest {

    private static final Search.ParserOptions BODY = new Search.ParserOptions("body", false, false, 0, 0, true, false);
    private static final Search.AnalyzerSpec STANDARD = new Search.AnalyzerSpec.Standard();

    private static Path testIndex(String name) {
        String dir = System.getProperty("leia.test.index." + name);
        if (dir == null) {
            throw new IllegalStateException("System property leia.test.index." + name + " is not set");
        }
        return Path.of(dir).toAbsolutePath().normalize();
    }

    private static RuntimeIndex open(String name) throws IOException {
        return new Lucene10Runtime().open(List.of(testIndex(name)), DirectoryKind.FS);
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void isFoundAsARuntime() {
        assertThat(ServiceLoader.load(LuceneRuntime.class))
                .singleElement()
                .isInstanceOfSatisfying(Lucene10Runtime.class, runtime -> {
                    assertThat(runtime.apiVersion()).isEqualTo(LuceneRuntime.API_VERSION);
                    assertThat(runtime.indexMajors()).containsExactlyInAnyOrder(9, 10);
                    assertThat(runtime.luceneVersion()).startsWith("10.");
                });
    }

    @Test
    void readsTheStatsAndFields() throws IOException {
        try (RuntimeIndex index = open("lucene9")) {
            RuntimeIndex.Stats stats = index.stats();
            assertThat(stats.numDocs()).isEqualTo(2);
            assertThat(stats.maxDoc()).isEqualTo(3);
            RuntimeIndex.IndexStats only = stats.indexes().getFirst();
            assertThat(only.path()).isEqualTo(testIndex("lucene9"));
            assertThat(only.segmentsFile()).isEqualTo("segments_2");
            assertThat(only.generation()).isEqualTo(2);
            assertThat(only.userData()).containsEntry("writer", "leia LegacyIndexWriter");
            assertThat(only.segmentVersions()).containsExactly("9.12.3");
            assertThat(only.luceneVersion()).contains("9.12.3");
            assertThat(only.createdVersionMajor()).hasValue(9);
            assertThat(only.segments())
                    .extracting(
                            RuntimeIndex.Segment::name,
                            RuntimeIndex.Segment::docBase,
                            RuntimeIndex.Segment::maxDoc,
                            RuntimeIndex.Segment::numDocs)
                    .containsExactly(tuple("_0", 0, 3, 2));
            assertThat(only.segments().getFirst().id()).isPresent();
            assertThat(only.segments().getFirst().files()).contains("_0.si");
            assertThat(index.fields())
                    .extracting(RuntimeIndex.Field::name)
                    .containsExactly("id", "body", "vector", "number");
            RuntimeIndex.Field vector = index.fields().get(2);
            assertThat(vector.vectors())
                    .contains(new RuntimeIndex.VectorFormat(
                            3, RuntimeIndex.VectorEncoding.FLOAT32, RuntimeIndex.VectorSimilarity.EUCLIDEAN));
            assertThat(index.fields().get(3).pointDimensionCount()).isEqualTo(1);
            assertThat(index.fields().get(1).terms()).contains(new RuntimeIndex.TermStats(3, 9, 9));
            assertThat(index.directories()).containsExactly(testIndex("lucene9"));
            assertThat(index.directory()).isEqualTo(DirectoryKind.FS);
        }
    }

    @Test
    void readsTheDocuments() throws IOException {
        try (RuntimeIndex index = open("lucene9")) {
            Documents documents = index.documents().orElseThrow();
            Documents.IndexDocument deleted = documents.document(1);
            assertThat(deleted.deleted()).isTrue();
            Documents.DocumentField body = deleted.fields().get(1);
            assertThat(body.stored()).containsExactly(new RuntimeIndex.StoredValue.Text("document number 2"));
            assertThat(body.norm()).isPresent();
            assertThat(((RuntimeIndex.Vector.Floats)
                                    deleted.fields().get(2).vector().orElseThrow())
                            .values())
                    .containsExactly(2f, 0f, 1f);
            assertThat(documents.storedValues(0, 3, Optional.of(Set.of("id")), false))
                    .extracting(Documents.StoredDocument::docId)
                    .containsExactly(0, 2);
            assertThat(documents.storedValues(0, 3, Optional.empty(), true))
                    .extracting(Documents.StoredDocument::deleted)
                    .containsExactly(false, true, false);
            // No term vectors in this index; a field it doesn't have has none either.
            assertThat(documents.termVector(0, "body")).isEmpty();
            assertThat(documents.termVector(0, "nope")).isEmpty();
            assertThatThrownBy(() -> documents.document(3)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void readsPositionsWithThePostings() throws IOException {
        try (RuntimeIndex index = open("lucene9")) {
            Terms terms = index.terms().orElseThrow();
            Terms.PostingsPage page = terms.postings("body", utf8("number"), 0, 10, 5);
            assertThat(page.postings())
                    .hasSize(3)
                    .allSatisfy(posting -> assertThat(posting.positions())
                            .usingRecursiveComparison()
                            .isEqualTo(terms.positions(posting.docId(), "body", utf8("number"))
                                    .orElseThrow()));
            assertThat(terms.postings("body", utf8("number"), 0, 10, 0).postings())
                    .allSatisfy(posting -> assertThat(posting.positions()).isEmpty());
        }
    }

    @Test
    void readsTheTermsAndPostings() throws IOException {
        try (RuntimeIndex index = open("lucene9")) {
            Terms terms = index.terms().orElseThrow();
            Terms.TermsPage page = terms.terms("body", Optional.of(utf8("3")), 2);
            assertThat(page.terms())
                    .extracting(term -> new String(term.term(), StandardCharsets.UTF_8))
                    .containsExactly("3", "document");
            assertThat(page.next()).hasValueSatisfying(next -> assertThat(next).isEqualTo(utf8("number")));
            assertThat(terms.terms("nope", Optional.empty(), 10).terms()).isEmpty();

            Terms.PostingsPage postings = terms.postings("body", utf8("number"), 0, 10, 0);
            assertThat(postings.docFreq()).isEqualTo(3);
            assertThat(postings.postings())
                    .containsExactly(
                            new Terms.Posting(0, 1, false),
                            new Terms.Posting(1, 1, true),
                            new Terms.Posting(2, 1, false));
            assertThat(terms.postings("body", utf8("nothing"), 0, 10, 0).postings())
                    .isEmpty();

            assertThat(terms.positions(2, "body", utf8("number")).orElseThrow())
                    .singleElement()
                    .satisfies(position -> assertThat(position.position()).hasValue(1));
            assertThat(terms.positions(0, "body", utf8("3"))).isEmpty();

            List<Long> progress = new ArrayList<>();
            Terms.FieldTerms top = terms.topTerms("body", 2, progress::add);
            assertThat(top.uniqueTerms()).isEqualTo(5);
            assertThat(top.topTerms())
                    .extracting(term -> new String(term.term(), StandardCharsets.UTF_8))
                    .containsExactly("document", "number");
            assertThat(progress).last().isEqualTo(5L);
        }
    }

    @Test
    void searchesScoresAndExplains() throws Exception {
        try (RuntimeIndex index = open("lucene9")) {
            Search search = index.search().orElseThrow();
            Search.ParsedQuery query = search.parse("number", BODY, STANDARD);
            assertThat(query).hasToString("body:number");

            Search.SearchResults results = search.search(query, 0, 10, Optional.of(List.of("id")), _ -> {});
            assertThat(results.totalHits()).isEqualTo(2);
            assertThat(results.hits()).extracting(Search.Hit::docId).containsExactlyInAnyOrder(0, 2);
            assertThat(results.hits().getFirst().fields()).containsOnlyKeys("id");

            Scoring scoring = index.scoring().orElseThrow();
            Search.SearchResults byDoc = scoring.search(
                    query,
                    new Scoring.Similarity.Classic(),
                    List.of(new Scoring.SortKey.Doc(true)),
                    0,
                    10,
                    Optional.empty(),
                    _ -> {});
            assertThat(byDoc.hits()).extracting(Search.Hit::docId).containsExactly(2, 0);
            assertThat(byDoc.hits().getFirst().sortValues())
                    .singleElement()
                    .satisfies(value -> assertThat(value.number()).contains(2));
            assertThatThrownBy(() -> scoring.search(
                            query,
                            new Scoring.Similarity.Bool(),
                            List.of(new Scoring.SortKey.Field(
                                    "body",
                                    Scoring.SortType.LONG,
                                    false,
                                    Scoring.SortSelector.MIN,
                                    Scoring.SortMissing.DEFAULT)),
                            0,
                            10,
                            Optional.empty(),
                            _ -> {}))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no doc values");

            Search.Matches all = search.matches(query, true, _ -> {});
            assertThat(all.docs().stream()).containsExactly(0, 1, 2);
            assertThat(search.matches(search.term("id", utf8("2")), false, _ -> {})
                            .docs()
                            .isEmpty())
                    .isTrue();
            assertThat(search.explain(query, 0, _ -> {}).match()).isTrue();
            assertThat(scoring.explain(query, new Scoring.Similarity.Bool(), 1, _ -> {})
                            .match())
                    .isFalse();
            assertThatThrownBy(() -> search.parse("body:(number", BODY, STANDARD))
                    .isInstanceOf(Search.InvalidQueryException.class);
            assertThatThrownBy(() ->
                            search.parse("number", BODY, new Search.AnalyzerSpec.Preset("org.example.NoSuchAnalyzer")))
                    .isInstanceOf(Search.InvalidAnalyzerException.class)
                    .hasMessage("no preset analyzer 'org.example.NoSuchAnalyzer'");
        }
    }

    @Test
    void stopsASearchWhenTheProgressThrows() throws Exception {
        try (RuntimeIndex index = open("demo")) {
            Search search = index.search().orElseThrow();
            Search.ParsedQuery query = search.parse(
                    "body:*a*", new Search.ParserOptions("body", false, true, 0, 0, true, false), STANDARD);
            assertThatThrownBy(() -> search.search(query, 0, 10, Optional.empty(), _ -> {
                        throw new CancellationException("stop");
                    }))
                    .isInstanceOf(CancellationException.class);
        }
    }

    @Test
    void searchesTheNearestVectors() throws Exception {
        try (RuntimeIndex index = open("lucene9")) {
            Knn knn = index.knn().orElseThrow();
            Search search = index.search().orElseThrow();
            RuntimeIndex.Vector target = new RuntimeIndex.Vector.Floats(new float[] {3, 0, 1});
            assertThat(search.search(
                                    knn.approximate("vector", target, 1, Optional.empty()),
                                    0,
                                    10,
                                    Optional.empty(),
                                    _ -> {})
                            .hits())
                    .extracting(Search.Hit::docId)
                    .containsExactly(2);
            assertThat(search.search(
                                    knn.exact("vector", target, Optional.of(search.term("id", utf8("1")))),
                                    0,
                                    10,
                                    Optional.empty(),
                                    _ -> {})
                            .hits())
                    .extracting(Search.Hit::docId)
                    .containsExactly(0);
            assertThat(knn.vector(1, "vector")).isPresent();
            assertThatThrownBy(() -> knn.vector(0, "body")).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void readsThePointsAndRebuilds() throws IOException {
        try (RuntimeIndex index = open("lucene9")) {
            Points points = index.points().orElseThrow();
            Points.Tree tree = points.tree("number");
            assertThat(tree.size()).isEqualTo(3);
            assertThat(tree.min())
                    .hasValueSatisfying(
                            min -> assertThat(HexFormat.of().formatHex(min)).isEqualTo("8000000a"));
            Points.CellContents root = points.cell("number", 0, List.of());
            assertThat(root.cell().leaf()).isTrue();
            assertThat(root.points()).extracting(Points.LeafPoint::deleted).containsExactly(false, true, false);
            assertThatThrownBy(() -> points.tree("body")).isInstanceOf(IllegalArgumentException.class);

            Rebuild rebuild = index.rebuild().orElseThrow();
            assertThat(rebuild.count(1, List.of("body", "number"))).hasValue(8);
            List<Rebuild.RebuiltField> fields = rebuild.rebuild(1, List.of("body", "number"), _ -> {});
            assertThat(fields.getFirst().source()).isEqualTo(Rebuild.Source.POSTINGS);
            assertThat(fields.getFirst().terms())
                    .extracting(term -> new String(term.term(), StandardCharsets.UTF_8))
                    .containsExactly("2", "document", "number");
            assertThat(fields.get(1).points())
                    .singleElement()
                    .satisfies(
                            point -> assertThat(HexFormat.of().formatHex(point)).isEqualTo("80000014"));
        }
    }

    @Test
    void highlights() throws Exception {
        try (RuntimeIndex index = open("lucene9")) {
            Search.ParsedQuery query = index.search().orElseThrow().parse("number", BODY, STANDARD);
            List<Long> progress = new ArrayList<>();
            Highlight.Highlighted found = index.highlight()
                    .orElseThrow()
                    .highlight(
                            query,
                            new Highlight.Options(false, STANDARD, false, 100_000),
                            List.of("body", "id"),
                            new int[] {0, 1},
                            new int[] {3, 3},
                            progress::add);
            assertThat(progress).containsExactly(0L, 1L, 2L, 3L);
            Highlight.FieldText text = found.found().get("body").get(1).orElseThrow();
            assertThat(text.content()).isEqualTo("document number 2");
            assertThat(text.passages().getFirst().matchStarts()).containsExactly(9);
            assertThat(found.sources()).containsEntry("body", Highlight.OffsetSource.ANALYSIS);
            assertThat(found.found().get("id")).containsExactly(Optional.empty(), Optional.empty());
        }
    }

    @Test
    void readsTheCommitsAndChecks() throws IOException {
        Path dir = testIndex("demo");
        Commits commits = new Lucene10Runtime().commits().orElseThrow();
        Commits.CommitList list = commits.commits(dir, DirectoryKind.FS);
        assertThat(list.commits()).extracting(Commits.Commit::generation).containsExactly(4L, 3L, 2L, 1L);
        assertThat(list.unreadable()).isEmpty();
        Commits.CommitDetails details =
                commits.commit(dir, DirectoryKind.NIO, 4).orElseThrow();
        assertThat(details.segments()).extracting(Commits.Segment::name).containsExactly("_0", "_1", "_2");
        assertThat(details.segments().get(2).fieldFormats()).isNotEmpty();
        assertThat(commits.commit(dir, DirectoryKind.FS, 9)).isEmpty();

        Check check = new Lucene10Runtime().check().orElseThrow();
        assertThat(check.hasLevels()).isTrue();
        assertThat(check.hasThreads()).isTrue();
        List<String> lines = new ArrayList<>();
        Check.CheckResult result = check.check(dir, DirectoryKind.FS, Check.Options.atLevel(1), lines::add, _ -> {});
        assertThat(result.clean()).isTrue();
        assertThat(result.segmentCount()).isEqualTo(3);
        assertThat(lines).isNotEmpty();
    }

    @ParameterizedTest
    @EnumSource(DirectoryKind.class)
    void opensThroughEachDirectory(DirectoryKind kind) throws IOException {
        try (RuntimeIndex index = new Lucene10Runtime().open(List.of(testIndex("demo")), kind)) {
            assertThat(index.directory()).isEqualTo(kind);
            assertThat(index.maxDoc()).isEqualTo(500);
        }
    }

    /**
     * Of several directories, one that fails to open is skipped, and so is one whose fields conflict
     * with those before it, as leia-core skips them (ADR 0021).
     */
    @Test
    void skipsADirectoryThatDoesNotOpen(@TempDir Path dir) throws IOException {
        Path empty = Files.createDirectory(dir.resolve("empty"));
        Path copy = Files.createDirectory(dir.resolve("copy"));
        try (Stream<Path> files = Files.list(testIndex("lucene9"))) {
            for (Path file : files.toList()) {
                Files.copy(file, copy.resolve(file.getFileName()));
            }
        }
        try (RuntimeIndex index = new Lucene10Runtime()
                .open(List.of(testIndex("lucene9"), empty, testIndex("demo"), copy), DirectoryKind.FS)) {
            assertThat(index.directories()).containsExactly(testIndex("lucene9"), copy);
            assertThat(index.skipped())
                    .extracting(RuntimeIndex.Skipped::path)
                    .containsExactly(empty, testIndex("demo"));
            assertThat(index.skipped().get(1).message()).contains("conflict");
            assertThat(index.maxDoc()).isEqualTo(6);
            assertThat(index.stats().indexes().get(1).docBase()).isEqualTo(3);
            assertThat(index.documents().orElseThrow().document(4).deleted()).isTrue();
        }
        assertThatThrownBy(() -> new Lucene10Runtime().open(List.of(empty), DirectoryKind.FS))
                .isInstanceOf(IOException.class);
    }

    @Test
    void reopensOnANewCommit(@TempDir Path dir) throws IOException {
        Path demo = testIndex("demo");
        try (Stream<Path> files = Files.list(demo)) {
            for (Path file : files.toList()) {
                if (!file.getFileName().toString().equals("segments_4")) {
                    Files.copy(file, dir.resolve(file.getFileName()));
                }
            }
        }
        try (RuntimeIndex index = new Lucene10Runtime().open(List.of(dir), DirectoryKind.FS)) {
            assertThat(index.stats().indexes().getFirst().segmentsFile()).isEqualTo("segments_3");
            assertThat(index.reopen()).isEmpty();
            Files.copy(demo.resolve("segments_4"), dir.resolve("segments_4"));
            try (RuntimeIndex reopened = index.reopen().orElseThrow()) {
                assertThat(reopened.stats().indexes().getFirst().segmentsFile()).isEqualTo("segments_4");
                assertThat(reopened.maxDoc()).isEqualTo(500);
                // The old index stays usable until it is closed.
                assertThat(index.documents().orElseThrow().document(0).docId()).isZero();
                assertThatThrownBy(index::reopen).isInstanceOf(IllegalStateException.class);
            }
        }
    }

    @Test
    void writesNothing() throws Exception {
        for (String name : List.of("lucene9", "demo")) {
            Path dir = testIndex(name);
            Map<String, String> before = hashes(dir);
            try (RuntimeIndex index = open(name)) {
                index.stats();
                index.documents().orElseThrow().document(0);
                Search search = index.search().orElseThrow();
                search.search(search.parse("number OR the", BODY, STANDARD), 0, 10, Optional.empty(), _ -> {});
                index.rebuild().orElseThrow().rebuild(0, List.of("body"), _ -> {});
            }
            new Lucene10Runtime().commits().orElseThrow().commits(dir, DirectoryKind.FS);
            new Lucene10Runtime()
                    .check()
                    .orElseThrow()
                    .check(dir, DirectoryKind.FS, Check.Options.atLevel(1), _ -> {}, _ -> {});
            // No file changed, and none was added, such as a write.lock: lucene9 has the one its writer left.
            assertThat(hashes(dir)).isEqualTo(before);
        }
    }

    /** The SHA-256 of each file of {@code dir}, by name. */
    private static Map<String, String> hashes(Path dir) throws IOException, NoSuchAlgorithmException {
        Map<String, String> hashes = new TreeMap<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.toList()) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (InputStream in = Files.newInputStream(file)) {
                    digest.update(in.readAllBytes());
                }
                hashes.put(file.getFileName().toString(), HexFormat.of().formatHex(digest.digest()));
            }
        }
        return hashes;
    }

    @Test
    void parsesWithTheStandardParserAndPoints() throws Exception {
        try (RuntimeIndex index = open("lucene9")) {
            Search.ParsedQuery query = index.standardSearch()
                    .orElseThrow()
                    .parse(
                            "number:[15 TO 30] OR missing",
                            new StandardSearch.Options("body", false, false, 0, 0, true),
                            Map.of("number", StandardSearch.PointType.INT),
                            STANDARD);
            assertThat(query).hasToString("number:[15 TO 30] body:missing");
            assertThat(index.search()
                            .orElseThrow()
                            .search(query, 0, 10, Optional.empty(), _ -> {})
                            .hits())
                    .extracting(Search.Hit::docId)
                    .containsExactly(2);
            assertThatThrownBy(() -> index.standardSearch()
                            .orElseThrow()
                            .parse(
                                    "body:(",
                                    new StandardSearch.Options("body", false, false, 0, 0, true),
                                    Map.of(),
                                    STANDARD))
                    .isInstanceOf(Search.InvalidQueryException.class)
                    .hasMessage("Cannot parse 'body:(': Encountered \"<EOF>\" at column 6.");
        }
    }

    @Test
    void findsDocumentsLikeATextAndHighlightsThem() throws Exception {
        try (RuntimeIndex index = open("lucene9")) {
            Search.ParsedQuery query = index.moreLikeThisSearch()
                    .orElseThrow()
                    .like(
                            new MoreLikeThisSearch.Source.Text("number"),
                            new MoreLikeThisSearch.Options(
                                    List.of("body"),
                                    1,
                                    1,
                                    OptionalInt.empty(),
                                    25,
                                    false,
                                    0,
                                    OptionalInt.empty(),
                                    Set.of()),
                            STANDARD);
            assertThat(query)
                    .hasToString("like(text \"number\", fields [body], minTermFreq 1, minDocFreq 1, maxQueryTerms 25)");
            Search.SearchResults results = index.search().orElseThrow().search(query, 0, 10, Optional.empty(), _ -> {});
            assertThat(results.hits()).extracting(Search.Hit::docId).containsExactlyInAnyOrder(0, 2);
            assertThat(results.likeTerms().orElseThrow())
                    .singleElement()
                    .satisfies(term -> assertThat(term.term()).isEqualTo("number"));
            assertThat(index.search().orElseThrow().matches(query, true, _ -> {}).docs().stream())
                    .containsExactly(0, 1, 2);
            assertThat(index.search().orElseThrow().explain(query, 0, _ -> {}).match())
                    .isTrue();
            Highlight.Highlighted found = index.highlight()
                    .orElseThrow()
                    .highlight(
                            query,
                            new Highlight.Options(false, STANDARD, false, 100_000),
                            List.of("body"),
                            new int[] {0},
                            new int[] {3},
                            _ -> {});
            assertThat(found.found().get("body").getFirst().orElseThrow().passages())
                    .singleElement()
                    .satisfies(passage -> assertThat(passage.matchStarts()).containsExactly(9));
        }
    }

    @Test
    void searchesDocValues() throws Exception {
        try (RuntimeIndex index = open("demo")) {
            DocValuesSearch docValues = index.docValuesSearch().orElseThrow();
            Search search = index.search().orElseThrow();
            Search.ParsedQuery numbers = docValues.numbers(
                    "quantity",
                    DocValuesSearch.NumberEncoding.LONG,
                    new DocValuesSearch.Match.Range<>(Optional.of("0"), Optional.empty(), true, true));
            Search.ParsedQuery prefix = docValues.prefix("category", new byte[0]);
            Search.ParsedQuery binary = docValues.bytes(
                    "checksum", new DocValuesSearch.Match.Range<>(Optional.empty(), Optional.empty(), true, true));
            for (Search.ParsedQuery query : List.of(numbers, prefix, binary)) {
                Search.SearchResults results = search.search(query, 0, 1, Optional.empty(), _ -> {});
                assertThat(results.totalHits()).as(query.toString()).isPositive();
                assertThat(search.explain(query, results.hits().getFirst().docId(), _ -> {})
                                .match())
                        .isTrue();
            }
            assertThatThrownBy(() -> docValues.numbers(
                            "category",
                            DocValuesSearch.NumberEncoding.LONG,
                            new DocValuesSearch.Match.Values<>(List.of("1"))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("the field 'category' has SORTED doc values, which hold bytes, not numbers");
        }
    }

    @Test
    void namesTheDirectoryClass() throws IOException {
        try (RuntimeIndex mmap = new Lucene10Runtime().open(List.of(testIndex("lucene9")), DirectoryKind.MMAP);
                RuntimeIndex nio = new Lucene10Runtime().open(List.of(testIndex("lucene9")), DirectoryKind.NIO)) {
            assertThat(mmap.stats().directoryClass()).isEqualTo("MMapDirectory");
            assertThat(nio.stats().directoryClass()).isEqualTo("NIOFSDirectory");
        }
    }

    /** Options of the classic parser with these fuzzy edits, split and dates. */
    private static Search.ParserOptions parserOptions(
            int fuzzyMaxEdits, boolean splitOnWhitespace, boolean autoGeneratePhraseQueries, Search.Dates dates) {
        return new Search.ParserOptions(
                "body", false, false, 0, 0, true, autoGeneratePhraseQueries, fuzzyMaxEdits, splitOnWhitespace, dates);
    }

    @Test
    void readsRangeBoundsAsDates() throws Exception {
        Search.Dates dates = new Search.Dates(
                Optional.of(Search.DateResolution.YEAR),
                Map.of("published", Search.DateResolution.MILLISECOND),
                Optional.of(Locale.GERMANY),
                Optional.of(ZoneId.of("Europe/Berlin")));
        try (RuntimeIndex index = open("lucene9")) {
            Search.ParsedQuery query = index.search()
                    .orElseThrow()
                    .parse(
                            "published:[15.01.26 TO 31.01.26] other:{15.01.26 TO 31.01.26}",
                            parserOptions(2, true, false, dates),
                            new Search.AnalyzerSpec.Standard());

            // The field's own resolution, and the default one for the other field; the short dates of the
            // locale, read in the zone; an inclusive upper bound takes the whole day.
            assertThat(query).hasToString("published:[20260114230000000 TO 20260131225959999] other:{2026 TO 2026}");
        }
    }

    @Test
    void takesTheFuzzyEdits() throws Exception {
        try (RuntimeIndex index = open("lucene9")) {
            Search.ParsedQuery query = index.search()
                    .orElseThrow()
                    .parse(
                            "word~",
                            parserOptions(1, true, false, Search.Dates.NONE),
                            new Search.AnalyzerSpec.Standard());

            assertThat(query).hasToString("body:word~1");
        }
    }

    @Test
    void generatesPhraseQueriesWhileSplittingOnWhitespace() throws Exception {
        try (RuntimeIndex index = open("lucene9")) {
            Search search = index.search().orElseThrow();
            Search.AnalyzerSpec standard = new Search.AnalyzerSpec.Standard();

            assertThat(search.parse("wi-fi", parserOptions(2, true, true, Search.Dates.NONE), standard))
                    .hasToString("body:\"wi fi\"");
            assertThat(search.parse("wi-fi", parserOptions(2, false, false, Search.Dates.NONE), standard))
                    .hasToString("body:wi body:fi");
        }
    }

    /** A segment written with a block of documents says so, from Lucene 9.9 on. */
    @Test
    void readsTheSegmentsBlocks(@TempDir Path dir) throws IOException {
        try (Directory directory = FSDirectory.open(dir);
                IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(new StandardAnalyzer()))) {
            Document child = new Document();
            child.add(new StringField("type", "child", Field.Store.YES));
            Document parent = new Document();
            parent.add(new StringField("type", "parent", Field.Store.YES));
            writer.addDocuments(List.of(child, parent));
            writer.commit();
            writer.addDocument(parent);
        }
        // Gone once the writer is closed: the test reads the index as leia does.
        Files.deleteIfExists(dir.resolve("write.lock"));

        Commits commits = new Lucene10Runtime().commits().orElseThrow();
        long generation =
                commits.commits(dir, DirectoryKind.FS).commits().getFirst().generation();
        assertThat(commits.commit(dir, DirectoryKind.FS, generation)
                        .orElseThrow()
                        .segments())
                .extracting(Commits.Segment::hasBlocks)
                .containsExactly(true, false);
    }

    @Test
    void readsWhichFieldsTheDocumentsStore() throws IOException {
        try (RuntimeIndex index = open("demo")) {
            StoredFieldsSeen seen = storedFields(index);

            // docs/demo-index.md: every document stores its id, 42 alone a blob, the deleted 74 alone
            // deleted_only, and keywords is never stored.
            assertThat(seen.stored()).hasSize(500);
            assertThat(seen.stored().values())
                    .allSatisfy(fields -> assertThat(fields).contains("id").doesNotContain("keywords"));
            assertThat(seen.stored().get(42)).contains("blob");
            assertThat(seen.stored().get(74)).contains("deleted_only");
            assertThat(seen.deleted()).isEqualTo(13);
            assertThat(segments(index))
                    .allSatisfy(segment -> assertThat(segment.id()).isPresent());
        }
    }

    @Test
    void readsTheStoredValuesOfEachSegment() throws IOException {
        try (RuntimeIndex index = open("demo")) {
            assertSegmentsReadAsPages(index);
        }
    }

    /**
     * Reads the stored values a segment at a time (ADR 0114), with and without the deleted documents
     * and a field, as the pages of the whole index have them; then only the odd documents of each
     * segment, and with a progress that stops the read.
     */
    private static void assertSegmentsReadAsPages(RuntimeIndex index) throws IOException {
        Documents documents = index.documents().orElseThrow();
        for (Optional<Set<String>> fields : List.of(Optional.<Set<String>>empty(), Optional.of(Set.of("id")))) {
            for (boolean includeDeleted : new boolean[] {false, true}) {
                assertThat(bySegment(index, fields, includeDeleted, IntUnaryOperator.identity()))
                        .containsExactlyElementsOf(
                                documents.storedValues(0, index.maxDoc(), fields, includeDeleted).stream()
                                        .map(document -> described(document, document.docId()))
                                        .toList());
            }
        }
        int segment = 0;
        for (RuntimeIndex.Segment info : segments(index)) {
            List<Integer> read = new ArrayList<>();
            // The next odd document at or after doc.
            documents.storedValues(
                    segment, Optional.empty(), true, doc -> doc | 1, document -> read.add(document.docId()), _ -> {});
            assertThat(read).hasSize(info.maxDoc() / 2).allMatch(doc -> doc % 2 == 1);
            segment++;
        }
        assertThatThrownBy(() ->
                        documents.storedValues(0, Optional.empty(), true, IntUnaryOperator.identity(), _ -> {}, _ -> {
                            throw new CancellationException();
                        }))
                .isInstanceOf(CancellationException.class);
        int segments = segment;
        assertThatThrownBy(() -> documents.storedValues(
                        segments, Optional.empty(), true, IntUnaryOperator.identity(), _ -> {}, _ -> {}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** The stored documents of every segment, read a segment at a time, as {@link #described}. */
    private static List<String> bySegment(
            RuntimeIndex index, Optional<Set<String>> fields, boolean includeDeleted, IntUnaryOperator next)
            throws IOException {
        Documents documents = index.documents().orElseThrow();
        List<String> read = new ArrayList<>();
        int segment = 0;
        for (RuntimeIndex.IndexStats directory : index.stats().indexes()) {
            for (RuntimeIndex.Segment info : directory.segments()) {
                int base = directory.docBase() + info.docBase();
                List<Long> progress = new ArrayList<>();
                documents.storedValues(
                        segment,
                        fields,
                        includeDeleted,
                        next,
                        document -> read.add(described(document, base + document.docId())),
                        progress::add);
                assertThat(progress).isSorted().last().isEqualTo((long) info.maxDoc());
                segment++;
            }
        }
        return read;
    }

    /** A stored document with its id in the index, as text, binary values as their bytes. */
    private static String described(Documents.StoredDocument document, int docId) {
        StringBuilder text = new StringBuilder().append(docId).append(document.deleted() ? " deleted" : "");
        document.values().forEach((field, values) -> {
            text.append(' ').append(field).append('=');
            for (RuntimeIndex.StoredValue value : values) {
                text.append(
                                switch (value) {
                                    case RuntimeIndex.StoredValue.Text t -> t.text();
                                    case RuntimeIndex.StoredValue.Binary b -> java.util.Arrays.toString(b.bytes());
                                    case RuntimeIndex.StoredValue.Numeric n ->
                                        n.number().toString();
                                })
                        .append(';');
            }
        });
        return text.toString();
    }

    /**
     * Which fields each document stores, by its id in the index, as the scans of the segments report
     * them (ADR 0113), and how many documents the segments say are deleted.
     */
    private static StoredFieldsSeen storedFields(RuntimeIndex index) throws IOException {
        Map<Integer, Set<String>> stored = new TreeMap<>();
        Documents documents = index.documents().orElseThrow();
        int segment = 0;
        int deleted = 0;
        for (RuntimeIndex.IndexStats directory : index.stats().indexes()) {
            for (RuntimeIndex.Segment info : directory.segments()) {
                int base = directory.docBase() + info.docBase();
                List<Long> progress = new ArrayList<>();
                documents.storedFields(
                        segment,
                        (doc, field) -> stored.computeIfAbsent(base + doc, _ -> new TreeSet<>())
                                .add(field),
                        progress::add);
                assertThat(progress).last().isEqualTo((long) info.maxDoc());
                int[] deletedDocs = documents.deletedDocs(segment);
                assertThat(deletedDocs).isSorted().hasSize(info.maxDoc() - info.numDocs());
                deleted += deletedDocs.length;
                segment++;
            }
        }
        int segments = segment;
        assertThatThrownBy(() -> documents.storedFields(segments, (doc, field) -> {}, _ -> {}))
                .isInstanceOf(IllegalArgumentException.class);
        return new StoredFieldsSeen(stored, deleted);
    }

    private record StoredFieldsSeen(Map<Integer, Set<String>> stored, int deleted) {}

    /** The segments of {@code index}, of all its directories. */
    private static List<RuntimeIndex.Segment> segments(RuntimeIndex index) throws IOException {
        return index.stats().indexes().stream()
                .flatMap(directory -> directory.segments().stream())
                .toList();
    }
}
