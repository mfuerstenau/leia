package io.github.mfuerstenau.leia.lucene3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Reads the Lucene 2.4 index that the build writes (build.gradle.kts) with plain Lucene 3.6. */
class Lucene3IndexTest {

    private static Path lucene24() {
        String dir = System.getProperty("leia.test.index.lucene24");
        if (dir == null) {
            throw new IllegalStateException("System property leia.test.index.lucene24 is not set");
        }
        return Path.of(dir).toAbsolutePath();
    }

    @Test
    void handlesOnlyIndexesBeforeCodecs(@org.junit.jupiter.api.io.TempDir Path tmp) throws IOException {
        assertThat(Lucene3Index.handles(lucene24())).isTrue();
        assertThat(Lucene3Index.handles(tmp)).isFalse();
        // The header of every segments_N since Lucene 4.0: CodecUtil's magic.
        Files.write(tmp.resolve("segments_1"), new byte[] {0x3f, (byte) 0xd7, 0x6c, 0x17});
        assertThat(Lucene3Index.handles(tmp)).isFalse();
    }

    @Test
    void readsTheStats() throws IOException {
        try (Lucene3Index index = Lucene3Index.open(lucene24(), "NIO")) {
            Lucene3Index.Stats stats = index.stats();
            assertThat(stats.numDocs()).isEqualTo(2);
            assertThat(stats.maxDoc()).isEqualTo(3);
            assertThat(stats.segmentVersions()).containsExactly("2.x");
            assertThat(stats.oldestMajor()).isEqualTo(2);
            assertThat(stats.segmentsFile()).startsWith("segments_");
            assertThat(stats.fields()).extracting(Lucene3Index.Field::name).containsExactly("id", "body", "raw");
            assertThat(stats.fields().get(1))
                    .isEqualTo(new Lucene3Index.Field(
                            "body", 1, Lucene3Index.IndexOptions.DOCS_AND_FREQS_AND_POSITIONS, true, false, true));
            assertThat(stats.fields().get(2).indexOptions()).isEqualTo(Lucene3Index.IndexOptions.NONE);
        }
        assertThat(Lucene3Index.luceneVersion()).startsWith("3.6");
    }

    @Test
    void readsDocumentsAndTermVectors() throws IOException {
        try (Lucene3Index index = Lucene3Index.open(lucene24(), "FS")) {
            Lucene3Index.IndexDocument doc = index.document(0);
            assertThat(doc.deleted()).isFalse();
            assertThat(doc.fields().get(0).stored()).containsExactly(new Lucene3Index.StoredValue.Text("1"));
            assertThat(doc.fields().get(1).hasTermVector()).isTrue();
            assertThat(doc.fields().get(1).norm()).isPresent();
            Lucene3Index.StoredValue raw = doc.fields().get(2).stored().getFirst();
            assertThat(((Lucene3Index.StoredValue.Binary) raw).bytes())
                    .asString()
                    .isEqualTo("bytes1");
            assertThat(index.document(1).deleted()).isTrue();

            List<Lucene3Index.VectorTerm> vector = index.termVector(0, "body");
            assertThat(vector).extracting(Lucene3Index.VectorTerm::term).contains("document", "number", "old");
            Lucene3Index.VectorTerm document = vector.stream()
                    .filter(term -> term.term().equals("document"))
                    .findFirst()
                    .orElseThrow();
            assertThat(document.positions().getFirst().position()).hasValue(0);
            assertThat(document.positions().getFirst().startOffset()).hasValue(0);
            assertThat(document.positions().getFirst().endOffset()).hasValue(8);
            assertThatThrownBy(() -> index.document(3)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void readsTermsAndPostings() throws IOException {
        try (Lucene3Index index = Lucene3Index.open(lucene24(), "NIO")) {
            Lucene3Index.TermsPage page = index.terms("id", Optional.empty(), 2);
            assertThat(page.terms()).extracting(Lucene3Index.TermEntry::term).containsExactly("1", "2");
            assertThat(page.next()).contains("3");
            assertThat(index.terms("id", page.next(), 2).terms())
                    .containsExactly(new Lucene3Index.TermEntry("3", 1, 1));
            assertThat(index.terms("nothing", Optional.empty(), 10).terms()).isEmpty();

            Lucene3Index.PostingsPage postings = index.postings("body", "document", 0, 1);
            // docFreq counts the deleted document, the postings skip it.
            assertThat(postings.docFreq()).isEqualTo(3);
            assertThat(postings.postings()).containsExactly(new Lucene3Index.Posting(0, 1));
            assertThat(postings.nextDoc()).hasValue(2);
        }
    }

    @Test
    void searches() throws Exception {
        try (Lucene3Index index = Lucene3Index.open(lucene24(), "NIO")) {
            Lucene3Index.ParsedQuery query = index.parse(
                    "number AND (1 OR 3)", new Lucene3Index.ParserOptions("body", false, false, 0, 0, true, false));
            Lucene3Index.SearchResults results = index.search(query, 0, 10, Optional.of(List.of("id")), _ -> {});
            assertThat(results.totalHits()).isEqualTo(2);
            assertThat(results.hits()).extracting(Lucene3Index.Hit::docId).containsExactlyInAnyOrder(0, 2);
            assertThat(results.hits().getFirst().fields()).containsOnlyKeys("id");
            assertThatThrownBy(() -> index.parse(
                            "body:(", new Lucene3Index.ParserOptions("body", false, false, 0, 0, true, false)))
                    .isInstanceOf(Lucene3Index.InvalidQueryException.class);
        }
    }

    /** Hard rule 1: reading changes nothing in the index directory, and leaves no lock. */
    @ParameterizedTest
    @ValueSource(strings = {"FS", "MMAP", "NIO"})
    void changesNothing(String directoryImpl) throws Exception {
        Map<String, String> before = hashes(lucene24());
        try (Lucene3Index index = Lucene3Index.open(lucene24(), directoryImpl)) {
            index.stats();
            index.document(2);
            index.terms("body", Optional.empty(), 100);
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
