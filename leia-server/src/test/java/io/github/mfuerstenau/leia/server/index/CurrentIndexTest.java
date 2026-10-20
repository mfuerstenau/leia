package io.github.mfuerstenau.leia.server.index;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mfuerstenau.leia.server.TestIndexes;
import io.github.mfuerstenau.leia.server.filter.Filters;
import io.github.mfuerstenau.leia.server.job.Jobs;
import io.github.mfuerstenau.leia.server.runtime.OpenRuntime;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Jobs read the open index from their own threads, so they must stop before it is closed. */
class CurrentIndexTest {

    @TempDir
    Path tmp;

    private final Jobs jobs = new Jobs();
    private final CurrentIndex currentIndex = new CurrentIndex(jobs, new Filters());

    @Test
    void closingStopsTheJobsFirst() throws Exception {
        OpenRuntime index = TestIndexes.open(writeEmptyIndex(tmp.resolve("index")));
        currentIndex.replace(index);
        AtomicBoolean readerOpenWhenStopped = new AtomicBoolean();
        long jobId = startEndlessJob(index, readerOpenWhenStopped);

        currentIndex.close();

        assertThat(readerOpenWhenStopped).isTrue();
        assertThat(jobs.cancel(jobId)).as("the job is gone").isFalse();
    }

    @Test
    void replacingStopsTheJobsOfThePreviousIndex() throws Exception {
        OpenRuntime first = TestIndexes.open(writeEmptyIndex(tmp.resolve("first")));
        currentIndex.replace(first);
        AtomicBoolean readerOpenWhenStopped = new AtomicBoolean();
        long jobId = startEndlessJob(first, readerOpenWhenStopped);

        currentIndex.replace(TestIndexes.open(writeEmptyIndex(tmp.resolve("second"))));

        assertThat(readerOpenWhenStopped).isTrue();
        assertThat(jobs.cancel(jobId)).isFalse();
        assertThat(TestIndexes.readable(first)).as("previous reader is closed").isFalse();
        currentIndex.close();
    }

    /** Reports progress until cancelled, then records whether the reader was still open. */
    private long startEndlessJob(OpenRuntime index, AtomicBoolean readerOpenWhenStopped) throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        long id = jobs.start("endless", String.class, progress -> {
            started.countDown();
            try {
                for (long scanned = 0; ; scanned++) {
                    progress.scanned(scanned);
                }
            } finally {
                readerOpenWhenStopped.set(TestIndexes.readable(index));
            }
        });
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        return id;
    }

    private static Path writeEmptyIndex(Path dir) throws IOException {
        try (FSDirectory directory = FSDirectory.open(dir);
                IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig())) {
            writer.commit();
        }
        return dir;
    }
}
