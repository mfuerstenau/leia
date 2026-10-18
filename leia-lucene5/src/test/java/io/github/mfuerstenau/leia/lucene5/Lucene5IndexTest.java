package io.github.mfuerstenau.leia.lucene5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.github.mfuerstenau.leia.runtime.Check;
import io.github.mfuerstenau.leia.runtime.Commits;
import io.github.mfuerstenau.leia.runtime.DirectoryKind;
import io.github.mfuerstenau.leia.runtime.Documents;
import io.github.mfuerstenau.leia.runtime.LuceneRuntime;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Reads the Lucene 4.x and 5.x indexes that the build writes (build.gradle.kts) with plain Lucene 5.5,
 * through the runtime interface. Both have the same documents; 5.x adds a doc values update.
 */
class Lucene5IndexTest {

    private static final Search.ParserOptions BODY = new Search.ParserOptions("body", false, false, 0, 0, true, false);

    private static Path testIndex(String name) {
        String dir = System.getProperty("leia.test.index." + name);
        if (dir == null) {
            throw new IllegalStateException("System property leia.test.index." + name + " is not set");
        }
        return Path.of(dir).toAbsolutePath();
    }

    private static RuntimeIndex open(String name) throws IOException {
        return new Lucene5Runtime().open(List.of(testIndex(name)), DirectoryKind.FS);
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static Documents.DocumentField field(Documents.IndexDocument doc, String name) {
        return doc.fields().stream()
                .filter(field -> field.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void isFoundAsARuntime() {
        assertThat(ServiceLoader.load(LuceneRuntime.class))
                .singleElement()
                .isInstanceOfSatisfying(Lucene5Runtime.class, runtime -> {
                    assertThat(runtime.apiVersion()).isEqualTo(LuceneRuntime.API_VERSION);
                    assertThat(runtime.indexMajors()).containsExactlyInAnyOrder(4, 5);
                    assertThat(runtime.luceneVersion()).isEqualTo("5.5.5");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"lucene4", "lucene5"})
    void readsTheStatsAndFields(String name) throws IOException {
        try (RuntimeIndex index = open(name)) {
            RuntimeIndex.Stats stats = index.stats();
            assertThat(stats.numDocs()).isEqualTo(6);
            assertThat(stats.maxDoc()).isEqualTo(7);
            RuntimeIndex.IndexStats only = stats.indexes().getFirst();
            assertThat(only.segmentsFile()).isEqualTo("segments_2");
            assertThat(only.generation()).isEqualTo(2);
            assertThat(only.userData()).containsEntry("commit", "second");
            String version = name.equals("lucene4") ? "4.10.4" : "5.5.5";
            assertThat(only.segmentVersions()).containsExactly(version, version);
            assertSegments(only);
            assertThat(only.oldestMajor()).isEqualTo(name.equals("lucene4") ? 4 : 5);
            // Recorded since 5.3.
            assertThat(only.luceneVersion())
                    .isEqualTo(name.equals("lucene4") ? Optional.empty() : Optional.of("5.5.5"));
            assertThat(only.commitVersion("?")).isEqualTo(version);

            assertThat(stats.fields())
                    .extracting(RuntimeIndex.Field::name)
                    .containsExactly(
                            "id",
                            "body",
                            "tag",
                            "price",
                            "time",
                            "blob",
                            "dv_num",
                            "dv_bin",
                            "dv_sorted",
                            "dv_set",
                            "dv_sorted_num");
            assertThat(stats.fields().get(1))
                    .isEqualTo(new RuntimeIndex.Field(
                            "body",
                            1,
                            RuntimeIndex.IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS,
                            true,
                            true,
                            true,
                            RuntimeIndex.DocValuesType.NONE,
                            0,
                            0,
                            // alpha, document and 1 to 7 in each document, beta in 4, gamma twice in 1.
                            Optional.of(new RuntimeIndex.TermStats(7, 26, 27))));
            RuntimeIndex.Field tag = stats.fields().get(2);
            assertThat(tag.indexOptions()).isEqualTo(RuntimeIndex.IndexOptions.DOCS);
            assertThat(tag.hasNorms()).isFalse();
            // Without frequencies, sumTotalTermFreq is sumDocFreq.
            assertThat(tag.terms()).contains(new RuntimeIndex.TermStats(7, 7, 7));
            assertThat(stats.fields().get(5).indexOptions()).isEqualTo(RuntimeIndex.IndexOptions.NONE);
            assertThat(stats.fields().get(5).terms()).isEmpty();
            assertThat(stats.fields().subList(6, 11))
                    .extracting(RuntimeIndex.Field::docValuesType)
                    .containsExactly(
                            RuntimeIndex.DocValuesType.NUMERIC,
                            RuntimeIndex.DocValuesType.BINARY,
                            RuntimeIndex.DocValuesType.SORTED,
                            RuntimeIndex.DocValuesType.SORTED_SET,
                            RuntimeIndex.DocValuesType.SORTED_NUMERIC);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"lucene4", "lucene5"})
    void readsDocumentsWithDocValues(String name) throws IOException {
        try (RuntimeIndex index = open(name)) {
            Documents documents = index.documents().orElseThrow();
            Documents.IndexDocument first = documents.document(0);
            assertThat(first.deleted()).isFalse();
            assertThat(field(first, "id").stored()).containsExactly(new RuntimeIndex.StoredValue.Text("1"));
            assertThat(field(first, "price").stored()).containsExactly(new RuntimeIndex.StoredValue.Numeric(10));
            assertThat(field(first, "blob").stored())
                    .singleElement()
                    .isInstanceOfSatisfying(
                            RuntimeIndex.StoredValue.Binary.class,
                            binary -> assertThat(binary.bytes()).containsExactly(0xff, 0xfe, 1));
            assertThat(field(first, "body").norm()).isPresent();
            assertThat(field(first, "body").hasTermVector()).isTrue();
            assertThat(field(first, "tag").norm()).isEmpty();
            assertThat(field(first, "body").docValues()).isEmpty();

            assertThat(field(first, "dv_num").docValues())
                    .contains(new Documents.DocValues(RuntimeIndex.DocValuesType.NUMERIC, List.of(1L), List.of()));
            assertThat(field(first, "dv_sorted_num").docValues().orElseThrow().numbers())
                    .containsExactly(-1L, 1L);
            assertThat(field(first, "dv_bin").docValues().orElseThrow().values())
                    .extracting(Lucene5IndexTest::text)
                    .containsExactly("b1");
            assertThat(field(first, "dv_sorted").docValues().orElseThrow().values())
                    .extracting(Lucene5IndexTest::text)
                    .containsExactly("s1");
            assertThat(field(first, "dv_set").docValues().orElseThrow().values())
                    .extracting(Lucene5IndexTest::text)
                    .containsExactly("x", "y1");

            // Document 3 has no dv_num; 5.x set that of document 4 to 400.
            assertThat(field(documents.document(2), "dv_num").docValues()).isEmpty();
            assertThat(field(documents.document(3), "dv_num")
                            .docValues()
                            .orElseThrow()
                            .numbers())
                    .containsExactly(name.equals("lucene4") ? 4L : 400L);
            // In the second segment.
            assertThat(field(documents.document(6), "dv_set")
                            .docValues()
                            .orElseThrow()
                            .values())
                    .extracting(Lucene5IndexTest::text)
                    .containsExactly("x", "y7");
            assertThat(documents.document(1).deleted()).isTrue();
            assertThatThrownBy(() -> documents.document(7)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"lucene4", "lucene5"})
    void readsStoredValuesOfARange(String name) throws IOException {
        try (RuntimeIndex index = open(name)) {
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

    @ParameterizedTest
    @ValueSource(strings = {"lucene4", "lucene5"})
    void readsTermVectorsWithPayloads(String name) throws IOException {
        try (RuntimeIndex index = open(name)) {
            List<Documents.VectorTerm> vector = index.documents().orElseThrow().termVector(0, "body");
            assertThat(vector)
                    .extracting(term -> text(term.term()), Documents.VectorTerm::freq)
                    .containsExactly(
                            tuple("1", 1),
                            tuple("alpha", 1),
                            tuple("beta", 1),
                            tuple("document", 1),
                            tuple("gamma", 2));
            Documents.Position alpha = vector.get(1).positions().getFirst();
            assertThat(alpha.position()).hasValue(0);
            assertThat(alpha.startOffset()).hasValue(0);
            // The token's offsets are those of alpha|a1.
            assertThat(alpha.endOffset()).hasValue(8);
            assertThat(alpha.payload().map(Lucene5IndexTest::text)).contains("a1");
            assertThat(vector.get(4).positions())
                    .extracting(position -> position.position().getAsInt())
                    .containsExactly(4, 5);
            assertThat(vector.get(4).positions().getFirst().payload()).isEmpty();
            assertThat(index.documents().orElseThrow().termVector(0, "tag")).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"lucene4", "lucene5"})
    void readsBinaryTermsAndPostingsWithDeletedDocuments(String name) throws IOException {
        try (RuntimeIndex index = open(name)) {
            Terms terms = index.terms().orElseThrow();
            // An IntField's terms are trie-encoded: a shift byte from 0x60, then 7 bits per byte.
            Terms.TermsPage price = terms.terms("price", Optional.empty(), 2);
            assertThat(price.terms()).hasSize(2);
            assertThat(price.terms().getFirst().term()[0]).isEqualTo((byte) 0x60);
            Terms.TermsPage rest = terms.terms("price", price.next(), 1000);
            assertThat(rest.terms().getFirst().term()).isEqualTo(price.next().orElseThrow());
            assertThat(rest.next()).isEmpty();

            Terms.TermsPage ids = terms.terms("id", Optional.of(utf8("3")), 2);
            assertThat(ids.terms())
                    .extracting(term -> text(term.term()), Terms.TermEntry::docFreq, Terms.TermEntry::totalTermFreq)
                    .containsExactly(tuple("3", 1, 1L), tuple("4", 1, 1L));
            assertThat(ids.next().map(Lucene5IndexTest::text)).contains("5");
            assertThat(terms.terms("id", Optional.of(utf8("9")), 2).terms()).isEmpty();
            assertThat(terms.terms("nothing", Optional.empty(), 2).terms()).isEmpty();

            Terms.PostingsPage alpha = terms.postings("body", utf8("alpha"), 0, 3);
            assertThat(alpha.docFreq()).isEqualTo(7);
            assertThat(alpha.totalTermFreq()).isEqualTo(7);
            assertThat(alpha.postings())
                    .containsExactly(
                            new Terms.Posting(0, 1, false),
                            new Terms.Posting(1, 1, true),
                            new Terms.Posting(2, 1, false));
            assertThat(alpha.nextDoc()).hasValue(3);
            assertThat(terms.postings("body", utf8("alpha"), 6, 3).postings())
                    .containsExactly(new Terms.Posting(6, 1, false));
            // Docs only: totalTermFreq is docFreq, as in Lucene 10.
            Terms.PostingsPage odd = terms.postings("tag", utf8("odd"), 0, 10);
            assertThat(odd.totalTermFreq()).isEqualTo(odd.docFreq()).isEqualTo(4);
            assertThat(terms.postings("body", utf8("missing"), 0, 10).docFreq()).isZero();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"lucene4", "lucene5"})
    void readsPositionsWithOffsetsAndPayloads(String name) throws IOException {
        try (RuntimeIndex index = open(name)) {
            Terms terms = index.terms().orElseThrow();
            assertThat(terms.positions(0, "body", utf8("gamma")).orElseThrow())
                    .extracting(
                            position -> position.position().getAsInt(),
                            position -> position.startOffset().getAsInt(),
                            position -> position.endOffset().getAsInt())
                    .containsExactly(tuple(4, 25, 30), tuple(5, 31, 36));
            assertThat(terms.positions(4, "body", utf8("alpha")).orElseThrow())
                    .singleElement()
                    .satisfies(alpha -> assertThat(alpha.payload().map(Lucene5IndexTest::text))
                            .contains("a5"));
            // Deleted documents have their positions too.
            assertThat(terms.positions(1, "body", utf8("alpha"))).isPresent();
            assertThat(terms.positions(1, "body", utf8("gamma"))).isEmpty();
            assertThat(terms.positions(0, "tag", utf8("odd"))).hasValue(List.of());
            assertThat(terms.positions(0, "nothing", utf8("odd"))).isEmpty();
            assertThatThrownBy(() -> terms.positions(7, "body", utf8("alpha")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"lucene4", "lucene5"})
    void findsTheTopTerms(String name) throws IOException {
        try (RuntimeIndex index = open(name)) {
            List<Long> progress = new ArrayList<>();
            Terms.FieldTerms terms = index.terms().orElseThrow().topTerms("body", 3, progress::add);
            // alpha, beta, document, gamma and 1 to 7, the deleted document's too.
            assertThat(terms.uniqueTerms()).isEqualTo(11);
            assertThat(progress).last().isEqualTo(11L);
            assertThat(terms.topTerms())
                    .extracting(term -> text(term.term()), Terms.TermEntry::docFreq, Terms.TermEntry::totalTermFreq)
                    .containsExactly(tuple("alpha", 7, 7L), tuple("document", 7, 7L), tuple("beta", 4, 4L));
            assertThat(index.terms().orElseThrow().topTerms("tag", 0, _ -> {}))
                    .isEqualTo(new Terms.FieldTerms(2, List.of()));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"lucene4", "lucene5"})
    void searches(String name) throws Exception {
        try (RuntimeIndex index = open(name)) {
            Search search = index.search().orElseThrow();
            Search.ParsedQuery query = search.parse("documents AND (beta OR gamma)", BODY, named("EnglishAnalyzer"));
            assertThat(query).hasToString("+body:document +(body:beta body:gamma)");
            Search.SearchResults results = search.search(query, 0, 10, Optional.of(List.of("id")), _ -> {});
            assertThat(results.totalHits()).isEqualTo(4);
            assertThat(results.hits().getFirst().docId()).isZero();
            assertThat(results.hits()).extracting(Search.Hit::docId).containsExactlyInAnyOrder(0, 2, 4, 6);
            assertThat(results.hits().getFirst().fields()).containsOnlyKeys("id");
            assertThatThrownBy(() -> search.parse("body:(", BODY, named("StandardAnalyzer")))
                    .isInstanceOf(Search.InvalidQueryException.class);
        }
    }

    @Test
    void matchesAQuery() throws Exception {
        try (RuntimeIndex index = open("lucene5")) {
            Search search = index.search().orElseThrow();
            Search.ParsedQuery query = search.parse("alpha", BODY, named("StandardAnalyzer"));
            List<Long> progress = new ArrayList<>();
            Search.Matches live = search.matches(query, false, progress::add);
            assertThat(live.query()).isEqualTo("body:alpha");
            assertThat(live.rewrittenQuery()).isEqualTo("body:alpha");
            // Every document has alpha; document 1 is deleted.
            BitSet all = new BitSet();
            all.set(0, index.maxDoc());
            BitSet undeleted = (BitSet) all.clone();
            undeleted.clear(1);
            assertThat(live.docs()).isEqualTo(undeleted);
            assertThat(progress).last().isEqualTo((long) index.maxDoc());
            assertThat(search.matchesDeleted()).isTrue();
            assertThat(search.matches(query, true, _ -> {}).docs()).isEqualTo(all);

            CancellationException stop = new CancellationException("stop");
            assertThatThrownBy(() -> search.matches(query, true, _ -> {
                        throw stop;
                    }))
                    .isSameAs(stop);
        }
    }

    private static Search.AnalyzerSpec named(String name) {
        return new Search.AnalyzerSpec.Named(name, Optional.empty(), OptionalInt.empty());
    }

    @Test
    void parsesWithTheAnalyzersOf55() throws Exception {
        try (RuntimeIndex index = open("lucene5")) {
            Search search = index.search().orElseThrow();
            assertThat(search.analyzers())
                    .contains("StandardAnalyzer", "EnglishAnalyzer", "GermanAnalyzer", "UAX29URLEmailAnalyzer")
                    .hasSize(41);
            assertThat(search.parse("Alpha the", BODY, named("StandardAnalyzer")))
                    .hasToString("body:alpha");
            assertThat(search.parse("Häuser", BODY, named("GermanAnalyzer"))).hasToString("body:haus");
            assertThat(search.parse(
                            "Alpha beta",
                            BODY,
                            new Search.AnalyzerSpec.Named(
                                    "EnglishAnalyzer", Optional.of(List.of("alpha")), OptionalInt.empty())))
                    .hasToString("body:beta");
            assertThat(search.parse(
                            "abcdef",
                            BODY,
                            new Search.AnalyzerSpec.Named("StandardAnalyzer", Optional.empty(), OptionalInt.of(3))))
                    .hasToString("body:abc body:def");
            assertThat(search.parse(
                            "\"Alpha beta\" id:\"A b\"",
                            BODY,
                            new Search.AnalyzerSpec.PerField(
                                    new Search.AnalyzerSpec.LimitTokenCount(named("SimpleAnalyzer"), 1),
                                    Map.of("id", named("KeywordAnalyzer")))))
                    .hasToString("body:alpha id:A b");
            assertThatThrownBy(() -> search.parse("alpha", BODY, named("SmartChineseAnalyzer")))
                    .isInstanceOf(Search.UnsupportedAnalyzerException.class)
                    .hasMessageContaining("SmartChineseAnalyzer")
                    .hasMessageContaining("ArabicAnalyzer, ArmenianAnalyzer");
            assertThatThrownBy(() -> search.parse(
                            "alpha",
                            BODY,
                            new Search.AnalyzerSpec.Named(
                                    "WhitespaceAnalyzer", Optional.of(List.of("a")), OptionalInt.empty())))
                    .isInstanceOf(Search.UnsupportedAnalyzerException.class)
                    .hasMessageContaining("takes no stop words");
            assertThatThrownBy(() -> search.parse(
                            "alpha",
                            BODY,
                            new Search.AnalyzerSpec.Named("EnglishAnalyzer", Optional.empty(), OptionalInt.of(3))))
                    .isInstanceOf(Search.UnsupportedAnalyzerException.class)
                    .hasMessageContaining("takes no longest token");
        }
    }

    @Test
    void opensSeveralDirectoriesAsOne(@TempDir Path empty) throws IOException {
        Path lucene4 = testIndex("lucene4");
        Path lucene5 = testIndex("lucene5");
        try (RuntimeIndex index = new Lucene5Runtime().open(List.of(lucene4, empty, lucene5), DirectoryKind.NIO)) {
            RuntimeIndex.Stats stats = index.stats();
            assertThat(stats.maxDoc()).isEqualTo(14);
            assertThat(stats.numDocs()).isEqualTo(12);
            assertThat(stats.indexes())
                    .extracting(RuntimeIndex.IndexStats::path, RuntimeIndex.IndexStats::docBase)
                    .containsExactly(tuple(lucene4, 0), tuple(lucene5, 7));
            assertThat(index.skipped()).extracting(RuntimeIndex.Skipped::path).containsExactly(empty);
            assertThat(index.fields().get(1).terms()).contains(new RuntimeIndex.TermStats(14, 52, 54));
            Documents.IndexDocument doc = index.documents().orElseThrow().document(10);
            assertThat(field(doc, "dv_num").docValues().orElseThrow().numbers()).containsExactly(400L);
            assertThat(index.terms()
                            .orElseThrow()
                            .postings("id", utf8("2"), 0, 10)
                            .postings())
                    .containsExactly(new Terms.Posting(1, 1, true), new Terms.Posting(8, 1, true));
            assertThat(index.terms().orElseThrow().positions(7, "body", utf8("alpha")))
                    .hasValueSatisfying(positions -> assertThat(
                                    positions.getFirst().payload().map(Lucene5IndexTest::text))
                            .contains("a1"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"lucene4", "lucene5"})
    void readsTheCommits(String name) throws IOException {
        boolean five = name.equals("lucene5");
        Commits commits = new Lucene5Runtime().commits().orElseThrow();
        Commits.CommitList list = commits.commits(testIndex(name), DirectoryKind.FS);
        assertThat(list.unreadable()).isEmpty();
        assertThat(list.commits()).extracting(Commits.Commit::segmentsFile).containsExactly("segments_2", "segments_1");
        Commits.Commit second = list.commits().getFirst();
        assertThat(second.segmentCount()).isEqualTo(2);
        assertThat(second.maxDoc()).isEqualTo(7);
        assertThat(second.delCount()).isEqualTo(1);
        assertThat(second.luceneVersion()).isEqualTo(five ? "5.5.5" : "4.10.4");
        assertThat(second.userData()).containsEntry("commit", "second");
        assertThat(list.commits().get(1).maxDoc()).isEqualTo(4);

        Commits.CommitDetails details =
                commits.commit(testIndex(name), DirectoryKind.NIO, 2).orElseThrow();
        Commits.Segment plain = details.segments().get(0);
        assertThat(plain.name()).isEqualTo("_0");
        assertThat(plain.codec()).contains(five ? "Lucene54" : "Lucene410");
        // Segment ids came with 5.0.
        assertThat(plain.id()).matches(id -> five ? id.orElseThrow().length() > 20 : id.isEmpty());
        assertThat(plain.compound()).isFalse();
        assertThat(plain.delGen()).hasValue(1);
        assertThat(plain.fieldInfosGen()).isEqualTo(five ? OptionalLong.of(1) : OptionalLong.empty());
        assertThat(plain.docValuesGen()).isEqualTo(five ? OptionalLong.of(1) : OptionalLong.empty());
        assertThat(plain.luceneVersion()).isEqualTo(five ? "5.5.5" : "4.10.4");
        assertThat(plain.sizeInBytes()).isPresent();
        assertThat(plain.diagnostics()).containsEntry("source", "flush");
        assertThat(plain.formats()).hasValueSatisfying(formats -> {
            assertThat(formats.points()).isEmpty();
            assertThat(formats.knnVectors()).isEmpty();
            assertThat(formats.storedFields()).isNotBlank();
            // 4.10's norms format is an anonymous subclass: shown by the class it extends.
            assertThat(formats.norms()).isEqualTo(five ? "Lucene53NormsFormat" : "Lucene49NormsFormat");
        });
        assertThat(plain.fieldInfosError()).isEmpty();
        assertThat(plain.fieldFormats())
                .filteredOn(formats ->
                        formats.field().equals("body") || formats.field().equals("dv_num"))
                .extracting(
                        Commits.FieldFormats::field, Commits.FieldFormats::postings, Commits.FieldFormats::docValues)
                .containsExactly(
                        tuple("body", Optional.of(five ? "Lucene50" : "Lucene41"), Optional.empty()),
                        tuple("dv_num", Optional.empty(), Optional.of(five ? "Lucene54" : "Lucene410")));
        Commits.Segment compound = details.segments().get(1);
        assertThat(compound.compound()).isTrue();
        assertThat(compound.docBase()).isEqualTo(4);
        assertThat(compound.delGen()).isEmpty();
        assertThat(compound.fieldFormats()).hasSize(11);
        assertThat(details.files()).extracting(Commits.CommitFile::name).contains("segments_2", "_1.cfs", "_0.si");
        assertThat(details.files())
                .filteredOn(file -> file.name().equals("_1.cfs"))
                .singleElement()
                .satisfies(file -> {
                    assertThat(file.segment()).contains("_1");
                    assertThat(file.size()).isPresent();
                });
        assertThat(commits.commit(testIndex(name), DirectoryKind.FS, 99)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void checksACleanIndexAtEachLevel(int level) {
        for (String name : List.of("lucene4", "lucene5")) {
            List<String> lines = new ArrayList<>();
            Check check = new Lucene5Runtime().check().orElseThrow();
            assertThat(check.hasLevels()).isTrue();
            Check.CheckResult result =
                    check.check(testIndex(name), DirectoryKind.FS, OptionalInt.of(level), lines::add, _ -> {});
            assertThat(result.error()).isEmpty();
            assertThat(result.clean()).isTrue();
            assertThat(result.segmentCount()).isEqualTo(2);
            assertThat(result.segments()).allSatisfy(segment -> {
                assertThat(segment.codec()).isPresent();
                assertThat(segment.error()).isEmpty();
            });
            assertThat(lines).anyMatch(line -> line.contains("No problems were detected"));
            assertThat(lines).anyMatch(line -> line.contains("test: check integrity"));
            // Level 1 checks the checksums only.
            if (level == 1) {
                assertThat(lines).noneMatch(line -> line.contains("test: terms"));
                // Lucene's hint names its -fast; leia's has levels. The 4.10 segments have no ids.
                assertThat(lines).noneMatch(line -> line.contains("-fast"));
                if (name.equals("lucene4")) {
                    assertThat(lines).anyMatch(line -> line.endsWith("Check at level 2 or 3 for full verification."));
                }
            } else {
                assertThat(lines).anyMatch(line -> line.contains("test: terms"));
            }
        }
    }

    @Test
    void checksADamagedIndexWithoutChangingIt(@TempDir Path dir) throws Exception {
        try (Stream<Path> files = Files.list(testIndex("lucene5"))) {
            for (Path file : files.toList()) {
                Files.copy(file, dir.resolve(file.getFileName()));
            }
        }
        Path postings = dir.resolve("_0_Lucene50_0.doc");
        byte[] bytes = Files.readAllBytes(postings);
        // Inside the file, so only its checksum notices.
        bytes[bytes.length / 2] ^= 0x55;
        Files.write(postings, bytes);
        Map<String, String> before = hashes(dir);

        Check.CheckResult result = new Lucene5Runtime()
                .check()
                .orElseThrow()
                .check(dir, DirectoryKind.NIO, OptionalInt.of(1), _ -> {}, _ -> {});
        assertThat(result.clean()).isFalse();
        assertThat(result.badSegments()).isEqualTo(1);
        assertThat(result.segments().get(0).error()).isPresent();
        assertThat(result.segments().get(1).error()).isEmpty();
        assertThat(hashes(dir)).isEqualTo(before);
        assertThat(dir.resolve("write.lock")).doesNotExist();
    }

    @Test
    void stopsACheckWhenProgressThrows() {
        CancellationException stop = new CancellationException("stop");
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> new Lucene5Runtime()
                        .check()
                        .orElseThrow()
                        .check(testIndex("lucene5"), DirectoryKind.FS, OptionalInt.of(3), _ -> {}, _ -> {
                            if (calls.incrementAndGet() > 3) {
                                throw stop;
                            }
                        }))
                .isSameAs(stop);
    }

    /** Hard rule 1: reading changes nothing in the index directory, and leaves no lock. */
    @ParameterizedTest
    @EnumSource(DirectoryKind.class)
    void changesNothing(DirectoryKind directoryKind) throws Exception {
        for (String name : List.of("lucene4", "lucene5")) {
            Path dir = testIndex(name);
            Map<String, String> before = hashes(dir);
            LuceneRuntime runtime = new Lucene5Runtime();
            try (RuntimeIndex index = runtime.open(List.of(dir), directoryKind)) {
                index.stats();
                index.documents().orElseThrow().document(2);
                index.documents().orElseThrow().termVector(2, "body");
                index.terms().orElseThrow().terms("body", Optional.empty(), 100);
                index.terms().orElseThrow().topTerms("body", 10, _ -> {});
                index.terms().orElseThrow().positions(2, "body", utf8("alpha"));
            }
            runtime.commits().orElseThrow().commits(dir, directoryKind);
            runtime.commits().orElseThrow().commit(dir, directoryKind, 2);
            runtime.check().orElseThrow().check(dir, directoryKind, OptionalInt.of(3), _ -> {}, _ -> {});
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
