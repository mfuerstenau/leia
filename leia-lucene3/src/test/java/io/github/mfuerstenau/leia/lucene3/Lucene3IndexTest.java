package io.github.mfuerstenau.leia.lucene3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.github.mfuerstenau.leia.runtime.Check;
import io.github.mfuerstenau.leia.runtime.Commits;
import io.github.mfuerstenau.leia.runtime.DirectoryKind;
import io.github.mfuerstenau.leia.runtime.Documents;
import io.github.mfuerstenau.leia.runtime.LuceneRuntime;
import io.github.mfuerstenau.leia.runtime.Rebuild;
import io.github.mfuerstenau.leia.runtime.RuntimeIndex;
import io.github.mfuerstenau.leia.runtime.Search;
import io.github.mfuerstenau.leia.runtime.Terms;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.apache.lucene.store.MMapDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Reads the Lucene 2.4 and 3.x indexes that the build writes (build.gradle.kts) with plain Lucene 3.6,
 * through the runtime interface.
 */
class Lucene3IndexTest {

    private static final Search.ParserOptions BODY = new Search.ParserOptions("body", false, false, 0, 0, true, false);

    private static Path testIndex(String name) {
        String dir = System.getProperty("leia.test.index." + name);
        if (dir == null) {
            throw new IllegalStateException("System property leia.test.index." + name + " is not set");
        }
        return Path.of(dir).toAbsolutePath();
    }

    private static Path lucene24() {
        return testIndex("lucene24");
    }

    private static Path lucene3() {
        return testIndex("lucene3");
    }

    private static RuntimeIndex open(DirectoryKind kind) throws IOException {
        return new Lucene3Runtime().open(List.of(lucene24()), kind);
    }

    private static RuntimeIndex open3() throws IOException {
        return new Lucene3Runtime().open(List.of(lucene3()), DirectoryKind.FS);
    }

    @Test
    void isFoundAsARuntime() {
        assertThat(ServiceLoader.load(LuceneRuntime.class))
                .singleElement()
                .isInstanceOfSatisfying(Lucene3Runtime.class, runtime -> {
                    assertThat(runtime.apiVersion()).isEqualTo(LuceneRuntime.API_VERSION);
                    assertThat(runtime.indexMajors()).containsExactlyInAnyOrder(2, 3);
                    assertThat(runtime.luceneVersion()).startsWith("3.6");
                });
    }

    @Test
    void neverUnmaps() {
        // 3.6 unmaps through sun.misc.Cleaner, gone since Java 9: no Unsafe warning (ADR 0047).
        assertThat(MMapDirectory.UNMAP_SUPPORTED).isFalse();
    }

    @Test
    void readsTheStats() throws IOException {
        try (RuntimeIndex index = open(DirectoryKind.NIO)) {
            RuntimeIndex.Stats stats = index.stats();
            assertThat(stats.numDocs()).isEqualTo(2);
            assertThat(stats.maxDoc()).isEqualTo(3);
            RuntimeIndex.IndexStats only = stats.indexes().getFirst();
            assertThat(stats.indexes()).hasSize(1);
            assertThat(only.path()).isEqualTo(lucene24());
            assertThat(only.docBase()).isZero();
            assertThat(only.segmentVersions()).containsExactly("2.x");
            assertSegments(only);
            assertThat(only.oldestMajor()).isEqualTo(2);
            assertThat(only.segmentsFile()).startsWith("segments_");
            assertThat(index.directories()).containsExactly(lucene24());
            assertThat(index.skipped()).isEmpty();
            assertThat(stats.fields()).extracting(RuntimeIndex.Field::name).containsExactly("id", "body", "raw");
            assertThat(stats.fields().get(1))
                    .isEqualTo(new RuntimeIndex.Field(
                            "body",
                            1,
                            RuntimeIndex.IndexOptions.DOCS_AND_FREQS_AND_POSITIONS,
                            true,
                            false,
                            true,
                            RuntimeIndex.DocValuesType.NONE,
                            0,
                            0,
                            Optional.empty()));
            assertThat(stats.fields().get(2).indexOptions()).isEqualTo(RuntimeIndex.IndexOptions.NONE);
        }
    }

    @Test
    void readsDocumentsAndTermVectors() throws IOException {
        try (RuntimeIndex index = open(DirectoryKind.FS)) {
            Documents.IndexDocument doc = index.documents().orElseThrow().document(0);
            assertThat(doc.deleted()).isFalse();
            assertThat(doc.fields().get(0).stored()).containsExactly(new RuntimeIndex.StoredValue.Text("1"));
            assertThat(doc.fields().get(1).hasTermVector()).isTrue();
            assertThat(doc.fields().get(1).norm()).isPresent();
            RuntimeIndex.StoredValue raw = doc.fields().get(2).stored().getFirst();
            assertThat(((RuntimeIndex.StoredValue.Binary) raw).bytes())
                    .asString()
                    .isEqualTo("bytes1");
            assertThat(index.documents().orElseThrow().document(1).deleted()).isTrue();

            List<Documents.VectorTerm> vector = index.documents().orElseThrow().termVector(0, "body");
            assertThat(vector).extracting(term -> text(term.term())).contains("document", "number", "old");
            Documents.VectorTerm document = vector.stream()
                    .filter(term -> text(term.term()).equals("document"))
                    .findFirst()
                    .orElseThrow();
            assertThat(document.positions().getFirst().position()).hasValue(0);
            assertThat(document.positions().getFirst().startOffset()).hasValue(0);
            assertThat(document.positions().getFirst().endOffset()).hasValue(8);
            assertThat(document.positions().getFirst().payload()).isEmpty();
            assertThatThrownBy(() -> index.documents().orElseThrow().document(3))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void readsStoredValuesOfARange() throws IOException {
        try (RuntimeIndex index = open(DirectoryKind.FS)) {
            Documents documents = index.documents().orElseThrow();
            int maxDoc = index.maxDoc();
            List<Documents.StoredDocument> live = documents.storedValues(0, maxDoc, Optional.of(Set.of("id")), false);
            assertThat(live).extracting(Documents.StoredDocument::docId).doesNotContain(1);
            assertThat(live).extracting(Documents.StoredDocument::deleted).containsOnly(false);
            assertThat(live.getFirst().values())
                    .containsExactly(Map.entry("id", List.of(new RuntimeIndex.StoredValue.Text("1"))));

            List<Documents.StoredDocument> page = documents.storedValues(1, 3, Optional.empty(), true);
            assertThat(page)
                    .extracting(Documents.StoredDocument::docId, Documents.StoredDocument::deleted)
                    .containsExactly(tuple(1, true), tuple(2, false));
            // All fields, each with the values that document() reads.
            for (Documents.StoredDocument doc : page) {
                for (Documents.DocumentField field :
                        documents.document(doc.docId()).fields()) {
                    assertThat(doc.values().getOrDefault(field.name(), List.of()))
                            .usingRecursiveFieldByFieldElementComparator()
                            .isEqualTo(field.stored());
                }
            }
            assertThat(documents.storedValues(maxDoc, maxDoc, Optional.empty(), true))
                    .isEmpty();
            assertThatThrownBy(() -> documents.storedValues(-1, 1, Optional.empty(), true))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> documents.storedValues(2, 1, Optional.empty(), true))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> documents.storedValues(0, maxDoc + 1, Optional.empty(), true))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void readsTermsAndPostings() throws IOException {
        try (RuntimeIndex index = open(DirectoryKind.NIO)) {
            Terms.TermsPage page = index.terms().orElseThrow().terms("id", Optional.empty(), 2);
            assertThat(page.terms()).extracting(term -> text(term.term())).containsExactly("1", "2");
            assertThat(page.next().map(Lucene3IndexTest::text)).contains("3");
            assertThat(index.terms().orElseThrow().terms("id", page.next(), 2).terms())
                    .extracting(term -> text(term.term()), Terms.TermEntry::docFreq, Terms.TermEntry::totalTermFreq)
                    .containsExactly(tuple("3", 1, 1L));
            assertThat(index.terms()
                            .orElseThrow()
                            .terms("nothing", Optional.empty(), 10)
                            .terms())
                    .isEmpty();

            Terms.PostingsPage postings = index.terms().orElseThrow().postings("body", utf8("document"), 0, 1);
            // docFreq counts the deleted document, the postings skip it.
            assertThat(postings.docFreq()).isEqualTo(3);
            assertThat(postings.postings()).containsExactly(new Terms.Posting(0, 1, false));
            assertThat(postings.nextDoc()).hasValue(2);
        }
    }

    @Test
    void readsPositionsWithoutOffsets() throws IOException {
        try (RuntimeIndex index = open3()) {
            Terms terms = index.terms().orElseThrow();
            assertThat(terms.positions(0, "body", utf8("alpha")))
                    .hasValueSatisfying(positions -> assertThat(positions)
                            .singleElement()
                            .isEqualTo(new Documents.Position(
                                    OptionalInt.of(0), OptionalInt.empty(), OptionalInt.empty(), Optional.empty())));
            assertThat(terms.positions(1, "body", utf8("gamma"))).isEmpty();
            // 3.6 skips the deleted document.
            assertThat(terms.positions(1, "body", utf8("alpha"))).isEmpty();
            // Docs only: the document contains it, without positions.
            assertThat(terms.positions(0, "tag", utf8("odd"))).hasValue(List.of());
            assertThatThrownBy(() -> terms.positions(99, "body", utf8("alpha")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Test
    void searches() throws Exception {
        try (RuntimeIndex index = open(DirectoryKind.NIO)) {
            Search.ParsedQuery query = index.search().orElseThrow().parse("number AND (1 OR 3)", BODY, standard());
            Search.SearchResults results =
                    index.search().orElseThrow().search(query, 0, 10, Optional.of(List.of("id")), _ -> {});
            assertThat(results.totalHits()).isEqualTo(2);
            assertThat(results.hits()).extracting(Search.Hit::docId).containsExactlyInAnyOrder(0, 2);
            assertThat(results.hits().getFirst().fields()).containsOnlyKeys("id");
            assertThatThrownBy(() -> index.search().orElseThrow().parse("body:(", BODY, standard()))
                    .isInstanceOf(Search.InvalidQueryException.class);
        }
    }

    @Test
    void matchesAQueryInTheLiveDocumentsOnly() throws Exception {
        try (RuntimeIndex index = open3()) {
            Search search = index.search().orElseThrow();
            Search.ParsedQuery query = search.parse("alpha", BODY, standard());
            List<Long> progress = new ArrayList<>();
            Search.Matches live = search.matches(query, false, progress::add);
            assertThat(live.query()).isEqualTo("body:alpha");
            assertThat(live.rewrittenQuery()).isEqualTo("body:alpha");
            // Every document has alpha; one of the 7 is deleted.
            BitSet hits = new BitSet();
            search.search(query, 0, 10, Optional.empty(), _ -> {}).hits().forEach(hit -> hits.set(hit.docId()));
            assertThat(live.docs()).isEqualTo(hits);
            assertThat(live.docs().cardinality()).isEqualTo(6);
            assertThat(progress).last().isEqualTo(7L);

            assertThat(search.matchesDeleted()).isFalse();
            assertThatThrownBy(() -> search.matches(query, true, _ -> {})).isInstanceOf(IllegalArgumentException.class);
            CancellationException stop = new CancellationException("stop");
            assertThatThrownBy(() -> search.matches(query, false, _ -> {
                        throw stop;
                    }))
                    .isSameAs(stop);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"lucene24", "lucene3"})
    void searchesATerm(String name) throws Exception {
        try (RuntimeIndex index = name.equals("lucene3") ? open3() : open(DirectoryKind.FS)) {
            for (Terms.TermEntry term : index.terms()
                    .orElseThrow()
                    .terms("body", Optional.empty(), 100)
                    .terms()) {
                searchesTheTerm(index, "body", term.term());
            }

            Search search = index.search().orElseThrow();
            Search.ParsedQuery nothing = search.term("body", utf8("nothing"));
            assertThat(nothing.toString()).isEqualTo("body:nothing");
            assertThat(search.search(nothing, 0, 10, Optional.empty(), _ -> {}).totalHits())
                    .isZero();
            assertThat(search.matches(nothing, false, _ -> {}).docs().isEmpty()).isTrue();
        }
    }

    /**
     * The hits of a term query are the live postings of the term, and so are its matches; a hit is
     * explained with its score.
     */
    private static void searchesTheTerm(RuntimeIndex index, String field, byte[] term) throws IOException {
        Search search = index.search().orElseThrow();
        Search.ParsedQuery query = search.term(field, term);
        List<Integer> live = index.terms().orElseThrow().postings(field, term, 0, index.maxDoc()).postings().stream()
                .filter(posting -> !posting.deleted())
                .map(Terms.Posting::docId)
                .toList();
        Search.SearchResults results = search.search(query, 0, index.maxDoc(), Optional.empty(), _ -> {});
        assertThat(results.totalHits()).isEqualTo(live.size());
        assertThat(results.hits()).extracting(Search.Hit::docId).containsExactlyInAnyOrderElementsOf(live);
        assertThat(search.matches(query, false, _ -> {}).docs().stream().boxed().toList())
                .containsExactlyElementsOf(live);
        if (!live.isEmpty()) {
            Search.Hit hit = results.hits().getFirst();
            Search.Explanation explanation = search.explain(query, hit.docId(), _ -> {});
            assertThat(explanation.match()).isTrue();
            assertThat(explanation.value().floatValue()).isEqualTo(hit.score());
        }
    }

    @Test
    void explainsAQuery() throws Exception {
        try (RuntimeIndex index = open3()) {
            Search search = index.search().orElseThrow();
            Search.ParsedQuery alpha = search.parse("alpha", BODY, standard());
            Search.Hit hit =
                    search.search(alpha, 0, 1, Optional.empty(), _ -> {}).hits().getFirst();
            List<Long> progress = new ArrayList<>();
            Search.Explanation explanation = search.explain(alpha, hit.docId(), progress::add);
            assertThat(explanation.match()).isTrue();
            assertThat(explanation.value().floatValue()).isEqualTo(hit.score());
            assertThat(explanation.details()).isNotEmpty();
            assertThat(progress).last().isEqualTo((long) index.maxDoc());

            // Every document has alpha: the one without a match is deleted.
            int deleted = search.matches(alpha, false, _ -> {}).docs().nextClearBit(0);
            assertThat(deleted).isLessThan(index.maxDoc());
            assertThat(search.explain(alpha, deleted, _ -> {}))
                    .isEqualTo(new Search.Explanation(false, 0.0f, "Document " + deleted + " is deleted", List.of()));

            Search.ParsedQuery beta = search.parse("beta", BODY, standard());
            BitSet withoutBeta = search.matches(beta, false, _ -> {}).docs();
            withoutBeta.flip(0, index.maxDoc());
            withoutBeta.clear(deleted);
            assertThat(search.explain(beta, withoutBeta.nextSetBit(0), _ -> {}).match())
                    .isFalse();

            CancellationException stop = new CancellationException("stop");
            assertThatThrownBy(() -> search.explain(alpha, hit.docId(), _ -> {
                        throw stop;
                    }))
                    .isSameAs(stop);
        }
    }

    private static Search.AnalyzerSpec standard() {
        return named("StandardAnalyzer");
    }

    private static Search.AnalyzerSpec named(String name) {
        return new Search.AnalyzerSpec.Named(name, Optional.empty(), OptionalInt.empty());
    }

    @Test
    void readsTheStatsOfThe3xIndex() throws IOException {
        try (RuntimeIndex index = open3()) {
            RuntimeIndex.Stats stats = index.stats();
            assertThat(stats.numDocs()).isEqualTo(6);
            assertThat(stats.maxDoc()).isEqualTo(7);
            RuntimeIndex.IndexStats only = stats.indexes().getFirst();
            assertThat(only.segmentsFile()).isEqualTo("segments_2");
            assertThat(only.generation()).isEqualTo(2);
            assertThat(only.segmentVersions()).containsExactly("3.6.2", "3.6.2");
            assertSegments(only);
            assertThat(only.oldestMajor()).isEqualTo(3);
            assertThat(only.userData()).containsEntry("commit", "second");
            assertThat(stats.fields())
                    .extracting(RuntimeIndex.Field::name)
                    .containsExactly("id", "body", "tag", "price", "payload");
            RuntimeIndex.Field tag = stats.fields().get(2);
            assertThat(tag.indexOptions()).isEqualTo(RuntimeIndex.IndexOptions.DOCS);
            assertThat(tag.hasNorms()).isFalse();
            Documents.IndexDocument doc = index.documents().orElseThrow().document(4);
            assertThat(doc.fields().get(0).stored()).containsExactly(new RuntimeIndex.StoredValue.Text("5"));
            assertThat(doc.fields().get(3).stored()).containsExactly(new RuntimeIndex.StoredValue.Numeric(50));
            assertThat(index.documents().orElseThrow().document(1).deleted()).isTrue();
        }
    }

    @Test
    void opensSeveralDirectoriesAsOne() throws IOException {
        try (RuntimeIndex index = new Lucene3Runtime().open(List.of(lucene24(), lucene3()), DirectoryKind.NIO)) {
            RuntimeIndex.Stats stats = index.stats();
            assertThat(stats.maxDoc()).isEqualTo(10);
            assertThat(stats.numDocs()).isEqualTo(8);
            assertThat(stats.indexes())
                    .extracting(RuntimeIndex.IndexStats::path, RuntimeIndex.IndexStats::docBase)
                    .containsExactly(tuple(lucene24(), 0), tuple(lucene3(), 3));
            assertThat(stats.fields())
                    .extracting(RuntimeIndex.Field::name)
                    .containsExactlyInAnyOrder("id", "body", "raw", "tag", "price", "payload");
            assertThat(index.documents()
                            .orElseThrow()
                            .document(3)
                            .fields()
                            .getFirst()
                            .stored())
                    .containsExactly(new RuntimeIndex.StoredValue.Text("1"));
            assertThat(index.terms()
                            .orElseThrow()
                            .terms("id", Optional.empty(), 20)
                            .terms())
                    .extracting(term -> text(term.term()))
                    .containsExactly("1", "2", "3", "4", "5", "6", "7");
        }
    }

    @Test
    void skipsADirectoryThatFails(@TempDir Path empty) throws IOException {
        try (RuntimeIndex index = new Lucene3Runtime().open(List.of(empty, lucene24()), DirectoryKind.FS)) {
            assertThat(index.directories()).containsExactly(lucene24());
            assertThat(index.skipped()).singleElement().satisfies(skipped -> {
                assertThat(skipped.path()).isEqualTo(empty);
                assertThat(skipped.message()).isNotBlank();
            });
            assertThat(index.maxDoc()).isEqualTo(3);
        }
        assertThatThrownBy(() -> new Lucene3Runtime().open(List.of(empty, empty.resolve("missing")), DirectoryKind.FS))
                .isInstanceOf(IOException.class)
                .hasMessageStartingWith(empty.toString());
    }

    @Test
    void readsTheCommits() throws IOException {
        Commits commits = new Lucene3Runtime().commits().orElseThrow();
        Commits.CommitList list = commits.commits(lucene3(), DirectoryKind.FS);
        assertThat(list.unreadable()).isEmpty();
        assertThat(list.commits()).extracting(Commits.Commit::segmentsFile).containsExactly("segments_2", "segments_1");
        Commits.Commit second = list.commits().getFirst();
        assertThat(second.segmentCount()).isEqualTo(2);
        assertThat(second.maxDoc()).isEqualTo(7);
        assertThat(second.delCount()).isEqualTo(1);
        assertThat(second.luceneVersion()).isEqualTo("3.6.2");
        assertThat(second.userData()).containsEntry("commit", "second");
        assertThat(list.commits().get(1).userData()).containsEntry("commit", "first");
        assertThat(list.commits().get(1).maxDoc()).isEqualTo(4);

        Commits.CommitDetails details =
                commits.commit(lucene3(), DirectoryKind.NIO, 2).orElseThrow();
        assertThat(details.segments()).hasSize(2);
        Commits.Segment plain = details.segments().get(0);
        assertThat(plain.name()).isEqualTo("_0");
        assertThat(plain.compound()).isFalse();
        assertThat(plain.delCount()).isEqualTo(1);
        assertThat(plain.delGen()).hasValue(1);
        assertThat(plain.luceneVersion()).isEqualTo("3.6.2");
        assertThat(plain.sizeInBytes()).isPresent();
        assertThat(plain.diagnostics()).containsEntry("source", "flush");
        assertThat(plain.attributes()).containsKeys("docStoreSegment", "docStoreOffset", "hasProx");
        Commits.Segment compound = details.segments().get(1);
        assertThat(compound.compound()).isTrue();
        assertThat(compound.docBase()).isEqualTo(4);
        assertThat(compound.delGen()).isEmpty();
        assertThat(details.files())
                .extracting(Commits.CommitFile::name)
                .contains("segments_2", "_0_1.del", "_0.frq", "_1.cfs");
        assertThat(details.files())
                .filteredOn(file -> file.name().equals("_1.cfs"))
                .singleElement()
                .satisfies(file -> {
                    assertThat(file.segment()).contains("_1");
                    assertThat(file.size()).isPresent();
                });
        assertThat(commits.commit(lucene3(), DirectoryKind.FS, 99)).isEmpty();

        Commits.CommitList old = commits.commits(lucene24(), DirectoryKind.FS);
        assertThat(old.commits()).singleElement().satisfies(commit -> {
            assertThat(commit.luceneVersion()).isEqualTo("2.x");
            assertThat(commit.delCount()).isEqualTo(1);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"lucene24", "lucene3"})
    void checksACleanIndex(String name) {
        List<String> lines = new ArrayList<>();
        Check.CheckResult result = new Lucene3Runtime()
                .check()
                .orElseThrow()
                .check(testIndex(name), DirectoryKind.FS, OptionalInt.empty(), lines::add, _ -> {});
        assertThat(result.error()).isEmpty();
        assertThat(result.clean()).isTrue();
        assertThat(result.badSegments()).isZero();
        assertThat(result.segments()).allSatisfy(segment -> {
            assertThat(segment.openReaderPassed()).isTrue();
            assertThat(segment.error()).isEmpty();
        });
        assertThat(lines).anyMatch(line -> line.contains("No problems were detected"));
    }

    @Test
    void checksADamagedIndexWithoutChangingIt(@TempDir Path dir) throws Exception {
        try (Stream<Path> files = Files.list(lucene3())) {
            for (Path file : files.toList()) {
                Files.copy(file, dir.resolve(file.getFileName()));
            }
        }
        Path frq = dir.resolve("_0.frq");
        byte[] garbage = new byte[(int) Files.size(frq)];
        Arrays.fill(garbage, (byte) 0xff);
        Files.write(frq, garbage);
        Map<String, String> before = hashes(dir);

        Check.CheckResult result = new Lucene3Runtime()
                .check()
                .orElseThrow()
                .check(dir, DirectoryKind.NIO, OptionalInt.empty(), _ -> {}, _ -> {});
        assertThat(result.clean()).isFalse();
        assertThat(result.badSegments()).isEqualTo(1);
        assertThat(result.lostDocs()).isEqualTo(3);
        assertThat(result.segments().get(0).error()).isPresent();
        assertThat(result.segments().get(1).error()).isEmpty();
        assertThat(hashes(dir)).isEqualTo(before);
        assertThat(dir.resolve("write.lock")).doesNotExist();
    }

    @Test
    void stopsACheckWhenProgressThrows() {
        CancellationException stop = new CancellationException("stop");
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> new Lucene3Runtime()
                        .check()
                        .orElseThrow()
                        .check(lucene3(), DirectoryKind.FS, OptionalInt.empty(), _ -> {}, _ -> {
                            if (calls.incrementAndGet() > 3) {
                                throw stop;
                            }
                        }))
                .isSameAs(stop);
    }

    /**
     * Rebuilds every live document in every indexed field, and checks it against what the term
     * vectors, postings and positions say; then the deleted document, which 3.6's postings skip.
     */
    @ParameterizedTest
    @ValueSource(strings = {"lucene24", "lucene3"})
    void rebuildsADocument(String name) throws IOException {
        try (RuntimeIndex index = new Lucene3Runtime().open(List.of(testIndex(name)), DirectoryKind.FS)) {
            Rebuild rebuild = index.rebuild().orElseThrow();
            List<RuntimeIndex.Field> fields = index.fields().stream()
                    .filter(field -> field.indexOptions() != RuntimeIndex.IndexOptions.NONE)
                    .toList();
            List<String> names = fields.stream().map(RuntimeIndex.Field::name).toList();
            for (int doc = 0; doc < index.maxDoc(); doc++) {
                if (index.documents().orElseThrow().document(doc).deleted()) {
                    continue;
                }
                List<Long> progress = new ArrayList<>();
                List<Rebuild.RebuiltField> rebuilt = rebuild.rebuild(doc, names, progress::add);
                assertThat(rebuilt).extracting(Rebuild.RebuiltField::field).containsExactlyElementsOf(names);
                // 3.6 doesn't know how many terms a field has.
                boolean postings = rebuilt.stream().anyMatch(one -> one.source() == Rebuild.Source.POSTINGS);
                assertThat(rebuild.count(doc, names)).isEqualTo(postings ? OptionalLong.empty() : OptionalLong.of(0));
                assertThat(progress).isNotEmpty();
                for (int i = 0; i < fields.size(); i++) {
                    RuntimeIndex.Field field = fields.get(i);
                    Rebuild.RebuiltField one = rebuilt.get(i);
                    List<Documents.VectorTerm> expected = one.source() == Rebuild.Source.TERM_VECTOR
                            ? index.documents().orElseThrow().termVector(doc, field.name())
                            : postingsOf(index, doc, field);
                    assertThat(describe(one.terms()))
                            .as("%s of document %d", field.name(), doc)
                            .isEqualTo(describe(expected));
                    assertThat(one.terms())
                            .as("%s of document %d", field.name(), doc)
                            .isNotEmpty();
                    assertThat(one.points()).isEmpty();
                }
            }
            // Document 1 is deleted, and rebuilt too: its body from the term vector, its id from the raw postings.
            List<Rebuild.RebuiltField> deleted = rebuild.rebuild(1, List.of("body", "id"), _ -> {});
            assertThat(deleted)
                    .extracting(Rebuild.RebuiltField::source)
                    .containsExactly(Rebuild.Source.TERM_VECTOR, Rebuild.Source.POSTINGS);
            assertThat(deleted.get(1).terms())
                    .extracting(term -> text(term.term()))
                    .containsExactly("2");
            CancellationException stop = new CancellationException("stop");
            assertThatThrownBy(() -> rebuild.rebuild(0, names, _ -> {
                        throw stop;
                    }))
                    .isSameAs(stop);
        }
    }

    /** The 3.x index: payloads aren't in 3.6's term vectors, so payload comes from the postings. */
    @Test
    void rebuildsPayloadsFromThePostings() throws IOException {
        try (RuntimeIndex index = open3()) {
            Rebuild rebuild = index.rebuild().orElseThrow();
            List<String> names = List.of("body", "id", "tag", "payload");
            assertThat(rebuild.rebuild(0, names, _ -> {}))
                    .extracting(Rebuild.RebuiltField::source, Rebuild.RebuiltField::indexOptions)
                    .containsExactly(
                            tuple(Rebuild.Source.TERM_VECTOR, RuntimeIndex.IndexOptions.DOCS_AND_FREQS_AND_POSITIONS),
                            tuple(Rebuild.Source.POSTINGS, RuntimeIndex.IndexOptions.DOCS_AND_FREQS_AND_POSITIONS),
                            tuple(Rebuild.Source.POSTINGS, RuntimeIndex.IndexOptions.DOCS),
                            tuple(Rebuild.Source.POSTINGS, RuntimeIndex.IndexOptions.DOCS_AND_FREQS_AND_POSITIONS));
            assertThat(rebuild.count(0, names)).isEmpty();
            assertThat(rebuild.count(0, List.of("body"))).hasValue(0);

            // The deleted document 1, id 2.
            List<Long> progress = new ArrayList<>();
            List<Rebuild.RebuiltField> deleted = rebuild.rebuild(1, names, progress::add);
            assertThat(deleted.get(0).terms())
                    .extracting(term -> text(term.term()))
                    .contains("2", "alpha");
            assertThat(deleted.get(1).terms()).singleElement().satisfies(id -> {
                assertThat(text(id.term())).isEqualTo("2");
                assertThat(id.freq()).isEqualTo(1);
                // Not analyzed: one position, without a payload.
                assertThat(id.positions())
                        .singleElement()
                        .isEqualTo(new Documents.Position(
                                OptionalInt.of(0), OptionalInt.empty(), OptionalInt.empty(), Optional.empty()));
            });
            assertThat(deleted.get(2).terms())
                    .extracting(term -> text(term.term()))
                    .containsExactly("even");
            assertThat(deleted.get(3).terms())
                    .extracting(term -> text(term.term()))
                    .containsExactly("2", "alpha", "document");
            assertThat(deleted.get(3).terms())
                    .flatExtracting(Documents.VectorTerm::positions)
                    .extracting(
                            position -> position.position().getAsInt(),
                            position -> position.payload()
                                    .map(Lucene3IndexTest::text)
                                    .orElseThrow())
                    .containsExactly(tuple(2, "p2"), tuple(0, "p2"), tuple(1, "p2"));
            // The terms of id, tag and payload in the segment _0 of documents 1 to 4: 4 ids, 2 tags, and
            // alpha, document and 4 numbers.
            assertThat(progress).last().isEqualTo(12L);
        }
    }

    /** The terms of {@code doc} in {@code field}, read from the postings and positions of every term. */
    private static List<Documents.VectorTerm> postingsOf(RuntimeIndex index, int doc, RuntimeIndex.Field field)
            throws IOException {
        List<Documents.VectorTerm> result = new ArrayList<>();
        boolean positions = field.indexOptions() == RuntimeIndex.IndexOptions.DOCS_AND_FREQS_AND_POSITIONS;
        Terms terms = index.terms().orElseThrow();
        Optional<byte[]> from = Optional.empty();
        do {
            Terms.TermsPage page = terms.terms(field.name(), from, 100);
            for (Terms.TermEntry entry : page.terms()) {
                List<Terms.Posting> postings =
                        terms.postings(field.name(), entry.term(), doc, 1).postings();
                if (!postings.isEmpty() && postings.getFirst().docId() == doc) {
                    result.add(new Documents.VectorTerm(
                            entry.term(),
                            postings.getFirst().freq(),
                            positions
                                    ? terms.positions(doc, field.name(), entry.term())
                                            .orElseThrow()
                                    : List.of()));
                }
            }
            from = page.next();
        } while (from.isPresent());
        return result;
    }

    private static List<String> describe(List<Documents.VectorTerm> terms) {
        return terms.stream()
                .map(term -> HexFormat.of().formatHex(term.term()) + " " + term.freq() + " "
                        + term.positions().stream()
                                .map(position -> position.position() + "/" + position.startOffset() + "/"
                                        + position.endOffset() + "/"
                                        + position.payload().map(HexFormat.of()::formatHex))
                                .toList())
                .toList();
    }

    @Test
    void findsTheTopTerms() throws IOException {
        try (RuntimeIndex index = open3()) {
            List<Long> progress = new ArrayList<>();
            Terms.FieldTerms terms = index.terms().orElseThrow().topTerms("body", 3, progress::add);
            // alpha, beta, document, gamma and 1 to 7, the deleted document's too.
            assertThat(terms.uniqueTerms()).isEqualTo(11);
            assertThat(progress).last().isEqualTo(11L);
            // docFreq counts the deleted document, totalTermFreq the live ones.
            assertThat(terms.topTerms())
                    .extracting(term -> text(term.term()), Terms.TermEntry::docFreq, Terms.TermEntry::totalTermFreq)
                    .containsExactly(tuple("alpha", 7, 6L), tuple("document", 7, 6L), tuple("beta", 4, 4L));
            assertThat(index.terms().orElseThrow().topTerms("tag", 0, _ -> {}))
                    .isEqualTo(new Terms.FieldTerms(2, List.of()));
        }
    }

    @Test
    void dropsTheAutoStopWords() throws Exception {
        try (RuntimeIndex index = open3()) {
            Search search = index.search().orElseThrow();
            // Case-sensitive, after the analyzer lowercased Alpha, in body only.
            assertThat(search.parse(
                            "Alpha beta id:alpha",
                            BODY,
                            new Search.AnalyzerSpec.AutoStopWords(standard(), Map.of("body", List.of("alpha")))))
                    .hasToString("body:beta id:alpha");
        }
    }

    @Test
    void parsesWithTheAnalyzersOf36() throws Exception {
        try (RuntimeIndex index = open3()) {
            Search search = index.search().orElseThrow();
            assertThat(search.analyzers()).contains("StandardAnalyzer", "WhitespaceAnalyzer", "KeywordAnalyzer");
            assertThat(search.parse("Alpha the", BODY, standard())).hasToString("body:alpha");
            assertThat(search.parse("Alpha the", BODY, named("WhitespaceAnalyzer")))
                    .hasToString("body:Alpha body:the");
            assertThat(search.parse(
                            "Alpha beta",
                            BODY,
                            new Search.AnalyzerSpec.Named(
                                    "StandardAnalyzer", Optional.of(List.of("alpha")), OptionalInt.empty())))
                    .hasToString("body:beta");
            assertThat(search.parse(
                            "\"Alpha beta\" id:\"A b\"",
                            BODY,
                            new Search.AnalyzerSpec.PerField(
                                    new Search.AnalyzerSpec.LimitTokenCount(named("SimpleAnalyzer"), 1),
                                    Map.of("id", named("KeywordAnalyzer")))))
                    .hasToString("body:alpha id:A b");
            assertThatThrownBy(() -> search.parse("alpha", BODY, named("EnglishAnalyzer")))
                    .isInstanceOf(Search.UnsupportedAnalyzerException.class)
                    .hasMessageContaining("EnglishAnalyzer")
                    .hasMessageContaining("ClassicAnalyzer, KeywordAnalyzer");
            assertThatThrownBy(() -> search.parse(
                            "alpha",
                            BODY,
                            new Search.AnalyzerSpec.Named(
                                    "WhitespaceAnalyzer", Optional.of(List.of("a")), OptionalInt.empty())))
                    .isInstanceOf(Search.UnsupportedAnalyzerException.class)
                    .hasMessageContaining("takes no stop words");
        }
    }

    @Test
    void reopensAChangedIndex(@TempDir Path dir) throws IOException {
        String name = "lucene3";
        String newest = "segments_2";
        Path index0 = testIndex(name);
        copyWithout(index0, dir, newest);
        RuntimeIndex index = new Lucene3Runtime().open(List.of(dir), DirectoryKind.FS);
        try (index;
                RuntimeIndex latest = new Lucene3Runtime().open(List.of(index0), DirectoryKind.FS)) {
            String older = index.stats().indexes().getFirst().segmentsFile();
            assertThat(older).isNotEqualTo(newest);
            assertThat(index.reopen()).isEmpty();

            Files.copy(index0.resolve(newest), dir.resolve(newest));
            try (RuntimeIndex reopened = index.reopen().orElseThrow()) {
                assertThat(reopened.directories()).containsExactly(dir);
                assertThat(reopened.stats().indexes().getFirst().segmentsFile()).isEqualTo(newest);
                assertThat(reopened.maxDoc()).isEqualTo(latest.maxDoc()).isGreaterThan(index.maxDoc());
                assertThat(reopened.stats().numDocs()).isEqualTo(latest.stats().numDocs());
                assertThat(reopened.fields()).isEqualTo(latest.fields());
                // The old index stays usable until it is closed, and can't reopen again.
                assertThat(index.stats().indexes().getFirst().segmentsFile()).isEqualTo(older);
                assertThat(index.documents().orElseThrow().document(0).fields()).isNotEmpty();
                assertThatThrownBy(index::reopen).isInstanceOf(IllegalStateException.class);
                index.close();
                // The first segment is in both commits, and its reader is shared.
                assertThat(reopened.documents().orElseThrow().document(0).fields())
                        .isNotEmpty();
                assertThat(reopened.terms()
                                .orElseThrow()
                                .terms("id", Optional.empty(), 20)
                                .terms())
                        .isNotEmpty();
            }
        }
        assertThat(dir.resolve("write.lock")).doesNotExist();
    }

    /** Of several directories, only one changed: the other one's reader is shared, and stays open. */
    @Test
    void reopensOneOfSeveralDirectories(@TempDir Path dir) throws IOException {
        Path unchanged = testIndex("lucene24");
        copyWithout(testIndex("lucene3"), dir, "segments_2");
        RuntimeIndex index = new Lucene3Runtime().open(List.of(unchanged, dir), DirectoryKind.FS);
        try (index;
                RuntimeIndex latest =
                        new Lucene3Runtime().open(List.of(unchanged, testIndex("lucene3")), DirectoryKind.FS)) {
            assertThat(index.reopen()).isEmpty();
            Files.copy(testIndex("lucene3").resolve("segments_2"), dir.resolve("segments_2"));
            try (RuntimeIndex reopened = index.reopen().orElseThrow()) {
                index.close();
                assertThat(reopened.directories()).containsExactly(unchanged, dir);
                assertThat(reopened.stats().indexes().get(1).segmentsFile()).isEqualTo("segments_2");
                assertThat(reopened.maxDoc()).isEqualTo(latest.maxDoc());
                assertThat(reopened.fields()).isEqualTo(latest.fields());
                // The unchanged directory's reader, still open.
                assertThat(reopened.documents().orElseThrow().document(0).fields())
                        .isNotEmpty();
                assertThat(reopened.terms()
                                .orElseThrow()
                                .terms("id", Optional.empty(), 20)
                                .terms())
                        .extracting(term -> text(term.term()), Terms.TermEntry::docFreq)
                        .isEqualTo(latest.terms().orElseThrow().terms("id", Optional.empty(), 20).terms().stream()
                                .map(term -> tuple(text(term.term()), term.docFreq()))
                                .toList());
            }
        }
    }

    /** Copies {@code index} into {@code dir} without the commit {@code segmentsFile}, nor {@code segments.gen}. */
    private static void copyWithout(Path index, Path dir, String segmentsFile) throws IOException {
        try (Stream<Path> files = Files.list(index)) {
            for (Path file : files.toList()) {
                String fileName = file.getFileName().toString();
                if (!fileName.equals(segmentsFile) && !fileName.equals("segments.gen")) {
                    Files.copy(file, dir.resolve(fileName));
                }
            }
        }
    }

    /** Hard rule 1: reading changes nothing in the index directory, and leaves no lock. */
    @ParameterizedTest
    @EnumSource(DirectoryKind.class)
    void changesNothing(DirectoryKind directoryKind) throws Exception {
        for (Path dir : List.of(lucene24(), lucene3())) {
            Map<String, String> before = hashes(dir);
            LuceneRuntime runtime = new Lucene3Runtime();
            try (RuntimeIndex index = runtime.open(List.of(dir), directoryKind)) {
                index.stats();
                index.documents().orElseThrow().document(2);
                index.terms().orElseThrow().terms("body", Optional.empty(), 100);
                index.terms().orElseThrow().topTerms("body", 10, _ -> {});
            }
            runtime.commits().orElseThrow().commits(dir, directoryKind);
            runtime.commits().orElseThrow().commit(dir, directoryKind, 1);
            runtime.check().orElseThrow().check(dir, directoryKind, OptionalInt.empty(), _ -> {}, _ -> {});
            assertThat(hashes(dir)).isEqualTo(before);
            assertThat(dir.resolve("write.lock")).doesNotExist();
        }
    }

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

    /** The reader's segments follow each other and add up to the directory's counts (ADR 0051). */
    private static void assertSegments(RuntimeIndex.IndexStats stats) {
        assertThat(stats.segments()).hasSize(stats.segmentVersions().size());
        int docBase = 0;
        for (RuntimeIndex.Segment segment : stats.segments()) {
            assertThat(segment.name()).matches("_[0-9a-z]+");
            assertThat(segment.docBase()).isEqualTo(docBase);
            docBase += segment.maxDoc();
        }
        assertThat(docBase).isEqualTo(stats.maxDoc());
        assertThat(stats.segments().stream()
                        .mapToInt(RuntimeIndex.Segment::numDocs)
                        .sum())
                .isEqualTo(stats.numDocs());
    }
}
