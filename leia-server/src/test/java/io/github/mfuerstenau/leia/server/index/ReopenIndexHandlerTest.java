package io.github.mfuerstenau.leia.server.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mfuerstenau.leia.core.ReadOnlyIndex;
import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.index.IndexEntry;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexParams;
import io.github.mfuerstenau.leia.protocol.index.OpenMode;
import io.github.mfuerstenau.leia.protocol.index.ReaderInfo;
import io.github.mfuerstenau.leia.protocol.index.ReopenIndexParams;
import io.github.mfuerstenau.leia.protocol.index.ReopenIndexResult;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.filter.Filters;
import io.github.mfuerstenau.leia.server.job.Jobs;
import io.github.mfuerstenau.leia.server.runtime.Runtimes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Version;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReopenIndexHandlerTest {

    @TempDir
    Path tmp;

    private final Jobs jobs = new Jobs();
    private final CurrentIndex currentIndex = new CurrentIndex(jobs, new Filters());
    private final OpenIndexHandler openHandler = new OpenIndexHandler(currentIndex, new Runtimes());
    private final ReopenIndexHandler handler = new ReopenIndexHandler(currentIndex);

    @AfterEach
    void closeIndex() {
        currentIndex.close();
    }

    @Test
    void failsWithoutAnOpenIndex() {
        assertThatThrownBy(() -> handler.handle(new ReopenIndexParams()))
                .isInstanceOfSatisfying(
                        RpcException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NO_INDEX_OPEN))
                .hasMessage("No index is open");
    }

    @Test
    void keepsAnUnchangedIndexAndItsJobs() throws Exception {
        Path dir = addDocuments(tmp.resolve("index"), 1, 2);
        openHandler.handle(new OpenIndexParams(dir.toString()));
        ReadOnlyIndex open = currentIndex.readable();
        long jobId = startEndlessJob();

        ReopenIndexResult result = handler.handle(new ReopenIndexParams());

        assertThat(result)
                .isEqualTo(new ReopenIndexResult(
                        false,
                        dir.toString(),
                        OpenMode.READER,
                        OptionalInt.of(2),
                        OptionalInt.of(2),
                        List.of(new IndexEntry(
                                dir.toString(),
                                Optional.of(new ReaderInfo(
                                        0,
                                        "segments_1",
                                        2,
                                        2,
                                        Version.LATEST.major,
                                        Optional.of(Version.LATEST.toString()))))),
                        List.of()));
        assertThat(currentIndex.get()).containsSame(open);
        assertThat(jobs.cancel(jobId)).as("the job was still running").isTrue();
    }

    @Test
    void replacesAChangedIndexAndStopsTheJobsOfTheOldOne() throws Exception {
        Path dir = addDocuments(tmp.resolve("index"), 1, 2);
        openHandler.handle(new OpenIndexParams(dir.toString()));
        ReadOnlyIndex old = currentIndex.readable();
        long jobId = startEndlessJob();
        addDocuments(dir, 3, 1);

        ReopenIndexResult result = handler.handle(new ReopenIndexParams());

        assertThat(result)
                .isEqualTo(new ReopenIndexResult(
                        true,
                        dir.toString(),
                        OpenMode.READER,
                        OptionalInt.of(3),
                        OptionalInt.of(3),
                        List.of(new IndexEntry(
                                dir.toString(),
                                Optional.of(new ReaderInfo(
                                        0,
                                        "segments_2",
                                        3,
                                        3,
                                        Version.LATEST.major,
                                        Optional.of(Version.LATEST.toString()))))),
                        List.of()));
        assertThat(jobs.cancel(jobId)).as("the job was stopped").isFalse();
        assertThat(old.reader().tryIncRef()).as("the old reader is closed").isFalse();
        ReadOnlyIndex reopened = currentIndex.readable();
        assertThat(reopened).isNotSameAs(old);
        assertThat(reopened.document(2).docId()).isEqualTo(2);
    }

    @Test
    void keepsTheIndexWhenTheLatestCommitCantBeRead() throws Exception {
        Path dir = addDocuments(tmp.resolve("index"), 1, 2);
        openHandler.handle(new OpenIndexParams(dir.toString()));
        ReadOnlyIndex open = currentIndex.readable();
        addDocuments(dir, 3, 1);
        Path segments = dir.resolve("segments_2");
        byte[] bytes = Files.readAllBytes(segments);
        bytes[bytes.length / 2] ^= 0x5A;
        Files.write(segments, bytes);

        assertThatThrownBy(() -> handler.handle(new ReopenIndexParams()))
                .isInstanceOfSatisfying(
                        RpcException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.CORRUPT_INDEX))
                .hasMessageStartingWith("Corrupt index");
        assertThat(currentIndex.get()).containsSame(open);
        assertThat(open.reader().numDocs()).isEqualTo(2);
    }

    /** A job that reports progress until it is cancelled. */
    private long startEndlessJob() throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        long id = jobs.start("endless", String.class, progress -> {
            started.countDown();
            for (long scanned = 0; ; scanned++) {
                progress.scanned(scanned);
            }
        });
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        return id;
    }

    /** Adds and commits {@code docs} documents with ids from {@code firstId} on. */
    private static Path addDocuments(Path dir, int firstId, int docs) throws IOException {
        try (FSDirectory directory = FSDirectory.open(dir);
                IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig())) {
            for (int i = firstId; i < firstId + docs; i++) {
                Document doc = new Document();
                doc.add(new StringField("id", Integer.toString(i), Field.Store.YES));
                writer.addDocument(doc);
            }
            writer.commit();
        }
        return dir;
    }
}
