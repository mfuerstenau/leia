package io.github.mfuerstenau.leia.lucene7;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.withinPercentage;

import io.github.mfuerstenau.leia.runtime.Check;
import io.github.mfuerstenau.leia.runtime.Commits;
import io.github.mfuerstenau.leia.runtime.DirectoryKind;
import io.github.mfuerstenau.leia.runtime.DocValuesSearch;
import io.github.mfuerstenau.leia.runtime.Documents;
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
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.apache.lucene.search.similarities.ClassicSimilarity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Reads the Lucene 6.x and 7.x indexes that the build writes (build.gradle.kts) with plain Lucene 7.7,
 * through the runtime interface. Both have the same documents; 6.x adds trie-encoded terms and an
 * index sort, 7.x a soft update, which adds a document 8. The points are in {@link Lucene7PointsTest}.
 */
class Lucene7IndexTest {

    private static final Search.ParserOptions BODY = new Search.ParserOptions("body", false, false, 0, 0, true, false);

    private static Path testIndex(String name) {
        String dir = System.getProperty("leia.test.index." + name);
        if (dir == null) {
            throw new IllegalStateException("System property leia.test.index." + name + " is not set");
        }
        return Path.of(dir).toAbsolutePath();
    }

    private static RuntimeIndex open(String name) throws IOException {
        return new Lucene7Runtime().open(List.of(testIndex(name)), DirectoryKind.FS);
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
                .isInstanceOfSatisfying(Lucene7Runtime.class, runtime -> {
                    assertThat(runtime.apiVersion()).isEqualTo(LuceneRuntime.API_VERSION);
                    assertThat(runtime.indexMajors()).containsExactlyInAnyOrder(6, 7);
                    assertThat(runtime.luceneVersion()).isEqualTo("7.7.3");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"lucene6", "lucene7"})
    void readsTheStatsAndFields(String name) throws IOException {
        boolean seven = name.equals("lucene7");
        try (RuntimeIndex index = open(name)) {
            RuntimeIndex.Stats stats = index.stats();
            // The soft-deleted document is live, as in a plain DirectoryReader.
            assertThat(stats.numDocs()).isEqualTo(seven ? 7 : 6);
            assertThat(stats.maxDoc()).isEqualTo(seven ? 8 : 7);
            RuntimeIndex.IndexStats only = stats.indexes().getFirst();
            assertThat(only.segmentsFile()).isEqualTo("segments_2");
            assertThat(only.generation()).isEqualTo(2);
            assertThat(only.userData()).containsEntry("commit", "second");
            String version = seven ? "7.7.3" : "6.6.6";
            assertThat(only.segmentVersions()).containsExactly(version, version);
            assertSegments(only);
            assertThat(only.luceneVersion()).contains(version);
            // Recorded since 7.0; 7.7 takes a commit of 6.x as created by 6.
            assertThat(only.createdVersionMajor()).hasValue(seven ? 7 : 6);
            assertThat(only.createdMajor()).isEqualTo(seven ? 7 : 6);

            List<String> names = new ArrayList<>(List.of("id", "body", "tag", "price"));
            if (!seven) {
                names.add("time");
            }
            names.addAll(List.of("blob", "dv_num", "dv_bin", "dv_sorted", "dv_set", "dv_sorted_num"));
            names.addAll(
                    seven ? List.of("pt", "loc", "amount", "__soft_deletes") : List.of("order", "pt", "loc", "amount"));
            assertThat(stats.fields()).extracting(RuntimeIndex.Field::name).containsExactlyElementsOf(names);
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
                            // alpha, document and its number in each document, beta in the odd ones,
                            // gamma twice in 1; 7.x has document 3 twice.
                            Optional.of(
                                    seven
                                            ? new RuntimeIndex.TermStats(8, 30, 31)
                                            : new RuntimeIndex.TermStats(7, 26, 27))));
            RuntimeIndex.Field tag = stats.fields().get(2);
            assertThat(tag.indexOptions()).isEqualTo(RuntimeIndex.IndexOptions.DOCS);
            assertThat(tag.hasNorms()).isFalse();
            // Without frequencies, sumTotalTermFreq is sumDocFreq.
            int docs = seven ? 8 : 7;
            assertThat(tag.terms()).contains(new RuntimeIndex.TermStats(docs, docs, docs));
            assertThat(fieldNamed(stats, "blob").indexOptions()).isEqualTo(RuntimeIndex.IndexOptions.NONE);
            assertThat(fieldNamed(stats, "blob").terms()).isEmpty();
            assertThat(List.of("dv_num", "dv_bin", "dv_sorted", "dv_set", "dv_sorted_num"))
                    .extracting(field -> fieldNamed(stats, field).docValuesType())
                    .containsExactly(
                            RuntimeIndex.DocValuesType.NUMERIC,
                            RuntimeIndex.DocValuesType.BINARY,
                            RuntimeIndex.DocValuesType.SORTED,
                            RuntimeIndex.DocValuesType.SORTED_SET,
                            RuntimeIndex.DocValuesType.SORTED_NUMERIC);
            assertThat(List.of("pt", "loc", "amount", "body"))
                    .extracting(
                            field -> fieldNamed(stats, field).pointDimensionCount(),
                            field -> fieldNamed(stats, field).pointNumBytes())
                    .containsExactly(tuple(1, 4), tuple(2, 4), tuple(1, 8), tuple(0, 0));
        }
    }

    private static RuntimeIndex.Field fieldNamed(RuntimeIndex.Stats stats, String name) {
        return stats.fields().stream()
                .filter(field -> field.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    @ParameterizedTest
    @ValueSource(strings = {"lucene6", "lucene7"})
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
                    .extracting(Lucene7IndexTest::text)
                    .containsExactly("b1");
            assertThat(field(first, "dv_sorted").docValues().orElseThrow().values())
                    .extracting(Lucene7IndexTest::text)
                    .containsExactly("s1");
            assertThat(field(first, "dv_set").docValues().orElseThrow().values())
                    .extracting(Lucene7IndexTest::text)
                    .containsExactly("x", "y1");

            // Document 3 has no dv_num; the second commit set that of document 4 to 400.
            assertThat(field(documents.document(2), "dv_num").docValues()).isEmpty();
            assertThat(field(documents.document(3), "dv_num")
                            .docValues()
                            .orElseThrow()
                            .numbers())
                    .containsExactly(400L);
            // In the second segment.
            assertThat(field(documents.document(6), "dv_set")
                            .docValues()
                            .orElseThrow()
                            .values())
                    .extracting(Lucene7IndexTest::text)
                    .containsExactly("x", "y7");
            assertThat(documents.document(1).deleted()).isTrue();
            int maxDoc = name.equals("lucene7") ? 8 : 7;
            assertThatThrownBy(() -> documents.document(maxDoc)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    /**
     * The raw norms: 6.x encodes {@code 1 / sqrt(length)} as a byte of {@code floatToByte315}, 7.x the
     * length itself, which leia-server decodes by the created major.
     */
    @ParameterizedTest
    @ValueSource(strings = {"lucene6", "lucene7"})
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
    @ValueSource(strings = {"lucene6", "lucene7"})
    void readsTheRawNorms(String name) throws IOException {
        try (RuntimeIndex index = open(name)) {
            Documents documents = index.documents().orElseThrow();
            // Six tokens in document 1, four in document 3, three in document 2.
            List<Long> norms = new ArrayList<>();
            for (int doc : List.of(0, 2, 1)) {
                norms.add(field(documents.document(doc), "body").norm().orElseThrow());
            }
            assertThat(norms)
                    .containsExactlyElementsOf(
                            name.equals("lucene7") ? List.of(6L, 4L, 3L) : List.of(118L, 120L, 120L));
        }
    }

    @Test
    void readsTheSoftUpdatedDocument() throws IOException {
        try (RuntimeIndex index = open("lucene7")) {
            Documents documents = index.documents().orElseThrow();
            Documents.IndexDocument soft = documents.document(2);
            Documents.IndexDocument update = documents.document(7);
            assertThat(soft.deleted()).isFalse();
            assertThat(field(soft, "id").stored()).isEqualTo(field(update, "id").stored());
            assertThat(field(soft, "__soft_deletes").docValues().orElseThrow().numbers())
                    .containsExactly(1L);
            assertThat(field(update, "__soft_deletes").docValues()).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"lucene6", "lucene7"})
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
            assertThat(alpha.payload().map(Lucene7IndexTest::text)).contains("a1");
            assertThat(vector.get(4).positions())
                    .extracting(position -> position.position().getAsInt())
                    .containsExactly(4, 5);
            assertThat(vector.get(4).positions().getFirst().payload()).isEmpty();
            assertThat(index.documents().orElseThrow().termVector(0, "tag")).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"lucene6", "lucene7"})
    void readsBinaryTermsAndPostingsWithDeletedDocuments(String name) throws IOException {
        try (RuntimeIndex index = open(name)) {
            Terms terms = index.terms().orElseThrow();
            if (name.equals("lucene6")) {
                // A LegacyIntField's terms are trie-encoded: a shift byte from 0x60, then 7 bits per byte.
                Terms.TermsPage price = terms.terms("price", Optional.empty(), 2);
                assertThat(price.terms()).hasSize(2);
                assertThat(price.terms().getFirst().term()[0]).isEqualTo((byte) 0x60);
                Terms.TermsPage rest = terms.terms("price", price.next(), 1000);
                assertThat(rest.terms().getFirst().term())
                        .isEqualTo(price.next().orElseThrow());
                assertThat(rest.next()).isEmpty();
            }

            Terms.TermsPage ids = terms.terms("id", Optional.of(utf8("3")), 2);
            int threes = name.equals("lucene7") ? 2 : 1;
            assertThat(ids.terms())
                    .extracting(term -> text(term.term()), Terms.TermEntry::docFreq, Terms.TermEntry::totalTermFreq)
                    .containsExactly(tuple("3", threes, (long) threes), tuple("4", 1, 1L));
            assertThat(ids.next().map(Lucene7IndexTest::text)).contains("5");
            assertThat(terms.terms("id", Optional.of(utf8("9")), 2).terms()).isEmpty();
            assertThat(terms.terms("nothing", Optional.empty(), 2).terms()).isEmpty();

            Terms.PostingsPage alpha = terms.postings("body", utf8("alpha"), 0, 3);
            int docs = name.equals("lucene7") ? 8 : 7;
            assertThat(alpha.docFreq()).isEqualTo(docs);
            assertThat(alpha.totalTermFreq()).isEqualTo(docs);
            assertThat(alpha.postings())
                    .containsExactly(
                            new Terms.Posting(0, 1, false),
                            new Terms.Posting(1, 1, true),
                            new Terms.Posting(2, 1, false));
            assertThat(alpha.nextDoc()).hasValue(3);
            assertThat(terms.postings("body", utf8("alpha"), 6, 3).postings())
                    .extracting(Terms.Posting::docId)
                    .containsExactlyElementsOf(docs == 8 ? List.of(6, 7) : List.of(6));
            // Docs only: totalTermFreq is docFreq, as in Lucene 10.
            Terms.PostingsPage odd = terms.postings("tag", utf8("odd"), 0, 10);
            assertThat(odd.totalTermFreq()).isEqualTo(odd.docFreq()).isEqualTo(docs == 8 ? 5 : 4);
            assertThat(terms.postings("body", utf8("missing"), 0, 10).docFreq()).isZero();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"lucene6", "lucene7"})
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
                    .satisfies(alpha -> assertThat(alpha.payload().map(Lucene7IndexTest::text))
                            .contains("a5"));
            // Deleted documents have their positions too.
            assertThat(terms.positions(1, "body", utf8("alpha"))).isPresent();
            assertThat(terms.positions(1, "body", utf8("gamma"))).isEmpty();
            assertThat(terms.positions(0, "tag", utf8("odd"))).hasValue(List.of());
            assertThat(terms.positions(0, "nothing", utf8("odd"))).isEmpty();
            assertThatThrownBy(() -> terms.positions(8, "body", utf8("alpha")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"lucene6", "lucene7"})
    void findsTheTopTerms(String name) throws IOException {
        try (RuntimeIndex index = open(name)) {
            List<Long> progress = new ArrayList<>();
            Terms.FieldTerms terms = index.terms().orElseThrow().topTerms("body", 3, progress::add);
            // alpha, beta, document, gamma and 1 to 7, the deleted document's too.
            assertThat(terms.uniqueTerms()).isEqualTo(11);
            assertThat(progress).last().isEqualTo(11L);
            int docs = name.equals("lucene7") ? 8 : 7;
            int betas = name.equals("lucene7") ? 5 : 4;
            assertThat(terms.topTerms())
                    .extracting(term -> text(term.term()), Terms.TermEntry::docFreq, Terms.TermEntry::totalTermFreq)
                    .containsExactly(
                            tuple("alpha", docs, (long) docs),
                            tuple("document", docs, (long) docs),
                            tuple("beta", betas, (long) betas));
            assertThat(index.terms().orElseThrow().topTerms("tag", 0, _ -> {}))
                    .isEqualTo(new Terms.FieldTerms(2, List.of()));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"lucene6", "lucene7"})
    void searches(String name) throws Exception {
        try (RuntimeIndex index = open(name)) {
            Search search = index.search().orElseThrow();
            Search.ParsedQuery query = search.parse("documents AND (beta OR gamma)", BODY, named("EnglishAnalyzer"));
            assertThat(query).hasToString("+body:document +(body:beta body:gamma)");
            Search.SearchResults results = search.search(query, 0, 10, Optional.of(List.of("id")), _ -> {});
            boolean seven = name.equals("lucene7");
            assertThat(results.totalHits()).isEqualTo(seven ? 5 : 4);
            assertThat(results.hits().getFirst().docId()).isZero();
            assertThat(results.hits())
                    .extracting(Search.Hit::docId)
                    .containsExactlyInAnyOrderElementsOf(seven ? List.of(0, 2, 4, 6, 7) : List.of(0, 2, 4, 6));
            assertThat(results.hits().getFirst().fields()).containsOnlyKeys("id");
            assertThatThrownBy(() -> search.parse("body:(", BODY, named("StandardAnalyzer")))
                    .isInstanceOf(Search.InvalidQueryException.class);
        }
    }

    @Test
    void matchesAQuery() throws Exception {
        try (RuntimeIndex index = open("lucene7")) {
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
    @ValueSource(strings = {"lucene6", "lucene7"})
    void searchesATerm(String name) throws Exception {
        try (RuntimeIndex index = open(name)) {
            for (Terms.TermEntry term : index.terms()
                    .orElseThrow()
                    .terms("body", Optional.empty(), 100)
                    .terms()) {
                searchesTheTerm(index, "body", term.term());
            }
            // A trie-encoded numeric field's terms are bytes, which aren't text; 7.x has points instead.
            for (Terms.TermEntry price : index.terms()
                    .orElseThrow()
                    .terms("price", Optional.empty(), 1)
                    .terms()) {
                searchesTheTerm(index, "price", price.term());
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
            // Lucene 7.7's BM25 explanation computes the score in another order than its scorer.
            assertThat(explanation.value().floatValue()).isCloseTo(hit.score(), withinPercentage(0.001));
        }
    }

    @Test
    void explainsAQuery() throws Exception {
        try (RuntimeIndex index = open("lucene7")) {
            Search search = index.search().orElseThrow();
            Search.ParsedQuery alpha = search.parse("alpha", BODY, named("StandardAnalyzer"));
            Search.Hit hit =
                    search.search(alpha, 0, 1, Optional.empty(), _ -> {}).hits().getFirst();
            List<Long> progress = new ArrayList<>();
            Search.Explanation explanation = search.explain(alpha, hit.docId(), progress::add);
            assertThat(explanation.match()).isTrue();
            // BM25 explains in another order than it scores: the last bits may differ.
            assertThat(explanation.value().floatValue()).isCloseTo(hit.score(), withinPercentage(0.001));
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

    private static final Scoring.Similarity BM25 = new Scoring.Similarity.Bm25(1.2f, 0.75f);

    /** The document ids of a search for {@code alpha}, which every live document has, by {@code sort}. */
    private static Search.SearchResults sorted(RuntimeIndex index, Scoring.SortKey... sort) throws Exception {
        Search.ParsedQuery alpha = index.search().orElseThrow().parse("alpha", BODY, named("StandardAnalyzer"));
        return index.scoring()
                .orElseThrow()
                .search(alpha, BM25, List.of(sort), 0, 10, Optional.of(List.of("id")), _ -> {});
    }

    private static Scoring.SortKey.Field field(
            String field, Scoring.SortType type, boolean reverse, Scoring.SortSelector selector) {
        return new Scoring.SortKey.Field(field, type, reverse, selector, Scoring.SortMissing.DEFAULT);
    }

    private static List<Integer> docIds(Search.SearchResults results) {
        return results.hits().stream().map(Search.Hit::docId).toList();
    }

    /** The number each hit was sorted by in its first key; null where it has none. */
    private static List<Number> numbers(Search.SearchResults results) {
        return results.hits().stream()
                .map(hit -> hit.sortValues().getFirst().number().orElse(null))
                .toList();
    }

    /**
     * {@code dv_num}: documents 2 and 7 (id 3, the old one soft-deleted but live, and the new one) have none, document 3 (id 4) has 400, and
     * document 1 is deleted.
     */
    @Test
    void sortsByNumericDocValues() throws Exception {
        try (RuntimeIndex index = open("lucene7")) {
            Search.SearchResults byDefault =
                    sorted(index, field("dv_num", Scoring.SortType.INT, false, Scoring.SortSelector.MIN));
            // Lucene's default puts a missing value as 0; the hits still say it is missing.
            assertThat(docIds(byDefault)).containsExactly(2, 7, 0, 4, 5, 6, 3);
            assertThat(numbers(byDefault)).containsExactly(null, null, 1, 5, 6, 7, 400);
            assertThat(byDefault.hits())
                    .allSatisfy(hit -> assertThat(hit.score()).isPositive());
            assertThat(byDefault.totalHits()).isEqualTo(7);

            Scoring.SortKey.Field last = new Scoring.SortKey.Field(
                    "dv_num", Scoring.SortType.LONG, false, Scoring.SortSelector.MIN, Scoring.SortMissing.LAST);
            assertThat(docIds(sorted(index, last))).containsExactly(0, 4, 5, 6, 3, 2, 7);
            Scoring.SortKey.Field reversedLast = new Scoring.SortKey.Field(
                    "dv_num", Scoring.SortType.LONG, true, Scoring.SortSelector.MIN, Scoring.SortMissing.LAST);
            Search.SearchResults reversed = sorted(index, reversedLast);
            assertThat(docIds(reversed)).containsExactly(3, 6, 5, 4, 0, 2, 7);
            assertThat(numbers(reversed)).containsExactly(400L, 7L, 6L, 5L, 1L, null, null);
        }
    }

    @Test
    void sortsBySortedNumericAndSortedSetDocValues() throws Exception {
        try (RuntimeIndex index = open("lucene7")) {
            Search.SearchResults max =
                    sorted(index, field("dv_sorted_num", Scoring.SortType.LONG, false, Scoring.SortSelector.MAX));
            assertThat(docIds(max)).containsExactly(0, 2, 7, 3, 4, 5, 6);
            assertThat(numbers(max)).containsExactly(1L, 3L, 3L, 4L, 5L, 6L, 7L);
            Search.SearchResults min =
                    sorted(index, field("dv_sorted_num", Scoring.SortType.LONG, false, Scoring.SortSelector.MIN));
            assertThat(numbers(min)).containsExactly(-7L, -6L, -5L, -4L, -3L, -3L, -1L);

            Search.SearchResults set =
                    sorted(index, field("dv_set", Scoring.SortType.STRING, false, Scoring.SortSelector.MIDDLE_MAX));
            assertThat(docIds(set)).containsExactly(0, 2, 7, 3, 4, 5, 6);
            assertThat(set.hits())
                    .extracting(hit -> text(hit.sortValues().getFirst().term().orElseThrow()))
                    .containsExactly("y1", "y3", "y3", "y4", "y5", "y6", "y7");

            assertThatThrownBy(() ->
                            sorted(index, field("dv_bin", Scoring.SortType.STRING, false, Scoring.SortSelector.MIN)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("the field 'dv_bin' has BINARY doc values, which can't be sorted by");
            assertThatThrownBy(
                            () -> sorted(index, field("dv_num", Scoring.SortType.INT, false, Scoring.SortSelector.MAX)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("the field 'dv_num' has single-valued NUMERIC doc values, which can't select MAX");
        }
    }

    @Test
    void sortsByDocAndScore() throws Exception {
        try (RuntimeIndex index = open("lucene7")) {
            assertThat(docIds(sorted(index, new Scoring.SortKey.Doc(true)))).containsExactly(7, 6, 5, 4, 3, 2, 0);
            Search.SearchResults docs = sorted(index, new Scoring.SortKey.Doc(false));
            assertThat(numbers(docs)).containsExactly(0, 2, 3, 4, 5, 6, 7);

            Search.SearchResults lowest = sorted(index, new Scoring.SortKey.Score(true));
            List<Float> scores = lowest.hits().stream().map(Search.Hit::score).toList();
            assertThat(scores).isSorted();
            assertThat(scores.getFirst()).isLessThan(scores.getLast());
            assertThat(numbers(lowest)).containsExactlyElementsOf(scores);

            CancellationException stop = new CancellationException("stop");
            Search.ParsedQuery alpha = index.search().orElseThrow().parse("alpha", BODY, named("StandardAnalyzer"));
            assertThatThrownBy(() -> index.scoring()
                            .orElseThrow()
                            .search(
                                    alpha,
                                    BM25,
                                    List.of(new Scoring.SortKey.Doc(false)),
                                    0,
                                    10,
                                    Optional.empty(),
                                    _ -> {
                                        throw stop;
                                    }))
                    .isSameAs(stop);
        }
    }

    @Test
    void scoresWithTheSimilarityChosen() throws Exception {
        try (RuntimeIndex index = open("lucene7")) {
            Search search = index.search().orElseThrow();
            Scoring scoring = index.scoring().orElseThrow();
            Search.ParsedQuery query = search.parse("beta^2 gamma", BODY, named("StandardAnalyzer"));
            // 7.7's default, BM25
            Search.Hit byDefault =
                    search.search(query, 0, 1, Optional.empty(), _ -> {}).hits().getFirst();
            assertThat(scoring.search(query, BM25, List.of(), 0, 1, Optional.empty(), _ -> {})
                            .hits())
                    .singleElement()
                    .satisfies(hit -> {
                        assertThat(hit.docId()).isEqualTo(byDefault.docId());
                        assertThat(hit.score()).isEqualTo(byDefault.score());
                        assertThat(hit.sortValues()).isEmpty();
                    });
            for (Scoring.Similarity similarity :
                    List.of(new Scoring.Similarity.Classic(), new Scoring.Similarity.Bool())) {
                Search.Hit hit = scoring.search(query, similarity, List.of(), 0, 1, Optional.empty(), _ -> {})
                        .hits()
                        .getFirst();
                assertThat(hit.score()).as("%s", similarity).isNotEqualTo(byDefault.score());
                assertThat(scoring.explain(query, similarity, hit.docId(), _ -> {})
                                .value()
                                .floatValue())
                        .as("%s", similarity)
                        .isEqualTo(hit.score());
            }
            // Boolean: beta's boost 2 and gamma's 1.
            assertThat(scoring.search(query, new Scoring.Similarity.Bool(), List.of(), 0, 1, Optional.empty(), _ -> {})
                            .hits()
                            .getFirst()
                            .score())
                    .isEqualTo(3.0f);
        }
    }

    /**
     * {@code lucene6} is sorted by {@code order}: a sort by it still counts every hit, where Lucene
     * would end the search early without (ADR 0076).
     */
    @Test
    void countsEveryHitOfTheIndexSort() throws Exception {
        try (RuntimeIndex index = open("lucene6")) {
            Search.ParsedQuery alpha = index.search().orElseThrow().parse("alpha", BODY, named("StandardAnalyzer"));
            Search.SearchResults first = index.scoring()
                    .orElseThrow()
                    .search(
                            alpha,
                            BM25,
                            List.of(field("order", Scoring.SortType.LONG, false, Scoring.SortSelector.MIN)),
                            0,
                            2,
                            Optional.of(List.of("id")),
                            _ -> {});
            assertThat(first.totalHits()).isEqualTo(6);
            assertThat(first.totalHitsExact()).isTrue();
            assertThat(numbers(first)).containsExactly(1L, 3L);
        }
    }

    private static Search.AnalyzerSpec named(String name) {
        return new Search.AnalyzerSpec.Preset(name);
    }

    @Test
    void dropsTheAutoStopWordsAfterTheWholeChain() throws Exception {
        try (RuntimeIndex index = open("lucene7")) {
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
    void parsesWithTheAnalyzersOf77() throws Exception {
        try (RuntimeIndex index = open("lucene7")) {
            Search search = index.search().orElseThrow();
            assertThat(search.parse("Alpha the", BODY, named("StandardAnalyzer")))
                    .hasToString("body:alpha");
            assertThat(search.parse("Häuser", BODY, named("GermanAnalyzer"))).hasToString("body:haus");
            assertThat(search.parse(
                            "Alpha beta",
                            BODY,
                            new Search.AnalyzerSpec.Preset(
                                    "EnglishAnalyzer",
                                    Optional.of(new Search.AnalyzerSpec.WordSet.Words(List.of("alpha"))),
                                    OptionalInt.empty())))
                    .hasToString("body:beta");
            assertThat(search.parse(
                            "abcdef",
                            BODY,
                            new Search.AnalyzerSpec.Preset("StandardAnalyzer", Optional.empty(), OptionalInt.of(3))))
                    .hasToString("body:abc body:def");
            assertThat(search.parse(
                            "\"Alpha beta\" id:\"A b\"",
                            BODY,
                            new Search.AnalyzerSpec.PerField(
                                    new Search.AnalyzerSpec.Wrapped(
                                            named("SimpleAnalyzer"),
                                            Optional.empty(),
                                            Optional.of(new Search.AnalyzerSpec.LimitTokenCount(1, false))),
                                    Map.of("id", named("KeywordAnalyzer")))))
                    .hasToString("body:alpha id:A b");
            assertThatThrownBy(() -> search.parse("alpha", BODY, named("SmartChineseAnalyzer")))
                    .isInstanceOf(Search.UnsupportedAnalyzerException.class)
                    .hasMessageContaining("SmartChineseAnalyzer")
                    .hasMessageContaining("ArabicAnalyzer, ArmenianAnalyzer");
            assertThatThrownBy(() -> search.parse(
                            "alpha",
                            BODY,
                            new Search.AnalyzerSpec.Preset(
                                    "WhitespaceAnalyzer",
                                    Optional.of(new Search.AnalyzerSpec.WordSet.Words(List.of("a"))),
                                    OptionalInt.empty())))
                    .isInstanceOf(Search.InvalidAnalyzerException.class)
                    .hasMessage("WhitespaceAnalyzer takes no stop words");
            assertThatThrownBy(() -> search.parse(
                            "alpha",
                            BODY,
                            new Search.AnalyzerSpec.Preset("EnglishAnalyzer", Optional.empty(), OptionalInt.of(3))))
                    .isInstanceOf(Search.InvalidAnalyzerException.class)
                    .hasMessage("EnglishAnalyzer takes no max token length");
            // Its StopAnalyzer has default stop words, unlike 9.12's (ADR 0075).
            assertThat(search.parse("Alpha the", BODY, named("StopAnalyzer"))).hasToString("body:alpha");
            assertThat(search.parse(
                            "Alpha BETA",
                            BODY,
                            new Search.AnalyzerSpec.Custom(
                                    List.of(),
                                    new Search.AnalyzerSpec.Component("whitespace", Map.of()),
                                    List.of(new Search.AnalyzerSpec.Component("lowercase", Map.of())),
                                    Optional.empty())))
                    .hasToString("body:alpha body:beta");
            assertThat(search.parse(
                            "\"alpha beta gamma\"",
                            BODY,
                            new Search.AnalyzerSpec.Wrapped(
                                    named("StandardAnalyzer"),
                                    Optional.of(new Search.AnalyzerSpec.Shingles(2, 2, "_", false, false, "-")),
                                    Optional.empty())))
                    .hasToString("body:\"alpha_beta beta_gamma\"");
            Search.AnalyzerSpec collation = new Search.AnalyzerSpec.Preset(
                    "CollationKeyAnalyzer",
                    Optional.empty(),
                    Optional.empty(),
                    OptionalInt.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(new Search.AnalyzerSpec.Collation(
                            "de", Optional.of(Search.AnalyzerSpec.CollationStrength.PRIMARY), Optional.empty())),
                    Optional.empty());
            assertThat(search.parse("Alpha", BODY, collation).toString())
                    .isEqualTo(search.parse("alpha", BODY, collation).toString());
            assertThat(search.parse(
                            "alpha",
                            BODY,
                            new Search.AnalyzerSpec.Preset(
                                    "DutchAnalyzer",
                                    Optional.empty(),
                                    Optional.empty(),
                                    OptionalInt.empty(),
                                    Optional.of(
                                            new Search.AnalyzerSpec.StemDictionary.Entries(Map.of("alpha", "gamma"))),
                                    Optional.empty(),
                                    Optional.empty(),
                                    Optional.empty(),
                                    Optional.empty())))
                    .hasToString("body:gamma");
            assertThatThrownBy(() -> search.parse(
                            "alpha",
                            BODY,
                            new Search.AnalyzerSpec.Custom(
                                    List.of(),
                                    new Search.AnalyzerSpec.Component("standard", Map.of()),
                                    List.of(new Search.AnalyzerSpec.Component("romanianNormalization", Map.of())),
                                    Optional.empty())))
                    .isInstanceOf(Search.UnsupportedAnalyzerException.class)
                    .hasMessage("Lucene 7.7.3 has no token filter 'romanianNormalization'");
        }
    }

    /**
     * Rebuilds every document, deleted ones included, in every field with postings or points, and
     * checks it against what the term vectors, postings, positions and point leaves say.
     */
    @ParameterizedTest
    @ValueSource(strings = {"lucene6", "lucene7"})
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
        Path lucene6 = testIndex("lucene6");
        Path lucene7 = testIndex("lucene7");
        try (RuntimeIndex index = new Lucene7Runtime().open(List.of(lucene6, empty, lucene7), DirectoryKind.NIO)) {
            RuntimeIndex.Stats stats = index.stats();
            assertThat(stats.maxDoc()).isEqualTo(15);
            assertThat(stats.numDocs()).isEqualTo(13);
            assertThat(stats.indexes())
                    .extracting(
                            RuntimeIndex.IndexStats::path,
                            RuntimeIndex.IndexStats::docBase,
                            RuntimeIndex.IndexStats::createdMajor)
                    .containsExactly(tuple(lucene6, 0, 6), tuple(lucene7, 7, 7));
            assertThat(index.skipped()).extracting(RuntimeIndex.Skipped::path).containsExactly(empty);
            assertThat(index.fields().get(1).terms()).contains(new RuntimeIndex.TermStats(15, 56, 58));
            Documents.IndexDocument doc = index.documents().orElseThrow().document(10);
            assertThat(field(doc, "dv_num").docValues().orElseThrow().numbers()).containsExactly(400L);
            assertThat(index.terms()
                            .orElseThrow()
                            .postings("id", utf8("2"), 0, 10)
                            .postings())
                    .containsExactly(new Terms.Posting(1, 1, true), new Terms.Posting(8, 1, true));
            assertThat(index.points().orElseThrow().tree("pt").segments())
                    .extracting(Points.Segment::docBase)
                    .containsExactly(0, 4, 7, 11);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"lucene6", "lucene7"})
    void readsTheCommits(String name) throws IOException {
        boolean seven = name.equals("lucene7");
        String version = seven ? "7.7.3" : "6.6.6";
        Commits commits = new Lucene7Runtime().commits().orElseThrow();
        Commits.CommitList list = commits.commits(testIndex(name), DirectoryKind.FS);
        assertThat(list.unreadable()).isEmpty();
        assertThat(list.commits()).extracting(Commits.Commit::segmentsFile).containsExactly("segments_2", "segments_1");
        Commits.Commit second = list.commits().getFirst();
        assertThat(second.segmentCount()).isEqualTo(2);
        assertThat(second.maxDoc()).isEqualTo(seven ? 8 : 7);
        // Hard deletes only.
        assertThat(second.delCount()).isEqualTo(1);
        assertThat(second.luceneVersion()).isEqualTo(version);
        assertThat(second.userData()).containsEntry("commit", "second");
        assertThat(list.commits().get(1).maxDoc()).isEqualTo(4);

        Commits.CommitDetails details =
                commits.commit(testIndex(name), DirectoryKind.NIO, 2).orElseThrow();
        Commits.Segment plain = details.segments().get(0);
        assertThat(plain.name()).isEqualTo("_0");
        assertThat(plain.codec()).contains(seven ? "Lucene70" : "Lucene62");
        assertThat(plain.id().orElseThrow()).hasSizeGreaterThan(20);
        assertThat(plain.compound()).isFalse();
        assertThat(plain.delCount()).isEqualTo(1);
        // The soft update of document 3; soft deletes came with 7.4.
        assertThat(plain.softDelCount()).isEqualTo(seven ? 1 : 0);
        assertThat(plain.delGen()).hasValue(1);
        assertThat(plain.fieldInfosGen()).isPresent();
        assertThat(plain.docValuesGen()).isPresent();
        assertThat(plain.luceneVersion()).isEqualTo(version);
        // Recorded since 7.0.
        assertThat(plain.minLuceneVersion()).isEqualTo(seven ? Optional.of(version) : Optional.empty());
        // Index sorts came with 6.2; only the 6.x index has one.
        assertThat(plain.indexSort()).isEqualTo(seven ? Optional.empty() : Optional.of("<long: \"order\">"));
        assertThat(plain.sizeInBytes()).isPresent();
        assertThat(plain.diagnostics()).containsEntry("source", "flush");
        assertThat(plain.formats()).hasValueSatisfying(formats -> {
            assertThat(formats.points()).contains("Lucene60PointsFormat");
            assertThat(formats.knnVectors()).isEmpty();
            assertThat(formats.storedFields()).isNotBlank();
            assertThat(formats.norms()).isEqualTo(seven ? "Lucene70NormsFormat" : "Lucene53NormsFormat");
        });
        assertThat(plain.fieldInfosError()).isEmpty();
        assertThat(plain.fieldFormats())
                .filteredOn(formats ->
                        formats.field().equals("body") || formats.field().equals("dv_num"))
                .extracting(
                        Commits.FieldFormats::field, Commits.FieldFormats::postings, Commits.FieldFormats::docValues)
                .containsExactly(
                        tuple("body", Optional.of("Lucene50"), Optional.empty()),
                        tuple("dv_num", Optional.empty(), Optional.of(seven ? "Lucene70" : "Lucene54")));
        Commits.Segment compound = details.segments().get(1);
        assertThat(compound.compound()).isTrue();
        assertThat(compound.docBase()).isEqualTo(4);
        assertThat(compound.delGen()).isEmpty();
        assertThat(compound.softDelCount()).isZero();
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
        for (String name : List.of("lucene6", "lucene7")) {
            List<String> lines = new ArrayList<>();
            Check check = new Lucene7Runtime().check().orElseThrow();
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
            } else {
                assertThat(lines).anyMatch(line -> line.contains("test: terms"));
                assertThat(lines).anyMatch(line -> line.contains("test: points"));
            }
            if (level >= 2 && name.equals("lucene6")) {
                assertThat(lines).anyMatch(line -> line.contains("test: index sort"));
            }
        }
    }

    @Test
    void checksADamagedIndexWithoutChangingIt(@TempDir Path dir) throws Exception {
        try (Stream<Path> files = Files.list(testIndex("lucene7"))) {
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

        Check.CheckResult result = new Lucene7Runtime()
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
        assertThatThrownBy(() -> new Lucene7Runtime()
                        .check()
                        .orElseThrow()
                        .check(testIndex("lucene7"), DirectoryKind.FS, OptionalInt.of(3), _ -> {}, _ -> {
                            if (calls.incrementAndGet() > 3) {
                                throw stop;
                            }
                        }))
                .isSameAs(stop);
    }

    @ParameterizedTest
    @ValueSource(strings = {"lucene6:segments_2", "lucene7:segments_2"})
    void reopensAChangedIndex(String nameAndNewest, @TempDir Path dir) throws IOException {
        String name = nameAndNewest.substring(0, nameAndNewest.indexOf(':'));
        String newest = nameAndNewest.substring(nameAndNewest.indexOf(':') + 1);
        Path index0 = testIndex(name);
        copyWithout(index0, dir, newest);
        RuntimeIndex index = new Lucene7Runtime().open(List.of(dir), DirectoryKind.FS);
        try (index;
                RuntimeIndex latest = new Lucene7Runtime().open(List.of(index0), DirectoryKind.FS)) {
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
        Path unchanged = testIndex("lucene6");
        copyWithout(testIndex("lucene7"), dir, "segments_2");
        RuntimeIndex index = new Lucene7Runtime().open(List.of(unchanged, dir), DirectoryKind.FS);
        try (index;
                RuntimeIndex latest =
                        new Lucene7Runtime().open(List.of(unchanged, testIndex("lucene7")), DirectoryKind.FS)) {
            assertThat(index.reopen()).isEmpty();
            Files.copy(testIndex("lucene7").resolve("segments_2"), dir.resolve("segments_2"));
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
        for (String name : List.of("lucene6", "lucene7")) {
            Path dir = testIndex(name);
            Map<String, String> before = hashes(dir);
            LuceneRuntime runtime = new Lucene7Runtime();
            try (RuntimeIndex index = runtime.open(List.of(dir), directoryKind)) {
                index.stats();
                index.documents().orElseThrow().document(2);
                index.documents().orElseThrow().termVector(2, "body");
                index.terms().orElseThrow().terms("body", Optional.empty(), 100);
                index.terms().orElseThrow().topTerms("body", 10, _ -> {});
                index.terms().orElseThrow().positions(2, "body", utf8("alpha"));
                Points points = index.points().orElseThrow();
                points.tree("pt");
                points.cell("pt", 0, List.of(1, 1));
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

    @Test
    void namesTheDirectoryClass() throws IOException {
        try (RuntimeIndex mmap = new Lucene7Runtime().open(List.of(testIndex("lucene7")), DirectoryKind.MMAP);
                RuntimeIndex nio = new Lucene7Runtime().open(List.of(testIndex("lucene7")), DirectoryKind.NIO)) {
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
        try (RuntimeIndex index = open("lucene7")) {
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
        try (RuntimeIndex index = open("lucene7")) {
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
        try (RuntimeIndex index = open("lucene7")) {
            Search search = index.search().orElseThrow();
            Search.AnalyzerSpec standard = new Search.AnalyzerSpec.Standard();

            assertThat(search.parse("wi-fi", parserOptions(2, true, true, Search.Dates.NONE), standard))
                    .hasToString("body:\"wi fi\"");
            assertThat(search.parse("wi-fi", parserOptions(2, false, false, Search.Dates.NONE), standard))
                    .hasToString("body:wi body:fi");
        }
    }

    private static final StandardSearch.Options STANDARD_BODY =
            new StandardSearch.Options("body", false, false, 0, 0, true);

    /** The live documents that {@code query} matches, by document id. */
    private static List<Integer> matched(RuntimeIndex index, Search.ParsedQuery query) throws IOException {
        return index.search().orElseThrow().matches(query, false, _ -> {}).docs().stream()
                .boxed()
                .toList();
    }

    @ParameterizedTest
    @ValueSource(strings = {"lucene6", "lucene7"})
    void parsesWithTheStandardParserAndPoints(String name) throws Exception {
        try (RuntimeIndex index = open(name)) {
            StandardSearch standard = index.standardSearch().orElseThrow();
            Search.ParsedQuery query = standard.parse(
                    "pt:[100 TO 114] OR amount:5000 OR gamma",
                    STANDARD_BODY,
                    Map.of("pt", StandardSearch.PointType.INT, "amount", StandardSearch.PointType.LONG),
                    named("StandardAnalyzer"));
            assertThat(query).hasToString("pt:[100 TO 114] amount:[5000 TO 5000] body:gamma");
            Search.SearchResults results = index.search().orElseThrow().search(query, 0, 10, Optional.empty(), _ -> {});
            // Documents 1 and 5.
            assertThat(results.hits()).extracting(Search.Hit::docId).containsExactlyInAnyOrder(0, 4);
            assertThat(results.likeTerms()).isEmpty();

            // Without its points config, the range is a term range.
            assertThat(standard.parse("pt:[100 TO 114]", STANDARD_BODY, Map.of(), named("StandardAnalyzer")))
                    .hasToString("pt:[100 TO 114]");
            assertThat(matched(
                            index,
                            standard.parse("pt:[100 TO 114]", STANDARD_BODY, Map.of(), named("StandardAnalyzer"))))
                    .isEmpty();
            // The analyzer normalizes the bounds of a term range.
            Search.ParsedQuery range =
                    standard.parse("body:[ALPHA TO ALPHA]", STANDARD_BODY, Map.of(), named("StandardAnalyzer"));
            assertThat(range).hasToString("body:[alpha TO alpha]");
            assertThat(matched(index, range)).hasSize(index.maxDoc() - 1);
            assertThatThrownBy(() -> standard.parse("body:(", STANDARD_BODY, Map.of(), named("StandardAnalyzer")))
                    .isInstanceOf(Search.InvalidQueryException.class)
                    .hasMessage("Cannot parse 'body:(': Encountered \"<EOF>\" at column 6.");
            assertThatThrownBy(() -> standard.parse(
                            "pt:abc",
                            STANDARD_BODY,
                            Map.of("pt", StandardSearch.PointType.INT),
                            named("StandardAnalyzer")))
                    .isInstanceOf(Search.InvalidQueryException.class);
            assertThatThrownBy(() -> standard.parse("x", STANDARD_BODY, Map.of(), named("NoSuchAnalyzer")))
                    .isInstanceOf(Search.UnsupportedAnalyzerException.class);
        }
    }

    private static MoreLikeThisSearch.Options like(boolean boost, String... fields) {
        return new MoreLikeThisSearch.Options(
                List.of(fields), 1, 1, OptionalInt.empty(), 25, boost, 0, OptionalInt.empty(), Set.of());
    }

    /**
     * The terms chosen are the clauses of the query that was built, in the same order: Lucene adds them
     * weakest first. They are ranked by their score.
     */
    private static void assertTheClauses(Search.SearchResults results) {
        List<MoreLikeThisSearch.LikeTerm> terms = results.likeTerms().orElseThrow();
        assertThat(terms)
                .isSortedAccordingTo(
                        Comparator.comparing(MoreLikeThisSearch.LikeTerm::score).reversed());
        assertThat(results.rewrittenQuery())
                .isEqualTo(String.join(
                        " ",
                        terms.reversed().stream()
                                .map(term -> term.field() + ":" + term.term())
                                .toList()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"lucene6", "lucene7"})
    void findsDocumentsLikeADocumentWithTheTermsChosen(String name) throws Exception {
        try (RuntimeIndex index = open(name)) {
            MoreLikeThisSearch like = index.moreLikeThisSearch().orElseThrow();
            Search search = index.search().orElseThrow();
            Search.ParsedQuery query =
                    like.like(new MoreLikeThisSearch.Source.Doc(0), like(false, "body"), named("StandardAnalyzer"));
            assertThat(query).hasToString("like(doc 0, fields [body], minTermFreq 1, minDocFreq 1, maxQueryTerms 25)");
            List<Long> progress = new ArrayList<>();
            Search.SearchResults results = search.search(query, 0, 10, Optional.empty(), progress::add);
            assertThat(results.query()).isEqualTo(query.toString());
            assertThat(results.hits().getFirst().docId()).isZero();
            assertThat(progress).last().isEqualTo((long) index.maxDoc());
            assertTheClauses(results);
            // From the term vector of document 1: gamma twice, the rarest, ranks first.
            List<MoreLikeThisSearch.LikeTerm> terms = results.likeTerms().orElseThrow();
            assertThat(terms).extracting(MoreLikeThisSearch.LikeTerm::term).contains("gamma", "beta", "alpha");
            MoreLikeThisSearch.LikeTerm gamma = terms.getFirst();
            assertThat(gamma.field()).isEqualTo("body");
            assertThat(gamma.term()).isEqualTo("gamma");
            assertThat(gamma.tf()).isEqualTo(2);
            assertThat(gamma.docFreq()).isEqualTo(1);
            // 7.7's idf is over the live documents.
            assertThat(gamma.idf())
                    .isEqualTo(new ClassicSimilarity().idf(1, index.stats().numDocs()));
            assertThat(gamma.score()).isEqualTo(gamma.tf() * gamma.idf());

            // Matches and explanations build the query too.
            assertThat(matched(index, query)).hasSize(index.maxDoc() - 1);
            Search.Explanation explanation =
                    search.explain(query, results.hits().getFirst().docId(), _ -> {});
            assertThat(explanation.match()).isTrue();
            assertThat(explanation.value().floatValue())
                    .isCloseTo(results.hits().getFirst().score(), withinPercentage(0.001));

            assertThatThrownBy(() -> like.like(
                            new MoreLikeThisSearch.Source.Doc(0), like(false, "body"), named("NoSuchAnalyzer")))
                    .isInstanceOf(Search.UnsupportedAnalyzerException.class);
        }
    }

    /** On 7.7, each field counts its own terms, as on 9.12. */
    @ParameterizedTest
    @ValueSource(strings = {"lucene6", "lucene7"})
    void countsTheTermsOfEachField(String name) throws Exception {
        try (RuntimeIndex index = open(name)) {
            MoreLikeThisSearch like = index.moreLikeThisSearch().orElseThrow();
            Search search = index.search().orElseThrow();
            // The id 1 has no term vector: its stored value is analyzed.
            Search.SearchResults byDoc = search.search(
                    like.like(
                            new MoreLikeThisSearch.Source.Doc(0), like(false, "id", "body"), named("StandardAnalyzer")),
                    0,
                    10,
                    Optional.empty(),
                    _ -> {});
            assertTheClauses(byDoc);
            assertThat(byDoc.likeTerms().orElseThrow())
                    .extracting(
                            MoreLikeThisSearch.LikeTerm::field,
                            MoreLikeThisSearch.LikeTerm::term,
                            MoreLikeThisSearch.LikeTerm::tf,
                            MoreLikeThisSearch.LikeTerm::docFreq)
                    .contains(tuple("id", "1", 1, 1), tuple("body", "1", 1, 1), tuple("body", "gamma", 2, 1));
            // Each field analyzes the text once; id has no gamma.
            Search.SearchResults byText = search.search(
                    like.like(
                            new MoreLikeThisSearch.Source.Text("gamma"),
                            like(false, "body", "id"),
                            named("StandardAnalyzer")),
                    0,
                    10,
                    Optional.empty(),
                    _ -> {});
            assertTheClauses(byText);
            assertThat(byText.likeTerms().orElseThrow())
                    .extracting(
                            MoreLikeThisSearch.LikeTerm::field,
                            MoreLikeThisSearch.LikeTerm::term,
                            MoreLikeThisSearch.LikeTerm::tf)
                    .containsExactly(tuple("body", "gamma", 1));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"lucene6", "lucene7"})
    void findsDocumentsLikeAText(String name) throws Exception {
        try (RuntimeIndex index = open(name)) {
            Search.ParsedQuery query = index.moreLikeThisSearch()
                    .orElseThrow()
                    .like(
                            new MoreLikeThisSearch.Source.Text("gamma \"x\""),
                            like(true, "body"),
                            named("StandardAnalyzer"));
            assertThat(query)
                    .hasToString("like(text \"gamma \\\"x\\\"\", fields [body], minTermFreq 1, minDocFreq 1,"
                            + " maxQueryTerms 25, boost)");
            Search.SearchResults results = index.search().orElseThrow().search(query, 0, 10, Optional.empty(), _ -> {});
            assertThat(results.hits()).extracting(Search.Hit::docId).containsExactly(0);
            assertTheClauses(results);
            assertThat(results.likeTerms().orElseThrow())
                    .extracting(MoreLikeThisSearch.LikeTerm::term, MoreLikeThisSearch.LikeTerm::tf)
                    .containsExactly(tuple("gamma", 1));
        }
    }

    private static DocValuesSearch.Match<String> numbers(String... values) {
        return new DocValuesSearch.Match.Values<>(List.of(values));
    }

    private static <T> DocValuesSearch.Match<T> range(T lower, T upper) {
        return new DocValuesSearch.Match.Range<>(Optional.ofNullable(lower), Optional.ofNullable(upper), true, true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"lucene6", "lucene7"})
    void searchesNumberDocValues(String name) throws Exception {
        try (RuntimeIndex index = open(name)) {
            DocValuesSearch docValues = index.docValuesSearch().orElseThrow();
            DocValuesSearch.NumberEncoding longs = DocValuesSearch.NumberEncoding.LONG;
            // dv_num is i, but 400 for document 4 (3 of the index), and none for document 3.
            assertThat(matched(index, docValues.numbers("dv_num", longs, numbers("5", "400", "5"))))
                    .containsExactly(3, 4);
            assertThat(matched(index, docValues.numbers("dv_num", longs, numbers("400"))))
                    .containsExactly(3);
            assertThat(matched(index, docValues.numbers("dv_num", longs, numbers("5", "6"))))
                    .containsExactly(4, 5);
            assertThat(matched(index, docValues.numbers("dv_num", longs, range("2", "6"))))
                    .containsExactly(4, 5);
            assertThat(matched(
                            index,
                            docValues.numbers(
                                    "dv_num",
                                    longs,
                                    new DocValuesSearch.Match.Range<>(
                                            Optional.of("5"), Optional.empty(), false, true))))
                    .containsExactly(3, 5, 6);
            // As raw double bits, every small long is a tiny positive double.
            assertThat(matched(
                            index, docValues.numbers("dv_num", DocValuesSearch.NumberEncoding.DOUBLE, range("0", "1"))))
                    .containsExactly(0, 3, 4, 5, 6);
            assertThat(matched(
                            index,
                            docValues.numbers("dv_num", DocValuesSearch.NumberEncoding.DOUBLE, range(null, "-1"))))
                    .isEmpty();
            // dv_sorted_num has -i and i.
            assertThat(matched(index, docValues.numbers("dv_sorted_num", longs, numbers("-5"))))
                    .containsExactly(4);
            assertThat(matched(index, docValues.numbers("dv_sorted_num", longs, numbers("-5", "7"))))
                    .containsExactly(4, 6);
            assertThat(matched(index, docValues.numbers("dv_sorted_num", longs, range("6", null))))
                    .containsExactly(5, 6);

            Search.ParsedQuery query = docValues.numbers("dv_num", longs, numbers("5"));
            assertThat(query).hasToString("dv_num:[5 TO 5]");
            Search.Hit hit = index.search()
                    .orElseThrow()
                    .search(query, 0, 1, Optional.empty(), _ -> {})
                    .hits()
                    .getFirst();
            Search.Explanation explanation = index.search().orElseThrow().explain(query, hit.docId(), _ -> {});
            assertThat(explanation.match()).isTrue();
            assertThat(explanation.value().floatValue()).isEqualTo(hit.score());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"lucene6", "lucene7"})
    void searchesByteDocValues(String name) throws Exception {
        boolean seven = name.equals("lucene7");
        try (RuntimeIndex index = open(name)) {
            DocValuesSearch docValues = index.docValuesSearch().orElseThrow();
            // dv_sorted is s<i % 3>: documents 1, 4 and 7 have s1, 3 and 6 s0, and so has the soft
            // update of 3 on 7.x (7 of the index).
            assertThat(matched(
                            index,
                            docValues.bytes("dv_sorted", new DocValuesSearch.Match.Values<>(List.of(utf8("s1"))))))
                    .containsExactly(0, 3, 6);
            assertThat(matched(
                            index,
                            docValues.bytes(
                                    "dv_sorted", new DocValuesSearch.Match.Values<>(List.of(utf8("s1"), utf8("s0"))))))
                    .containsExactlyElementsOf(seven ? List.of(0, 2, 3, 5, 6, 7) : List.of(0, 2, 3, 5, 6));
            assertThat(matched(index, docValues.bytes("dv_sorted", range(utf8("s2"), null))))
                    .containsExactly(4);
            // dv_set has x and y<i>.
            assertThat(matched(
                            index, docValues.bytes("dv_set", new DocValuesSearch.Match.Values<>(List.of(utf8("y5"))))))
                    .containsExactly(4);
            assertThat(matched(index, docValues.prefix("dv_set", utf8("y")))).hasSize(index.maxDoc() - 1);
            assertThat(matched(index, docValues.prefix("dv_set", new byte[0]))).hasSize(index.maxDoc() - 1);
            assertThat(matched(index, docValues.prefix("dv_sorted", utf8("s0"))))
                    .containsExactlyElementsOf(seven ? List.of(2, 5, 7) : List.of(2, 5));
            // dv_bin is b<i>.
            assertThat(matched(index, docValues.bytes("dv_bin", range(utf8("b5"), utf8("b7")))))
                    .containsExactly(4, 5, 6);
            assertThat(matched(
                            index, docValues.bytes("dv_bin", new DocValuesSearch.Match.Values<>(List.of(utf8("b4"))))))
                    .containsExactly(3);
            assertThat(matched(
                            index,
                            docValues.bytes(
                                    "dv_bin", new DocValuesSearch.Match.Values<>(List.of(utf8("b1"), utf8("b3"))))))
                    .containsExactlyElementsOf(seven ? List.of(0, 2, 7) : List.of(0, 2));
            assertThat(matched(index, docValues.prefix("dv_bin", utf8("b")))).hasSize(index.maxDoc() - 1);
            Search.ParsedQuery binary = docValues.bytes("dv_bin", range(utf8("b5"), utf8("b7")));
            assertThat(binary).hasToString("dv_bin:[[62 35] TO [62 37]]");
            Search.Hit hit = index.search()
                    .orElseThrow()
                    .search(binary, 0, 1, Optional.empty(), _ -> {})
                    .hits()
                    .getFirst();
            assertThat(index.search()
                            .orElseThrow()
                            .explain(binary, hit.docId(), _ -> {})
                            .match())
                    .isTrue();
        }
    }

    @Test
    void refusesDocValuesOfTheWrongType() throws Exception {
        try (RuntimeIndex index = open("lucene7")) {
            DocValuesSearch docValues = index.docValuesSearch().orElseThrow();
            DocValuesSearch.NumberEncoding longs = DocValuesSearch.NumberEncoding.LONG;
            assertThatThrownBy(() -> docValues.numbers("dv_bin", longs, numbers("1")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("the field 'dv_bin' has BINARY doc values, which hold bytes, not numbers");
            assertThatThrownBy(() -> docValues.numbers("body", longs, numbers("1")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("the field 'body' has no doc values");
            assertThatThrownBy(() -> docValues.bytes("dv_num", new DocValuesSearch.Match.Values<>(List.of(utf8("1")))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage(
                            "the field 'dv_num' has NUMERIC doc values, which hold numbers: say how they are encoded");
            assertThatThrownBy(() -> docValues.prefix("body", utf8("a")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("the field 'body' has no doc values");
            assertThatThrownBy(() -> docValues.numbers("dv_num", longs, numbers("1.5")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("'1.5' is not a LONG");
            assertThatThrownBy(() -> docValues.numbers("dv_num", DocValuesSearch.NumberEncoding.FLOAT, numbers("x")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("'x' is not a FLOAT");
            assertThatThrownBy(() -> docValues.numbers("dv_num", DocValuesSearch.NumberEncoding.DOUBLE, numbers("NaN")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("NaN can't be searched for");
            assertThatThrownBy(() -> docValues.numbers("nothing", longs, numbers("1")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("No field 'nothing' in the index");
        }
    }
}
