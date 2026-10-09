package io.github.mfuerstenau.leia.server.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.github.mfuerstenau.leia.core.DirectoryImpl;
import io.github.mfuerstenau.leia.core.DirectoryOnlyIndex;
import io.github.mfuerstenau.leia.core.ReadOnlyIndex;
import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.search.GetStoredScanParams;
import io.github.mfuerstenau.leia.protocol.search.GetStoredScanResult;
import io.github.mfuerstenau.leia.protocol.search.MatchSpan;
import io.github.mfuerstenau.leia.protocol.search.Snippet;
import io.github.mfuerstenau.leia.protocol.search.StartStoredScanParams;
import io.github.mfuerstenau.leia.protocol.search.StoredScanHit;
import io.github.mfuerstenau.leia.protocol.search.StoredScanResult;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.index.CurrentIndex;
import io.github.mfuerstenau.leia.server.job.Jobs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code startStoredScan} and {@code getStoredScan} together (ADR 0027). */
class StoredScanHandlersTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @TempDir
    Path tmp;

    private final Jobs jobs = new Jobs();
    private final CurrentIndex currentIndex = new CurrentIndex(jobs);
    private final StartStoredScanHandler start = new StartStoredScanHandler(currentIndex, jobs);
    private final GetStoredScanHandler get = new GetStoredScanHandler(jobs);

    @Test
    void scansTheStoredValuesAndStreamsTheHits() throws Exception {
        currentIndex.replace(ReadOnlyIndex.open(writeIndex()));

        long jobId = start.handle(params("RED", true, Optional.empty(), false)).jobId();
        List<StoredScanHit> hits = new ArrayList<>();
        StoredScanResult result = awaitDone(jobId, hits);

        assertThat(hits)
                .extracting(StoredScanHit::docId, StoredScanHit::field, StoredScanHit::spans)
                .containsExactly(
                        tuple(0, "title", List.of(new MatchSpan(0, 3))),
                        tuple(0, "json", List.of(new MatchSpan(10, 13))),
                        tuple(1, "title", List.of(new MatchSpan(6, 9))));
        assertThat(hits.get(2).snippet()).isEqualTo(new Snippet(0, "Käse red"));
        assertThat(result.scannedDocs()).isEqualTo(3);
        assertThat(result.matchedDocs()).isEqualTo(2);
        assertThat(result.matchedValues()).isEqualTo(3);
        assertThat(result.hitsTruncated()).isFalse();
        assertThat(result.timedOutValues()).isZero();
        assertFails(() -> get.handle(new GetStoredScanParams(jobId, 0)), ErrorCode.JOB_NOT_FOUND);
    }

    @Test
    void scansTheGivenFieldsCaseSensitivelyAndDeletedDocumentsWhenAsked() throws Exception {
        currentIndex.replace(ReadOnlyIndex.open(writeIndex()));

        List<StoredScanHit> hits = new ArrayList<>();
        awaitDone(
                start.handle(params("red", false, Optional.of(List.of("title")), true))
                        .jobId(),
                hits);

        assertThat(hits)
                .extracting(StoredScanHit::docId, StoredScanHit::deleted)
                // Case-sensitive: "Red apple" doesn't match.
                .containsExactly(tuple(1, false), tuple(2, true));
    }

    @Test
    void returnsTheHitsFromTheGivenOne() throws Exception {
        currentIndex.replace(ReadOnlyIndex.open(writeIndex()));

        GetStoredScanResult fromOne = awaitOutcome(
                start.handle(params("red", true, Optional.empty(), false)).jobId(), 1);
        GetStoredScanResult pastTheEnd = awaitOutcome(
                start.handle(params("red", true, Optional.empty(), false)).jobId(), 100);

        assertThat(fromOne.hits()).extracting(StoredScanHit::field).containsExactly("json", "title");
        assertThat(fromOne.hitCount()).isEqualTo(3);
        assertThat(fromOne.matchedValues()).isEqualTo(3);
        assertThat(fromOne.scannedDocs()).isEqualTo(3);
        assertThat(pastTheEnd.hits()).isEmpty();
        assertThat(pastTheEnd.hitCount()).isEqualTo(3);
    }

    @Test
    void rejectsAPatternThatDoesNotCompile() throws Exception {
        currentIndex.replace(ReadOnlyIndex.open(writeIndex()));

        assertThatThrownBy(() -> start.handle(params("(red|", false, Optional.empty(), false)))
                .isInstanceOfSatisfying(RpcException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.INVALID_QUERY);
                    assertThat(e.getMessage()).isEqualTo("Invalid query: Unclosed group at index 5");
                });
    }

    @Test
    void rejectsAnUnknownField() throws Exception {
        currentIndex.replace(ReadOnlyIndex.open(writeIndex()));

        assertFails(
                () -> start.handle(params("red", false, Optional.of(List.of("titel")), false)),
                ErrorCode.FIELD_NOT_FOUND);
    }

    @Test
    void needsAReader() throws Exception {
        currentIndex.replace(DirectoryOnlyIndex.open(writeIndex(), DirectoryImpl.FS));

        assertFails(() -> start.handle(params("red", false, Optional.empty(), false)), ErrorCode.NO_READER);
    }

    @Test
    void failsWithoutOpenIndex() {
        assertFails(() -> start.handle(params("red", false, Optional.empty(), false)), ErrorCode.NO_INDEX_OPEN);
    }

    @Test
    void rejectsANegativeHit() throws Exception {
        currentIndex.replace(ReadOnlyIndex.open(writeIndex()));
        long jobId = start.handle(params("red", false, Optional.empty(), false)).jobId();

        assertFails(() -> get.handle(new GetStoredScanParams(jobId, -1)), ErrorCode.INVALID_PARAMS);
    }

    @Test
    void failsForUnknownJob() {
        assertFails(() -> get.handle(new GetStoredScanParams(99, 0)), ErrorCode.JOB_NOT_FOUND);
    }

    private static StartStoredScanParams params(
            String pattern, boolean caseInsensitive, Optional<List<String>> fields, boolean includeDeleted) {
        return new StartStoredScanParams(pattern, caseInsensitive, fields, includeDeleted);
    }

    /** Polls as the TUI does, collecting the hits, until the outcome is there. */
    private StoredScanResult awaitDone(long jobId, List<StoredScanHit> hits) throws Exception {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            GetStoredScanResult result = get.handle(new GetStoredScanParams(jobId, hits.size()));
            hits.addAll(result.hits());
            assertThat(result.hitCount()).isEqualTo(hits.size());
            if (result.result().isPresent()) {
                return result.result().get();
            }
            Thread.sleep(5);
        }
        throw new AssertionError("job " + jobId + " did not finish");
    }

    /** Polls from {@code fromHit} on until the outcome is there. */
    private GetStoredScanResult awaitOutcome(long jobId, int fromHit) throws Exception {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            GetStoredScanResult result = get.handle(new GetStoredScanParams(jobId, fromHit));
            if (result.result().isPresent()) {
                return result;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("job " + jobId + " did not finish");
    }

    /** Three documents; the third is deleted. */
    private Path writeIndex() throws IOException {
        Path dir = tmp.resolve("index");
        try (FSDirectory directory = FSDirectory.open(dir);
                IndexWriter writer =
                        new IndexWriter(directory, new IndexWriterConfig().setMergePolicy(NoMergePolicy.INSTANCE))) {
            writer.addDocument(doc("0", "Red apple", "{\"color\":\"red\"}"));
            writer.addDocument(doc("1", "Käse red", "{}"));
            writer.addDocument(doc("2", "red deleted", "{}"));
            writer.deleteDocuments(new Term("id", "2"));
        }
        return dir;
    }

    private static Document doc(String id, String title, String json) {
        Document doc = new Document();
        doc.add(new StringField("id", id, Field.Store.NO));
        doc.add(new TextField("title", title, Field.Store.YES));
        doc.add(new StoredField("json", json.getBytes(StandardCharsets.UTF_8)));
        doc.add(new StoredField("count", 1));
        return doc;
    }

    private static void assertFails(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        RpcException.class, e -> assertThat(e.code()).isEqualTo(code));
    }
}
