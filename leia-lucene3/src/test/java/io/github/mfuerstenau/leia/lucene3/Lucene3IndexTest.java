package io.github.mfuerstenau.leia.lucene3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mfuerstenau.leia.runtime.DirectoryKind;
import io.github.mfuerstenau.leia.runtime.Documents;
import io.github.mfuerstenau.leia.runtime.LuceneRuntime;
import io.github.mfuerstenau.leia.runtime.RuntimeIndex;
import io.github.mfuerstenau.leia.runtime.Search;
import io.github.mfuerstenau.leia.runtime.Terms;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Reads the Lucene 2.4 index that the build writes (build.gradle.kts) with plain Lucene 3.6, through the runtime interface. */
class Lucene3IndexTest {

    private static Path lucene24() {
        String dir = System.getProperty("leia.test.index.lucene24");
        if (dir == null) {
            throw new IllegalStateException("System property leia.test.index.lucene24 is not set");
        }
        return Path.of(dir).toAbsolutePath();
    }

    private static RuntimeIndex open(DirectoryKind kind) throws IOException {
        return new Lucene3Runtime().open(lucene24(), kind);
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
    void readsTheStats() throws IOException {
        try (RuntimeIndex index = open(DirectoryKind.NIO)) {
            RuntimeIndex.Stats stats = index.stats();
            assertThat(stats.numDocs()).isEqualTo(2);
            assertThat(stats.maxDoc()).isEqualTo(3);
            assertThat(stats.segmentVersions()).containsExactly("2.x");
            assertThat(stats.oldestMajor()).isEqualTo(2);
            assertThat(stats.segmentsFile()).startsWith("segments_");
            assertThat(stats.fields()).extracting(RuntimeIndex.Field::name).containsExactly("id", "body", "raw");
            assertThat(stats.fields().get(1))
                    .isEqualTo(new RuntimeIndex.Field(
                            "body", 1, RuntimeIndex.IndexOptions.DOCS_AND_FREQS_AND_POSITIONS, true, false, true));
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
            assertThat(vector).extracting(Documents.VectorTerm::term).contains("document", "number", "old");
            Documents.VectorTerm document = vector.stream()
                    .filter(term -> term.term().equals("document"))
                    .findFirst()
                    .orElseThrow();
            assertThat(document.positions().getFirst().position()).hasValue(0);
            assertThat(document.positions().getFirst().startOffset()).hasValue(0);
            assertThat(document.positions().getFirst().endOffset()).hasValue(8);
            assertThatThrownBy(() -> index.documents().orElseThrow().document(3))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void readsTermsAndPostings() throws IOException {
        try (RuntimeIndex index = open(DirectoryKind.NIO)) {
            Terms.TermsPage page = index.terms().orElseThrow().terms("id", Optional.empty(), 2);
            assertThat(page.terms()).extracting(Terms.TermEntry::term).containsExactly("1", "2");
            assertThat(page.next()).contains("3");
            assertThat(index.terms().orElseThrow().terms("id", page.next(), 2).terms())
                    .containsExactly(new Terms.TermEntry("3", 1, 1));
            assertThat(index.terms()
                            .orElseThrow()
                            .terms("nothing", Optional.empty(), 10)
                            .terms())
                    .isEmpty();

            Terms.PostingsPage postings = index.terms().orElseThrow().postings("body", "document", 0, 1);
            // docFreq counts the deleted document, the postings skip it.
            assertThat(postings.docFreq()).isEqualTo(3);
            assertThat(postings.postings()).containsExactly(new Terms.Posting(0, 1));
            assertThat(postings.nextDoc()).hasValue(2);
        }
    }

    @Test
    void searches() throws Exception {
        try (RuntimeIndex index = open(DirectoryKind.NIO)) {
            Search.ParsedQuery query = index.search()
                    .orElseThrow()
                    .parse("number AND (1 OR 3)", new Search.ParserOptions("body", false, false, 0, 0, true, false));
            Search.SearchResults results =
                    index.search().orElseThrow().search(query, 0, 10, Optional.of(List.of("id")), _ -> {});
            assertThat(results.totalHits()).isEqualTo(2);
            assertThat(results.hits()).extracting(Search.Hit::docId).containsExactlyInAnyOrder(0, 2);
            assertThat(results.hits().getFirst().fields()).containsOnlyKeys("id");
            assertThatThrownBy(() -> index.search()
                            .orElseThrow()
                            .parse("body:(", new Search.ParserOptions("body", false, false, 0, 0, true, false)))
                    .isInstanceOf(Search.InvalidQueryException.class);
        }
    }

    /** Hard rule 1: reading changes nothing in the index directory, and leaves no lock. */
    @ParameterizedTest
    @EnumSource(DirectoryKind.class)
    void changesNothing(DirectoryKind directoryKind) throws Exception {
        Map<String, String> before = hashes(lucene24());
        try (RuntimeIndex index = open(directoryKind)) {
            index.stats();
            index.documents().orElseThrow().document(2);
            index.terms().orElseThrow().terms("body", Optional.empty(), 100);
        }
        assertThat(hashes(lucene24())).isEqualTo(before);
        assertThat(lucene24().resolve("write.lock")).doesNotExist();
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
}
