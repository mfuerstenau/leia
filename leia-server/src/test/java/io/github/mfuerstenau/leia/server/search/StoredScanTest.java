package io.github.mfuerstenau.leia.server.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.github.mfuerstenau.leia.runtime.RuntimeIndex;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.TestIndexes;
import io.github.mfuerstenau.leia.server.filter.DocFilter;
import io.github.mfuerstenau.leia.server.job.ScanProgress;
import io.github.mfuerstenau.leia.server.runtime.OpenRuntime;
import io.github.mfuerstenau.leia.server.runtime.RuntimeResults;
import io.github.mfuerstenau.leia.server.search.StoredScan.Hit;
import io.github.mfuerstenau.leia.server.search.StoredScan.Span;
import io.github.mfuerstenau.leia.server.search.StoredScan.Summary;
import io.github.mfuerstenau.leia.server.search.StoredScan.ValueRef;
import io.github.mfuerstenau.leia.server.stored.StoredFieldsCache;
import io.github.mfuerstenau.leia.server.stored.StoredFieldsScan;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.regex.Pattern;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.FSDirectory;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A regular expression over stored values (ADR 0027). */
class StoredScanTest {

    private static final Duration LIMIT = Duration.ofSeconds(10);
    private static final StoredFieldsCache NO_CACHE = new StoredFieldsCache(null);

    @TempDir
    Path tmp;

    @Test
    void findsThePatternInTextAndUtf8BinaryValues() throws Exception {
        Path dir = writeIndex(tmp, doc -> {
            doc.add(new TextField("title", "red apple", Field.Store.YES));
            doc.add(new StoredField("json", "{\"color\":\"red\"}".getBytes(StandardCharsets.UTF_8)));
            doc.add(new StoredField("blob", new byte[] {(byte) 0xff, 'r', 'e', 'd'}));
            doc.add(new StoredField("number", 123));
        });

        try (OpenRuntime index = TestIndexes.open(dir)) {
            Result result = scan(index, "red|123", null, false, 10);

            assertThat(result.hits)
                    .extracting(Hit::docId, Hit::field, Hit::valueIndex, Hit::spans)
                    .containsExactlyInAnyOrder(
                            tuple(0, "title", 0, List.of(new Span(0, 3))),
                            tuple(0, "json", 0, List.of(new Span(10, 13))));
            assertThat(result.summary.matchedDocs()).isEqualTo(1);
            assertThat(result.summary.matchedValues()).isEqualTo(2);
        }
    }

    @Test
    void positionsAreUtf8ByteOffsets() throws Exception {
        Path dir = writeIndex(tmp, doc -> doc.add(new StoredField("text", "ä😀red 字red")));

        try (OpenRuntime index = TestIndexes.open(dir)) {
            Hit hit = scan(index, "red", null, false, 10).hits.getFirst();

            // ä is 2 bytes, 😀 is 4, 字 is 3.
            assertThat(hit.spans()).containsExactly(new Span(6, 9), new Span(13, 16));
            assertThat(hit.length()).isEqualTo(16);
            assertThat(hit.matchCount()).isEqualTo(2);
            assertThat(hit.snippetStart()).isZero();
            assertThat(hit.snippet()).isEqualTo("ä😀red 字red");
        }
    }

    @Test
    void theSnippetSurroundsTheFirstMatchAndKeepsSurrogatePairsWhole() throws Exception {
        String text = "x".repeat(10) + "😀" + "x".repeat(39) + "needle" + "y".repeat(300);
        Path dir = writeIndex(tmp, doc -> doc.add(new StoredField("text", text)));

        try (OpenRuntime index = TestIndexes.open(dir)) {
            Hit hit = scan(index, "needle", null, false, 10).hits.getFirst();

            // 40 UTF-16 units before the match is the pair's second half: the snippet starts before it.
            int start = text.indexOf("needle");
            assertThat(hit.snippet()).isEqualTo(text.substring(10, start + StoredScan.SNIPPET_AFTER));
            assertThat(hit.snippetStart()).isEqualTo(10);
            assertThat(hit.spans()).containsExactly(new Span(53, 59));
        }
    }

    @Test
    void scansOnlyTheGivenFields() throws Exception {
        Path dir = writeIndex(tmp, doc -> {
            doc.add(new StoredField("a", "match"));
            doc.add(new StoredField("b", "match"));
            doc.add(new StoredField("b", "match again"));
        });

        try (OpenRuntime index = TestIndexes.open(dir)) {
            assertThat(scan(index, "match", Set.of("b"), false, 10).hits)
                    .extracting(Hit::field, Hit::valueIndex)
                    .containsExactly(tuple("b", 0), tuple("b", 1));
            assertThatThrownBy(() -> scan(index, "match", Set.of("missing"), false, 10))
                    .isInstanceOf(RpcException.class)
                    .hasMessageContaining("missing");
        }
    }

    @Test
    void scansDeletedDocumentsOnlyWhenAsked() throws Exception {
        Path dir = writeIndex(
                tmp,
                doc -> doc.add(new StoredField("text", "live match")),
                doc -> doc.add(new StoredField("text", "deleted match")));
        try (IndexWriter writer = new IndexWriter(
                FSDirectory.open(dir), new IndexWriterConfig().setMergePolicy(NoMergePolicy.INSTANCE))) {
            writer.deleteDocuments(new Term("id", "1"));
        }

        try (OpenRuntime index = TestIndexes.open(dir)) {
            Result live = scan(index, "match", null, false, 10);
            assertThat(live.hits).extracting(Hit::docId, Hit::deleted).containsExactly(tuple(0, false));
            assertThat(live.summary.scannedDocs()).isEqualTo(2);

            assertThat(scan(index, "match", null, true, 10).hits)
                    .extracting(Hit::docId, Hit::deleted)
                    .containsExactly(tuple(0, false), tuple(1, true));
        }
    }

    @Test
    void keepsTheFirstSpansAndHitsAndCountsTheRest() throws Exception {
        Path dir = writeIndex(
                tmp,
                doc -> doc.add(new StoredField("text", "a ".repeat(150))),
                doc -> doc.add(new StoredField("text", "a")),
                doc -> doc.add(new StoredField("text", "b")),
                doc -> doc.add(new StoredField("text", "a")));

        try (OpenRuntime index = TestIndexes.open(dir)) {
            Result result = scan(index, "a", null, false, 2);

            assertThat(result.hits).extracting(Hit::docId).containsExactly(0, 1);
            assertThat(result.hits.getFirst().matchCount()).isEqualTo(150);
            assertThat(result.hits.getFirst().spans()).hasSize(StoredScan.MAX_SPANS);
            assertThat(result.matched).containsExactly(1L, 2L, 3L);
            assertThat(result.summary.matchedValues()).isEqualTo(3);
            assertThat(result.summary.matchedDocs()).isEqualTo(3);
            assertThat(result.summary.hitsTruncated()).isTrue();
            assertThat(scan(index, "a", null, false, 3).summary.hitsTruncated()).isFalse();
        }
    }

    @Test
    void skipsAndReportsValuesThatTakeTooLong() throws Exception {
        Path dir = writeIndex(
                tmp,
                doc -> doc.add(new StoredField("text", "a".repeat(40) + "c")),
                // Matched by the cheap alternative: a value that matches only after much backtracking
                // can itself run out of the 50 ms on a slow CI runner.
                doc -> doc.add(new StoredField("text", "match")));

        try (OpenRuntime index = TestIndexes.open(dir)) {
            List<Hit> hits = new ArrayList<>();
            Summary summary = storedScan(
                    index,
                    Pattern.compile("(.*a){20}b|match"),
                    null,
                    false,
                    10,
                    Duration.ofMillis(50),
                    listener(hits, _ -> {}),
                    ScanProgress.NONE);

            assertThat(hits).extracting(Hit::docId).containsExactly(1);
            assertThat(summary.timedOutValues()).isEqualTo(1);
            assertThat(summary.timedOut()).containsExactly(new ValueRef(0, "text", 0));
        }
    }

    @Test
    void aCancelStopsTheScanInsideASlowValue() throws Exception {
        Path dir = writeIndex(tmp, doc -> doc.add(new StoredField("text", "a".repeat(40) + "c")));
        List<Long> reports = new ArrayList<>();

        try (OpenRuntime index = TestIndexes.open(dir)) {
            assertThatThrownBy(() -> storedScan(
                            index,
                            Pattern.compile("(.*a){20}b"),
                            null,
                            false,
                            10,
                            Duration.ofHours(1),
                            listener(new ArrayList<>(), _ -> {}),
                            scanned -> {
                                reports.add(scanned);
                                if (reports.size() > 1) {
                                    throw new CancellationException();
                                }
                            }))
                    .isInstanceOf(CancellationException.class);
            // The first report is before document 0, the second from inside its value.
            assertThat(reports).containsExactly(0L, 0L);
        }
    }

    @Test
    void reportsTheDocumentsGoneThrough() throws Exception {
        List<Fields> docs = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            docs.add(doc -> doc.add(new StoredField("text", "x")));
        }
        Path dir = writeIndex(tmp, docs);
        List<Long> reports = new ArrayList<>();

        try (OpenRuntime index = TestIndexes.open(dir)) {
            storedScan(
                    index,
                    Pattern.compile("y"),
                    null,
                    false,
                    10,
                    LIMIT,
                    listener(new ArrayList<>(), _ -> {}),
                    reports::add);
        }

        assertThat(reports).containsExactly(0L, 64L, 100L);
    }

    @Test
    void scansASource() throws Exception {
        // 150 documents; every tenth is deleted, and the source leaves those out.
        StoredScan.StoredSource source = new StoredScan.StoredSource() {
            @Override
            public int maxDoc() {
                return 150;
            }

            @Override
            public void read(Consumer<StoredScan.Document> sink, IntConsumer goneThrough) {
                goneThrough.accept(0);
                for (int docId = 0; docId < 150; docId++) {
                    if (docId == 100) {
                        goneThrough.accept(100);
                    }
                    if (docId % 10 != 0) {
                        String text = docId % 7 == 0 ? "seven" : "other";
                        sink.accept(new StoredScan.Document(
                                docId,
                                false,
                                Map.of(
                                        "text",
                                        List.of(
                                                new RuntimeIndex.StoredValue.Numeric(7L),
                                                new RuntimeIndex.StoredValue.Text(text)))));
                    }
                }
                goneThrough.accept(150);
            }
        };
        List<Hit> hits = new ArrayList<>();
        List<Long> reports = new ArrayList<>();

        Summary summary =
                StoredScan.scan(source, Pattern.compile("seven"), 100, LIMIT, listener(hits, _ -> {}), reports::add);

        // The source's reports, and the scan's own every 64 documents it gets: the 64th is 71.
        assertThat(reports).containsExactly(0L, 72L, 100L, 143L, 150L);
        // Multiples of 7 that aren't multiples of 10: 21 of them below 150, without 0, 70 and 140.
        assertThat(summary.scannedDocs()).isEqualTo(150);
        assertThat(summary.matchedDocs()).isEqualTo(19);
        assertThat(hits).extracting(Hit::docId).startsWith(7, 14, 21).doesNotContain(0, 70, 140);
        // The number before the text counts for the value index.
        assertThat(hits).extracting(Hit::valueIndex).containsOnly(1);

        DocFilter filter = StoredScan.matches(source, Pattern.compile("seven"), LIMIT, ScanProgress.NONE);
        assertThat(filter.matchCount()).isEqualTo(19);
        assertThat(filter.contains(147)).isTrue();
        assertThat(filter.contains(140)).isFalse();
    }

    @Test
    void passesOverTheDocumentsTheCacheSaysStoreNoneOfTheFields() throws Exception {
        List<Fields> docs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            int id = i;
            docs.add(doc -> {
                doc.add(new StoredField("text", "common"));
                if (id % 4 == 1) {
                    doc.add(new StoredField("rare", "rare " + id));
                }
            });
        }
        Path dir = writeIndex(tmp, docs);

        try (OpenRuntime index = TestIndexes.open(dir)) {
            StoredFieldsCache cache = new StoredFieldsCache(null);
            Set<String> rare = Set.of("rare");
            Summary before = storedScan(index, cache, rare);
            // Without the cache, every document is read, those without rare too.
            assertThat(read(index, cache, rare)).hasSize(10);

            StoredFieldsScan.scan(
                    index.index(), index.index().documents().orElseThrow(), cache, false, ScanProgress.NONE);

            assertThat(read(index, cache, rare)).containsExactly(1, 5, 9);
            assertThat(read(index, cache, Set.of("text"))).hasSize(10);
            assertThat(read(index, cache, null)).hasSize(10);
            Summary after = storedScan(index, cache, rare);
            assertThat(after.scannedDocs()).isEqualTo(before.scannedDocs()).isEqualTo(10);
            assertThat(after.matchedDocs()).isEqualTo(before.matchedDocs()).isEqualTo(3);
        }
    }

    /** The ids of the documents {@code startStoredScan}'s source reads of {@code fields}. */
    private static List<Integer> read(OpenRuntime index, StoredFieldsCache cache, @Nullable Set<String> fields)
            throws Exception {
        List<Integer> read = new ArrayList<>();
        RuntimeResults.storedSource(index, cache, fields, false).read(document -> read.add(document.docId()), _ -> {});
        return read;
    }

    private static Summary storedScan(OpenRuntime index, StoredFieldsCache cache, @Nullable Set<String> fields)
            throws Exception {
        return StoredScan.scan(
                RuntimeResults.storedSource(index, cache, fields, false),
                Pattern.compile("rare"),
                10,
                LIMIT,
                listener(new ArrayList<>(), _ -> {}),
                ScanProgress.NONE);
    }

    @Test
    void keepsTheDocumentsWithAMatchAsAFilter() throws Exception {
        Path dir = writeIndex(
                tmp,
                doc -> doc.add(new StoredField("text", "live match")),
                doc -> doc.add(new StoredField("text", "deleted match")),
                doc -> doc.add(new StoredField("text", "none")),
                doc -> doc.add(new StoredField("text", "a".repeat(40) + "c")),
                doc -> {
                    doc.add(new StoredField("text", "MATCH"));
                    doc.add(new StoredField("text", "match"));
                });
        try (IndexWriter writer = new IndexWriter(
                FSDirectory.open(dir), new IndexWriterConfig().setMergePolicy(NoMergePolicy.INSTANCE))) {
            writer.deleteDocuments(new Term("id", "1"));
        }

        try (OpenRuntime index = TestIndexes.open(dir)) {
            Pattern pattern = Pattern.compile("(.*a){20}b|match", Pattern.CASE_INSENSITIVE);
            DocFilter live = storedScanMatches(index, pattern, null, false, Duration.ofMillis(50), ScanProgress.NONE);

            assertThat(live.matchCount()).isEqualTo(2);
            assertThat(live.contains(0)).isTrue();
            assertThat(live.contains(4)).isTrue();
            assertThat(live.query()).isEqualTo("/(.*a){20}b|match/i");
            assertThat(live.rewrittenQuery()).isEqualTo(live.query());
            assertThat(live.timedOutValues()).hasValue(1);

            DocFilter all =
                    storedScanMatches(index, Pattern.compile("match"), Set.of("text"), true, LIMIT, ScanProgress.NONE);
            assertThat(all.matchCount()).isEqualTo(3);
            assertThat(all.contains(1)).isTrue();
            assertThat(all.query()).isEqualTo("/match/");
            assertThat(all.timedOutValues()).hasValue(0);
            assertThatThrownBy(
                            () -> storedScanMatches(index, pattern, Set.of("missing"), false, LIMIT, ScanProgress.NONE))
                    .isInstanceOf(RpcException.class);
        }
    }

    @Test
    void docIdsCountOverEveryIndexBelowThePath() throws Exception {
        writeIndex(
                tmp.resolve("a"),
                doc -> doc.add(new StoredField("text", "no")),
                doc -> doc.add(new StoredField("text", "no")));
        writeIndex(
                tmp.resolve("b"),
                doc -> doc.add(new StoredField("text", "no")),
                doc -> doc.add(new StoredField("text", "yes")));

        try (OpenRuntime index = TestIndexes.open(tmp)) {
            assertThat(scan(index, "yes", null, false, 10).hits)
                    .extracting(Hit::docId)
                    .containsExactly(3);
        }
    }

    @Test
    void utf8OffsetsCountSurrogatePairsOnce() {
        StoredScan.Utf8Offsets offsets = new StoredScan.Utf8Offsets("a😀b");

        assertThat(offsets.at(1)).isEqualTo(1);
        assertThat(offsets.at(2)).isEqualTo(5);
        // Between the halves of the pair: after it.
        assertThat(offsets.at(2)).isEqualTo(5);
        assertThat(offsets.at(4)).isEqualTo(6);
    }

    private record Result(List<Hit> hits, List<Long> matched, Summary summary) {}

    private static Result scan(
            OpenRuntime index, String pattern, @Nullable Set<String> fields, boolean includeDeleted, int maxHits)
            throws Exception {
        List<Hit> hits = new ArrayList<>();
        List<Long> matched = new ArrayList<>();
        Summary summary = storedScan(
                index,
                Pattern.compile(pattern),
                fields,
                includeDeleted,
                maxHits,
                LIMIT,
                listener(hits, matched::add),
                ScanProgress.NONE);
        return new Result(hits, matched, summary);
    }

    private static StoredScan.Listener listener(List<Hit> hits, Consumer<Long> matched) {
        return new StoredScan.Listener() {
            @Override
            public void hit(Hit hit) {
                hits.add(hit);
            }

            @Override
            public void matched(long matchedValues) {
                matched.accept(matchedValues);
            }
        };
    }

    /** Adds a test document's fields. */
    @FunctionalInterface
    private interface Fields {
        void addTo(Document doc);
    }

    /** Writes one document per {@code docs}, each with an {@code id} of its number, in one segment. */
    private static Path writeIndex(Path dir, Fields... docs) throws IOException {
        return writeIndex(dir, List.of(docs));
    }

    private static Path writeIndex(Path dir, List<Fields> docs) throws IOException {
        try (IndexWriter writer = new IndexWriter(
                FSDirectory.open(dir), new IndexWriterConfig().setMergePolicy(NoMergePolicy.INSTANCE))) {
            for (int i = 0; i < docs.size(); i++) {
                Document doc = new Document();
                doc.add(new StringField("id", Integer.toString(i), Field.Store.NO));
                docs.get(i).addTo(doc);
                writer.addDocument(doc);
            }
        }
        return dir;
    }

    /** Scans the stored values of {@code index} as {@code startStoredScan} does. */
    private static Summary storedScan(
            OpenRuntime index,
            Pattern pattern,
            @Nullable Set<String> fields,
            boolean includeDeleted,
            int maxHits,
            Duration limit,
            StoredScan.Listener listener,
            ScanProgress progress)
            throws Exception {
        return StoredScan.scan(
                RuntimeResults.storedSource(index, NO_CACHE, fields, includeDeleted),
                pattern,
                maxHits,
                limit,
                listener,
                progress);
    }

    /** The documents of {@code index} with a matching stored value, as a pattern filter finds them. */
    private static DocFilter storedScanMatches(
            OpenRuntime index,
            Pattern pattern,
            @Nullable Set<String> fields,
            boolean includeDeleted,
            Duration limit,
            ScanProgress progress)
            throws Exception {
        return StoredScan.matches(
                RuntimeResults.storedSource(index, NO_CACHE, fields, includeDeleted), pattern, limit, progress);
    }
}
