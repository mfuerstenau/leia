package io.github.mfuerstenau.leia.lucene9;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.within;

import io.github.mfuerstenau.leia.runtime.Check;
import io.github.mfuerstenau.leia.runtime.Commits;
import io.github.mfuerstenau.leia.runtime.DirectoryKind;
import io.github.mfuerstenau.leia.runtime.Documents;
import io.github.mfuerstenau.leia.runtime.Knn;
import io.github.mfuerstenau.leia.runtime.LuceneRuntime;
import io.github.mfuerstenau.leia.runtime.Points;
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
 * Reads the indexes that the build writes (build.gradle.kts) with plain Lucene 9.12, through the
 * runtime interface: {@code lucene8}, written by 8.11, sorted by {@code order}, whose soft update of
 * document 3 is document 4; and {@code lucene8to9}, a copy to which 9.12 added documents 9 to 11, 8 to
 * 10 of the index, with KNN vectors, and deleted document 5 (5 of the index). The points are in {@link
 * Lucene9PointsTest}.
 */
class Lucene9IndexTest {

    private static final Search.ParserOptions BODY = new Search.ParserOptions("body", false, false, 0, 0, true, false);

    private static Path testIndex(String name) {
        String dir = System.getProperty("leia.test.index." + name);
        if (dir == null) {
            throw new IllegalStateException("System property leia.test.index." + name + " is not set");
        }
        return Path.of(dir).toAbsolutePath();
    }

    private static RuntimeIndex open(String name) throws IOException {
        return new Lucene9Runtime().open(List.of(testIndex(name)), DirectoryKind.FS);
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

    private static final Search.ParserOptions TITLE =
            new Search.ParserOptions("title", false, false, 0, 0, true, false);

    @Test
    void isFoundAsARuntime() {
        assertThat(ServiceLoader.load(LuceneRuntime.class))
                .singleElement()
                .isInstanceOfSatisfying(Lucene9Runtime.class, runtime -> {
                    assertThat(runtime.apiVersion()).isEqualTo(LuceneRuntime.API_VERSION);
                    assertThat(runtime.indexMajors()).containsExactly(8);
                    assertThat(runtime.luceneVersion()).isEqualTo("9.12.3");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"lucene8", "lucene8to9"})
    void readsTheStatsAndFields(String name) throws IOException {
        boolean nine = name.equals("lucene8to9");
        try (RuntimeIndex index = open(name)) {
            RuntimeIndex.Stats stats = index.stats();
            // The soft-deleted document is live, as in a plain DirectoryReader.
            assertThat(stats.numDocs()).isEqualTo(nine ? 9 : 7);
            assertThat(stats.maxDoc()).isEqualTo(nine ? 11 : 8);
            RuntimeIndex.IndexStats only = stats.indexes().getFirst();
            assertThat(only.segmentsFile()).isEqualTo(nine ? "segments_3" : "segments_2");
            assertThat(only.userData()).containsEntry("commit", nine ? "third" : "second");
            assertThat(only.segmentVersions())
                    .containsExactlyElementsOf(
                            nine ? List.of("8.11.4", "8.11.4", "9.12.3") : List.of("8.11.4", "8.11.4"));
            assertSegments(only);
            assertThat(only.luceneVersion()).contains(nine ? "9.12.3" : "8.11.4");
            // Created by 8, even once 9 wrote to it: its norms are those of 8.
            assertThat(only.createdVersionMajor()).hasValue(8);
            assertThat(only.createdMajor()).isEqualTo(8);

            List<String> names = new ArrayList<>(List.of(
                    "id",
                    "body",
                    "tag",
                    "price",
                    "blob",
                    "dv_num",
                    "dv_bin",
                    "dv_sorted",
                    "dv_set",
                    "dv_sorted_num",
                    "pt",
                    "loc",
                    "amount",
                    "order",
                    "__soft_deletes"));
            if (nine) {
                names.addAll(List.of("title", "embedding", "code"));
            }
            assertThat(stats.fields()).extracting(RuntimeIndex.Field::name).containsExactlyElementsOf(names);
            RuntimeIndex.Field body = fieldNamed(stats, "body");
            assertThat(body.indexOptions())
                    .isEqualTo(RuntimeIndex.IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS);
            assertThat(body.hasPayloads()).isTrue();
            assertThat(body.hasTermVectors()).isTrue();
            // alpha, document and its number in each document, beta in the odd ones, gamma twice in 1;
            // document 3 twice.
            assertThat(body.terms()).contains(new RuntimeIndex.TermStats(8, 30, 31));
            assertThat(body.vectors()).isEmpty();
            assertThat(List.of("pt", "loc", "amount", "body"))
                    .extracting(
                            field -> fieldNamed(stats, field).pointDimensionCount(),
                            field -> fieldNamed(stats, field).pointNumBytes())
                    .containsExactly(tuple(1, 4), tuple(2, 4), tuple(1, 8), tuple(0, 0));
            if (nine) {
                assertThat(fieldNamed(stats, "embedding").vectors())
                        .contains(new RuntimeIndex.VectorFormat(
                                4, RuntimeIndex.VectorEncoding.FLOAT32, RuntimeIndex.VectorSimilarity.COSINE));
                assertThat(fieldNamed(stats, "code").vectors())
                        .contains(new RuntimeIndex.VectorFormat(
                                4, RuntimeIndex.VectorEncoding.BYTE, RuntimeIndex.VectorSimilarity.EUCLIDEAN));
                assertThat(fieldNamed(stats, "embedding").indexOptions()).isEqualTo(RuntimeIndex.IndexOptions.NONE);
            }
        }
    }

    private static RuntimeIndex.Field fieldNamed(RuntimeIndex.Stats stats, String name) {
        return stats.fields().stream()
                .filter(field -> field.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void readsDocumentsWithDocValues() throws IOException {
        try (RuntimeIndex index = open("lucene8")) {
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
            assertThat(field(first, "body").hasTermVector()).isTrue();
            assertThat(field(first, "body").vector()).isEmpty();
            assertThat(field(first, "tag").norm()).isEmpty();
            assertThat(field(first, "dv_num").docValues())
                    .contains(new Documents.DocValues(RuntimeIndex.DocValuesType.NUMERIC, List.of(1L), List.of()));
            assertThat(field(first, "dv_sorted_num").docValues().orElseThrow().numbers())
                    .containsExactly(-1L, 1L);
            assertThat(field(first, "dv_bin").docValues().orElseThrow().values())
                    .extracting(Lucene9IndexTest::text)
                    .containsExactly("b1");
            assertThat(field(first, "dv_sorted").docValues().orElseThrow().values())
                    .extracting(Lucene9IndexTest::text)
                    .containsExactly("s1");
            assertThat(field(first, "dv_set").docValues().orElseThrow().values())
                    .extracting(Lucene9IndexTest::text)
                    .containsExactly("x", "y1");

            // Document 3 has no dv_num; the second commit set that of document 4 to 400.
            assertThat(field(documents.document(2), "dv_num").docValues()).isEmpty();
            assertThat(field(documents.document(3), "dv_num")
                            .docValues()
                            .orElseThrow()
                            .numbers())
                    .containsExactly(400L);
            // In the second segment, after the soft update of document 3.
            assertThat(field(documents.document(7), "dv_set")
                            .docValues()
                            .orElseThrow()
                            .values())
                    .extracting(Lucene9IndexTest::text)
                    .containsExactly("x", "y7");
            assertThat(documents.document(1).deleted()).isTrue();
            assertThatThrownBy(() -> documents.document(8)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    /** The raw norms: 8.x encodes the length, as 7.x does, which leia-server decodes by the created major. */
    @ParameterizedTest
    @ValueSource(strings = {"lucene8", "lucene8to9"})
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

    @Test
    void readsTheRawNorms() throws IOException {
        try (RuntimeIndex index = open("lucene8")) {
            Documents documents = index.documents().orElseThrow();
            // Six tokens in document 1, four in document 3, three in document 2.
            List<Long> norms = new ArrayList<>();
            for (int doc : List.of(0, 2, 1)) {
                norms.add(field(documents.document(doc), "body").norm().orElseThrow());
            }
            assertThat(norms).containsExactly(6L, 4L, 3L);
        }
    }

    @Test
    void readsTheSoftUpdatedDocument() throws IOException {
        try (RuntimeIndex index = open("lucene8")) {
            Documents documents = index.documents().orElseThrow();
            Documents.IndexDocument soft = documents.document(2);
            // The index sort puts the update first in its segment.
            Documents.IndexDocument update = documents.document(4);
            assertThat(soft.deleted()).isFalse();
            assertThat(field(soft, "id").stored()).isEqualTo(field(update, "id").stored());
            assertThat(field(soft, "__soft_deletes").docValues().orElseThrow().numbers())
                    .containsExactly(1L);
            assertThat(field(update, "__soft_deletes").docValues()).isEmpty();
        }
    }

    @Test
    void readsTermVectorsWithPayloads() throws IOException {
        try (RuntimeIndex index = open("lucene8")) {
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
            assertThat(alpha.endOffset()).hasValue(8);
            assertThat(alpha.payload().map(Lucene9IndexTest::text)).contains("a1");
            assertThat(index.documents().orElseThrow().termVector(0, "tag")).isEmpty();
        }
    }

    @Test
    void readsTermsAndPostingsWithDeletedDocuments() throws IOException {
        try (RuntimeIndex index = open("lucene8")) {
            Terms terms = index.terms().orElseThrow();
            Terms.TermsPage ids = terms.terms("id", Optional.of(utf8("3")), 2);
            assertThat(ids.terms())
                    .extracting(term -> text(term.term()), Terms.TermEntry::docFreq, Terms.TermEntry::totalTermFreq)
                    .containsExactly(tuple("3", 2, 2L), tuple("4", 1, 1L));
            assertThat(ids.next().map(Lucene9IndexTest::text)).contains("5");
            assertThat(terms.terms("nothing", Optional.empty(), 2).terms()).isEmpty();

            Terms.PostingsPage alpha = terms.postings("body", utf8("alpha"), 0, 3);
            assertThat(alpha.docFreq()).isEqualTo(8);
            assertThat(alpha.totalTermFreq()).isEqualTo(8);
            assertThat(alpha.postings())
                    .containsExactly(
                            new Terms.Posting(0, 1, false),
                            new Terms.Posting(1, 1, true),
                            new Terms.Posting(2, 1, false));
            assertThat(alpha.nextDoc()).hasValue(3);
            assertThat(terms.postings("body", utf8("alpha"), 6, 3).postings())
                    .extracting(Terms.Posting::docId)
                    .containsExactly(6, 7);
            // Docs only: totalTermFreq is docFreq, as in Lucene 10.
            Terms.PostingsPage odd = terms.postings("tag", utf8("odd"), 0, 10);
            assertThat(odd.totalTermFreq()).isEqualTo(odd.docFreq()).isEqualTo(5);
        }
    }

    @Test
    void readsPositionsWithOffsetsAndPayloads() throws IOException {
        try (RuntimeIndex index = open("lucene8")) {
            Terms terms = index.terms().orElseThrow();
            assertThat(terms.positions(0, "body", utf8("gamma")).orElseThrow())
                    .extracting(
                            position -> position.position().getAsInt(),
                            position -> position.startOffset().getAsInt(),
                            position -> position.endOffset().getAsInt())
                    .containsExactly(tuple(4, 25, 30), tuple(5, 31, 36));
            assertThat(terms.positions(5, "body", utf8("alpha")).orElseThrow())
                    .singleElement()
                    .satisfies(alpha -> assertThat(alpha.payload().map(Lucene9IndexTest::text))
                            .contains("a5"));
            // Deleted documents have their positions too.
            assertThat(terms.positions(1, "body", utf8("alpha"))).isPresent();
            assertThat(terms.positions(1, "body", utf8("gamma"))).isEmpty();
            assertThat(terms.positions(0, "tag", utf8("odd"))).hasValue(List.of());
            assertThatThrownBy(() -> terms.positions(8, "body", utf8("alpha")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void findsTheTopTerms() throws IOException {
        try (RuntimeIndex index = open("lucene8")) {
            List<Long> progress = new ArrayList<>();
            Terms.FieldTerms terms = index.terms().orElseThrow().topTerms("body", 3, progress::add);
            // alpha, beta, document, gamma and 1 to 7, the deleted document's too.
            assertThat(terms.uniqueTerms()).isEqualTo(11);
            assertThat(progress).last().isEqualTo(11L);
            assertThat(terms.topTerms())
                    .extracting(term -> text(term.term()), Terms.TermEntry::docFreq, Terms.TermEntry::totalTermFreq)
                    .containsExactly(tuple("alpha", 8, 8L), tuple("document", 8, 8L), tuple("beta", 5, 5L));
        }
    }

    @Test
    void searches() throws Exception {
        try (RuntimeIndex index = open("lucene8")) {
            Search search = index.search().orElseThrow();
            Search.ParsedQuery query = search.parse("documents AND (beta OR gamma)", BODY, named("EnglishAnalyzer"));
            assertThat(query).hasToString("+body:document +(body:beta body:gamma)");
            Search.SearchResults results = search.search(query, 0, 10, Optional.of(List.of("id")), _ -> {});
            assertThat(results.totalHits()).isEqualTo(5);
            assertThat(results.hits()).extracting(Search.Hit::docId).containsExactlyInAnyOrder(0, 2, 4, 5, 7);
            assertThat(results.hits().getFirst().fields()).containsOnlyKeys("id");
            assertThatThrownBy(() -> search.parse("body:(", BODY, named("StandardAnalyzer")))
                    .isInstanceOf(Search.InvalidQueryException.class);
        }
    }

    @Test
    void matchesAQuery() throws Exception {
        try (RuntimeIndex index = open("lucene8")) {
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

    @ParameterizedTest
    @ValueSource(strings = {"lucene8", "lucene8to9"})
    void searchesATerm(String name) throws Exception {
        try (RuntimeIndex index = open(name)) {
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
        try (RuntimeIndex index = open("lucene8")) {
            Search search = index.search().orElseThrow();
            Search.ParsedQuery alpha = search.parse("alpha", BODY, named("StandardAnalyzer"));
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

            Search.ParsedQuery beta = search.parse("beta", BODY, named("StandardAnalyzer"));
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

    private static Search.AnalyzerSpec named(String name) {
        return new Search.AnalyzerSpec.Named(name, Optional.empty(), OptionalInt.empty());
    }

    @Test
    void dropsTheAutoStopWordsAfterTheWholeChain() throws Exception {
        try (RuntimeIndex index = open("lucene8")) {
            Search search = index.search().orElseThrow();
            // The filter runs after stemming, so the word run drops running, in body only.
            assertThat(search.parse(
                            "running beta id:running",
                            BODY,
                            new Search.AnalyzerSpec.AutoStopWords(
                                    named("EnglishAnalyzer"), Map.of("body", List.of("run")))))
                    .hasToString("body:beta id:run");
        }
    }

    @Test
    void parsesWithTheAnalyzersOf912() throws Exception {
        try (RuntimeIndex index = open("lucene8")) {
            Search search = index.search().orElseThrow();
            assertThat(search.analyzers())
                    .contains(
                            "StandardAnalyzer",
                            "ClassicAnalyzer",
                            "UAX29URLEmailAnalyzer",
                            "EstonianAnalyzer",
                            "TeluguAnalyzer")
                    .hasSize(47);
            assertThat(search.parse("Alpha the", BODY, named("StandardAnalyzer")))
                    .hasToString("body:alpha body:the");
            // 9.x's StopAnalyzer has no default stop words; leia gives it 7.7's.
            assertThat(search.parse("Alpha the", BODY, named("StopAnalyzer"))).hasToString("body:alpha");
            assertThat(search.parse("Häuser", BODY, named("GermanAnalyzer"))).hasToString("body:haus");
            assertThat(search.parse(
                            "abcdef",
                            BODY,
                            new Search.AnalyzerSpec.Named("StandardAnalyzer", Optional.empty(), OptionalInt.of(3))))
                    .hasToString("body:abc body:def");
            assertThatThrownBy(() -> search.parse("alpha", BODY, named("SmartChineseAnalyzer")))
                    .isInstanceOf(Search.UnsupportedAnalyzerException.class)
                    .hasMessageContaining("Lucene 9.12.3 has no analyzer SmartChineseAnalyzer");
        }
    }

    @Test
    void readsTheVectorsOfDocuments() throws IOException {
        try (RuntimeIndex index = open("lucene8to9")) {
            Documents documents = index.documents().orElseThrow();
            // Document 10 of the 9.x segment.
            Documents.IndexDocument ten = documents.document(9);
            assertThat(field(ten, "id").stored()).containsExactly(new RuntimeIndex.StoredValue.Text("10"));
            assertThat(field(ten, "embedding").vector())
                    .hasValueSatisfying(vector -> assertThat(vector)
                            .isInstanceOfSatisfying(
                                    RuntimeIndex.Vector.Floats.class,
                                    floats -> assertThat(floats.values()).containsExactly(0.8f, 0.6f, 0, 0)));
            assertThat(field(ten, "code").vector())
                    .hasValueSatisfying(vector -> assertThat(vector)
                            .isInstanceOfSatisfying(
                                    RuntimeIndex.Vector.Bytes.class,
                                    bytes -> assertThat(bytes.values()).containsExactly(4, 3, 2, 1)));
            assertThat(field(documents.document(10), "code").vector()).isEmpty();
            // A document of 8.x has none.
            assertThat(field(documents.document(0), "embedding").vector()).isEmpty();
            // Deleted by 9.12.
            assertThat(documents.document(5).deleted()).isTrue();

            Knn knn = index.knn().orElseThrow();
            assertThat(knn.vector(8, "embedding"))
                    .hasValueSatisfying(vector -> assertThat(((RuntimeIndex.Vector.Floats) vector).values())
                            .containsExactly(1, 0, 0, 0));
            assertThat(knn.vector(0, "embedding")).isEmpty();
            assertThatThrownBy(() -> knn.vector(11, "embedding")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> knn.vector(0, "body")).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void searchesTheNearestVectors() throws Exception {
        try (RuntimeIndex index = open("lucene8to9")) {
            Search search = index.search().orElseThrow();
            Knn knn = index.knn().orElseThrow();
            RuntimeIndex.Vector north = new RuntimeIndex.Vector.Floats(new float[] {1, 0, 0, 0});

            Search.SearchResults approximate = search.search(
                    knn.approximate("embedding", north, 2, Optional.empty()), 0, 10, Optional.empty(), _ -> {});
            assertThat(approximate.totalHits()).isEqualTo(2);
            assertThat(approximate.hits()).extracting(Search.Hit::docId).containsExactly(8, 9);
            // Cosine, as Lucene scores it: (1 + cos) / 2.
            assertThat(approximate.hits().getFirst().score()).isEqualTo(1f);
            assertThat(approximate.hits().get(1).score()).isCloseTo(0.9f, within(1e-6f));
            assertThat(approximate.hits().getFirst().fields()).containsKeys("id", "title");

            Search.SearchResults exact =
                    search.search(knn.exact("embedding", north, Optional.empty()), 0, 10, Optional.empty(), _ -> {});
            assertThat(exact.totalHits()).isEqualTo(3);
            assertThat(exact.hits())
                    .extracting(Search.Hit::docId, Search.Hit::score)
                    .containsExactly(
                            tuple(8, approximate.hits().getFirst().score()),
                            tuple(9, approximate.hits().get(1).score()),
                            tuple(10, 0.5f));
            assertThat(exact.query()).startsWith("ExactKnnQuery:embedding[1.0,...]");

            Search.ParsedQuery east = search.parse("east", TITLE, named("StandardAnalyzer"));
            Search.SearchResults filtered = search.search(
                    knn.approximate("embedding", north, 3, Optional.of(east)), 0, 10, Optional.empty(), _ -> {});
            assertThat(filtered.hits()).extracting(Search.Hit::docId).containsExactly(9, 10);
            assertThat(search.search(knn.exact("embedding", north, Optional.of(east)), 0, 10, Optional.empty(), _ -> {})
                            .hits())
                    .extracting(Search.Hit::docId)
                    .containsExactly(9, 10);

            RuntimeIndex.Vector code = new RuntimeIndex.Vector.Bytes(new byte[] {4, 3, 2, 2});
            assertThat(search.search(
                                    knn.approximate("code", code, 1, Optional.empty()),
                                    0,
                                    10,
                                    Optional.empty(),
                                    _ -> {})
                            .hits())
                    .extracting(Search.Hit::docId)
                    .containsExactly(9);
            assertThat(search.search(knn.exact("code", code, Optional.empty()), 0, 10, Optional.empty(), _ -> {})
                            .hits())
                    .extracting(Search.Hit::docId)
                    .containsExactly(9, 8);
            assertThatThrownBy(() -> knn.exact("body", north, Optional.empty()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void stopsASearchWhenProgressThrows() throws Exception {
        try (RuntimeIndex index = open("lucene8to9")) {
            Search search = index.search().orElseThrow();
            Search.ParsedQuery query = index.knn()
                    .orElseThrow()
                    .exact("embedding", new RuntimeIndex.Vector.Floats(new float[] {1, 0, 0, 0}), Optional.empty());
            CancellationException stop = new CancellationException("stop");
            assertThatThrownBy(() -> search.search(query, 0, 10, Optional.empty(), _ -> {
                        throw stop;
                    }))
                    .isSameAs(stop);
        }
    }

    /**
     * Rebuilds every document, deleted ones included, in every field with postings or points, and
     * checks it against what the term vectors, postings, positions and point leaves say.
     */
    @ParameterizedTest
    @ValueSource(strings = {"lucene8", "lucene8to9"})
    void rebuildsADocument(String name) throws IOException {
        try (RuntimeIndex index = open(name)) {
            Rebuild rebuild = index.rebuild().orElseThrow();
            List<RuntimeIndex.Field> fields = index.fields().stream()
                    .filter(field ->
                            field.indexOptions() != RuntimeIndex.IndexOptions.NONE || field.pointDimensionCount() > 0)
                    .toList();
            List<String> names = fields.stream().map(RuntimeIndex.Field::name).toList();
            for (int doc = 0; doc < index.maxDoc(); doc++) {
                List<Long> progress = new ArrayList<>();
                List<Rebuild.RebuiltField> rebuilt = rebuild.rebuild(doc, names, progress::add);
                assertThat(rebuilt).extracting(Rebuild.RebuiltField::field).containsExactlyElementsOf(names);
                // The count is what the rebuild goes through.
                assertThat(rebuild.count(doc, names)).hasValue(progress.getLast());
                for (int i = 0; i < fields.size(); i++) {
                    RuntimeIndex.Field field = fields.get(i);
                    Rebuild.RebuiltField one = rebuilt.get(i);
                    List<Documents.VectorTerm> expected = one.source() == Rebuild.Source.TERM_VECTOR
                            ? index.documents().orElseThrow().termVector(doc, field.name())
                            : postingsOf(index, doc, field);
                    assertThat(describe(one.terms()))
                            .as("%s of document %d", field.name(), doc)
                            .isEqualTo(describe(expected));
                    assertThat(one.points().stream()
                                    .map(HexFormat.of()::formatHex)
                                    .toList())
                            .as("points of %s of document %d", field.name(), doc)
                            .isEqualTo(pointsOf(index, doc, field));
                }
            }
            List<Rebuild.RebuiltField> first = rebuild.rebuild(0, List.of("body", "id", "tag"), _ -> {});
            assertThat(first)
                    .extracting(Rebuild.RebuiltField::source, Rebuild.RebuiltField::indexOptions)
                    .containsExactly(
                            tuple(
                                    Rebuild.Source.TERM_VECTOR,
                                    RuntimeIndex.IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS),
                            tuple(Rebuild.Source.POSTINGS, RuntimeIndex.IndexOptions.DOCS),
                            tuple(Rebuild.Source.POSTINGS, RuntimeIndex.IndexOptions.DOCS));
            assertThat(first.get(2).terms()).singleElement().satisfies(tag -> {
                assertThat(text(tag.term())).isEqualTo("odd");
                assertThat(tag.freq()).isEqualTo(1);
                assertThat(tag.positions()).isEmpty();
            });
            // Document 1 is deleted, and rebuilt too.
            assertThat(rebuild.rebuild(1, List.of("id"), _ -> {}).getFirst().terms())
                    .extracting(term -> text(term.term()))
                    .containsExactly("2");
            // Its points too, 15 in pt, in unsigned order.
            assertThat(rebuild.rebuild(1, List.of("pt"), _ -> {}).getFirst().points())
                    .hasSize(15)
                    .isSortedAccordingTo(Arrays::compareUnsigned);
            CancellationException stop = new CancellationException("stop");
            assertThatThrownBy(() -> rebuild.rebuild(0, names, _ -> {
                        throw stop;
                    }))
                    .isSameAs(stop);
        }
    }

    /** The terms of {@code doc} in {@code field}, read from the postings and positions of every term. */
    private static List<Documents.VectorTerm> postingsOf(RuntimeIndex index, int doc, RuntimeIndex.Field field)
            throws IOException {
        List<Documents.VectorTerm> result = new ArrayList<>();
        if (field.indexOptions() == RuntimeIndex.IndexOptions.NONE) {
            return result;
        }
        boolean positions = field.indexOptions().compareTo(RuntimeIndex.IndexOptions.DOCS_AND_FREQS_AND_POSITIONS) >= 0;
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

    /** The points of {@code doc} in {@code field}, read from the leaves of its segment's tree. */
    private static List<String> pointsOf(RuntimeIndex index, int doc, RuntimeIndex.Field field) throws IOException {
        if (field.pointDimensionCount() == 0) {
            return List.of();
        }
        Points points = index.points().orElseThrow();
        List<Points.LeafPoint> all = new ArrayList<>();
        for (Points.Segment segment : points.tree(field.name()).segments()) {
            if (doc >= segment.docBase() && doc < segment.docBase() + segment.maxDoc()) {
                collectPoints(points, field.name(), segment.segment(), new ArrayList<>(), all);
            }
        }
        return all.stream()
                .filter(point -> point.docId() == doc)
                .map(Points.LeafPoint::value)
                .sorted(Arrays::compareUnsigned)
                .map(HexFormat.of()::formatHex)
                .toList();
    }

    private static void collectPoints(
            Points points, String field, int segment, List<Integer> path, List<Points.LeafPoint> all)
            throws IOException {
        Points.CellContents contents = points.cell(field, segment, List.copyOf(path));
        all.addAll(contents.points());
        for (int i = 0; i < contents.children().size(); i++) {
            path.add(i);
            collectPoints(points, field, segment, path, all);
            path.removeLast();
        }
    }

    @Test
    void opensSeveralDirectoriesAsOne(@TempDir Path empty) throws IOException {
        Path lucene8 = testIndex("lucene8");
        Path lucene8to9 = testIndex("lucene8to9");
        try (RuntimeIndex index = new Lucene9Runtime().open(List.of(lucene8, empty, lucene8to9), DirectoryKind.NIO)) {
            RuntimeIndex.Stats stats = index.stats();
            assertThat(stats.maxDoc()).isEqualTo(19);
            assertThat(stats.numDocs()).isEqualTo(16);
            assertThat(stats.indexes())
                    .extracting(RuntimeIndex.IndexStats::path, RuntimeIndex.IndexStats::docBase)
                    .containsExactly(tuple(lucene8, 0), tuple(lucene8to9, 8));
            assertThat(index.skipped()).extracting(RuntimeIndex.Skipped::path).containsExactly(empty);
            assertThat(index.knn()
                            .orElseThrow()
                            .vector(16, "embedding")
                            .map(vector -> ((RuntimeIndex.Vector.Floats) vector).values()))
                    .hasValueSatisfying(values -> assertThat(values).containsExactly(1, 0, 0, 0));
        }
    }

    @Test
    void readsTheCommits() throws IOException {
        Commits commits = new Lucene9Runtime().commits().orElseThrow();
        Commits.CommitList list = commits.commits(testIndex("lucene8to9"), DirectoryKind.FS);
        assertThat(list.unreadable()).isEmpty();
        assertThat(list.commits())
                .extracting(Commits.Commit::segmentsFile, Commits.Commit::luceneVersion)
                .containsExactly(
                        tuple("segments_3", "9.12.3"), tuple("segments_2", "8.11.4"), tuple("segments_1", "8.11.4"));
        Commits.Commit third = list.commits().getFirst();
        assertThat(third.segmentCount()).isEqualTo(3);
        assertThat(third.maxDoc()).isEqualTo(11);
        // Hard deletes only.
        assertThat(third.delCount()).isEqualTo(2);

        Commits.CommitDetails details =
                commits.commit(testIndex("lucene8to9"), DirectoryKind.NIO, 3).orElseThrow();
        Commits.Segment plain = details.segments().get(0);
        assertThat(plain.name()).isEqualTo("_0");
        assertThat(plain.codec()).contains("Lucene87");
        assertThat(plain.delCount()).isEqualTo(1);
        assertThat(plain.softDelCount()).isEqualTo(1);
        assertThat(plain.luceneVersion()).isEqualTo("8.11.4");
        assertThat(plain.minLuceneVersion()).contains("8.11.4");
        assertThat(plain.indexSort()).contains("<long: \"order\">");
        assertThat(plain.formats()).hasValueSatisfying(formats -> {
            assertThat(formats.points()).contains("Lucene86PointsFormat");
            assertThat(formats.norms()).isEqualTo("Lucene80NormsFormat");
            assertThat(formats.knnVectors()).isEmpty();
        });
        assertThat(plain.fieldFormats())
                .filteredOn(formats -> formats.field().equals("body"))
                .extracting(Commits.FieldFormats::postings, Commits.FieldFormats::knnVectors)
                .containsExactly(tuple(Optional.of("Lucene84"), Optional.empty()));
        Commits.Segment compound = details.segments().get(1);
        assertThat(compound.compound()).isTrue();
        // Document 5, deleted by 9.12.
        assertThat(compound.delCount()).isEqualTo(1);
        assertThat(compound.delGen()).hasValue(1);
        Commits.Segment nine = details.segments().get(2);
        assertThat(nine.name()).isEqualTo("_2");
        assertThat(nine.docBase()).isEqualTo(8);
        assertThat(nine.codec()).contains("Lucene912");
        assertThat(nine.luceneVersion()).isEqualTo("9.12.3");
        assertThat(nine.fieldFormats())
                .filteredOn(formats -> formats.field().equals("embedding"))
                .extracting(Commits.FieldFormats::knnVectors)
                .containsExactly(Optional.of("Lucene99HnswVectorsFormat"));
        assertThat(nine.formats().orElseThrow().knnVectors()).isPresent();
        assertThat(commits.commit(testIndex("lucene8to9"), DirectoryKind.FS, 99))
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void checksACleanIndexAtEachLevel(int level) {
        for (String name : List.of("lucene8", "lucene8to9")) {
            List<String> lines = new ArrayList<>();
            Check check = new Lucene9Runtime().check().orElseThrow();
            assertThat(check.hasLevels()).isTrue();
            Check.CheckResult result =
                    check.check(testIndex(name), DirectoryKind.FS, OptionalInt.of(level), lines::add, _ -> {});
            assertThat(result.error()).isEmpty();
            assertThat(result.clean()).isTrue();
            assertThat(result.segmentCount()).isEqualTo(name.equals("lucene8") ? 2 : 3);
            assertThat(result.segments()).allSatisfy(segment -> {
                assertThat(segment.codec()).isPresent();
                assertThat(segment.error()).isEmpty();
            });
            assertThat(lines).anyMatch(line -> line.contains("No problems were detected"));
            if (level == 1) {
                assertThat(lines).noneMatch(line -> line.contains("test: terms"));
            } else {
                assertThat(lines).anyMatch(line -> line.contains("test: terms"));
                assertThat(lines).anyMatch(line -> line.contains("test: points"));
                assertThat(lines).anyMatch(line -> line.contains("test: index sort"));
            }
            if (level >= 2 && name.equals("lucene8to9")) {
                assertThat(lines).anyMatch(line -> line.contains("test: vectors"));
            }
        }
    }

    @Test
    void checksADamagedIndexWithoutChangingIt(@TempDir Path dir) throws Exception {
        try (Stream<Path> files = Files.list(testIndex("lucene8"))) {
            for (Path file : files.toList()) {
                Files.copy(file, dir.resolve(file.getFileName()));
            }
        }
        Path postings = dir.resolve("_0_Lucene84_0.doc");
        byte[] bytes = Files.readAllBytes(postings);
        // Inside the file, so only its checksum notices.
        bytes[bytes.length / 2] ^= 0x55;
        Files.write(postings, bytes);
        Map<String, String> before = hashes(dir);

        Check.CheckResult result = new Lucene9Runtime()
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
        assertThatThrownBy(() -> new Lucene9Runtime()
                        .check()
                        .orElseThrow()
                        .check(testIndex("lucene8to9"), DirectoryKind.FS, OptionalInt.of(3), _ -> {}, _ -> {
                            if (calls.incrementAndGet() > 3) {
                                throw stop;
                            }
                        }))
                .isSameAs(stop);
    }

    @ParameterizedTest
    @ValueSource(strings = {"lucene8:segments_2", "lucene8to9:segments_3"})
    void reopensAChangedIndex(String nameAndNewest, @TempDir Path dir) throws IOException {
        String name = nameAndNewest.substring(0, nameAndNewest.indexOf(':'));
        String newest = nameAndNewest.substring(nameAndNewest.indexOf(':') + 1);
        Path index0 = testIndex(name);
        copyWithout(index0, dir, newest);
        RuntimeIndex index = new Lucene9Runtime().open(List.of(dir), DirectoryKind.FS);
        try (index;
                RuntimeIndex latest = new Lucene9Runtime().open(List.of(index0), DirectoryKind.FS)) {
            String older = index.stats().indexes().getFirst().segmentsFile();
            assertThat(older).isNotEqualTo(newest);
            if (name.equals("lucene8to9")) {
                // The commit of 9.12 adds the KNN vector fields.
                assertThat(index.fields()).extracting(RuntimeIndex.Field::name).doesNotContain("embedding");
            }
            assertThat(index.reopen()).isEmpty();

            Files.copy(index0.resolve(newest), dir.resolve(newest));
            try (RuntimeIndex reopened = index.reopen().orElseThrow()) {
                assertThat(reopened.directories()).containsExactly(dir);
                assertThat(reopened.stats().indexes().getFirst().segmentsFile()).isEqualTo(newest);
                assertThat(reopened.maxDoc()).isEqualTo(latest.maxDoc()).isGreaterThan(index.maxDoc());
                assertThat(reopened.stats().numDocs()).isEqualTo(latest.stats().numDocs());
                assertThat(reopened.fields()).isEqualTo(latest.fields());
                if (name.equals("lucene8to9")) {
                    assertThat(reopened.fields())
                            .extracting(RuntimeIndex.Field::name)
                            .contains("embedding");
                }
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
        Path unchanged = testIndex("lucene8");
        copyWithout(testIndex("lucene8to9"), dir, "segments_3");
        RuntimeIndex index = new Lucene9Runtime().open(List.of(unchanged, dir), DirectoryKind.FS);
        try (index;
                RuntimeIndex latest =
                        new Lucene9Runtime().open(List.of(unchanged, testIndex("lucene8to9")), DirectoryKind.FS)) {
            assertThat(index.reopen()).isEmpty();
            Files.copy(testIndex("lucene8to9").resolve("segments_3"), dir.resolve("segments_3"));
            try (RuntimeIndex reopened = index.reopen().orElseThrow()) {
                index.close();
                assertThat(reopened.directories()).containsExactly(unchanged, dir);
                assertThat(reopened.stats().indexes().get(1).segmentsFile()).isEqualTo("segments_3");
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
        for (String name : List.of("lucene8", "lucene8to9")) {
            Path dir = testIndex(name);
            Map<String, String> before = hashes(dir);
            LuceneRuntime runtime = new Lucene9Runtime();
            try (RuntimeIndex index = runtime.open(List.of(dir), directoryKind)) {
                index.stats();
                index.documents().orElseThrow().document(2);
                index.documents().orElseThrow().termVector(2, "body");
                index.terms().orElseThrow().terms("body", Optional.empty(), 100);
                index.terms().orElseThrow().topTerms("body", 10, _ -> {});
                index.terms().orElseThrow().positions(2, "body", utf8("alpha"));
                Points points = index.points().orElseThrow();
                points.tree("pt");
                points.cell("pt", 0, List.of(1));
                if (name.equals("lucene8to9")) {
                    Search search = index.search().orElseThrow();
                    Knn knn = index.knn().orElseThrow();
                    RuntimeIndex.Vector target = knn.vector(8, "embedding").orElseThrow();
                    search.search(
                            knn.approximate("embedding", target, 2, Optional.empty()),
                            0,
                            10,
                            Optional.empty(),
                            _ -> {});
                }
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
