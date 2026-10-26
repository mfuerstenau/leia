package io.github.mfuerstenau.leia.lucene9;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.mfuerstenau.leia.runtime.Check;
import io.github.mfuerstenau.leia.runtime.DirectoryKind;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** 9.12's check on more threads, as Lucene 10's (ADR 0022 §5, ADR 0049 §5). */
class Lucene9CheckTest {

    @TempDir
    Path tmp;

    private static Path testIndex(String name) {
        String dir = System.getProperty("leia.test.index." + name);
        if (dir == null) {
            throw new IllegalStateException("System property leia.test.index." + name + " is not set");
        }
        return Path.of(dir).toAbsolutePath();
    }

    private static Check check() {
        return new Lucene9Runtime().check().orElseThrow();
    }

    @Test
    void checksTheSegmentsOfACommitOnThreadsOfItsOwn() {
        assumeTrue(Runtime.getRuntime().availableProcessors() > 1, "one processor checks on one thread");
        Set<String> threads = ConcurrentHashMap.newKeySet();

        Check.CheckResult result = check().check(
                        testIndex("lucene8to9"),
                        DirectoryKind.FS,
                        Check.Options.atLevel(2),
                        _ -> {},
                        _ -> threads.add(Thread.currentThread().getName()));

        assertThat(result.clean()).isTrue();
        assertThat(result.segments()).extracting(Check.SegmentCheck::name).containsExactly("_0", "_1", "_2");
        assertThat(threads).anyMatch(name -> name.startsWith("leia-check-"));
    }

    @Test
    void oneThreadChecksOnTheCallingThreadAndOnlyTheSegmentsAskedFor() {
        assertThat(check().hasThreads()).isTrue();
        Set<String> threads = ConcurrentHashMap.newKeySet();

        Check.CheckResult result = check().check(
                        testIndex("lucene8to9"),
                        DirectoryKind.FS,
                        new Check.Options(OptionalInt.of(2), OptionalInt.of(1), Optional.of(List.of("_2", "_9"))),
                        _ -> {},
                        _ -> threads.add(Thread.currentThread().getName()));

        assertThat(result.clean()).isTrue();
        assertThat(result.segmentCount()).isEqualTo(3);
        assertThat(result.segments()).extracting(Check.SegmentCheck::name).containsExactly("_2");
        assertThat(threads).containsOnly(Thread.currentThread().getName());
    }

    @Test
    void checksAnIndexWithOneSegmentOnTheCallingThread() throws IOException {
        Path dir = writeIndex(tmp.resolve("index"), 2);
        Set<String> threads = ConcurrentHashMap.newKeySet();

        Check.CheckResult result = check().check(
                        dir,
                        DirectoryKind.FS,
                        Check.Options.atLevel(2),
                        _ -> {},
                        _ -> threads.add(Thread.currentThread().getName()));

        assertThat(result.clean()).isTrue();
        assertThat(threads).containsOnly(Thread.currentThread().getName());
    }

    @Test
    @Timeout(60)
    void stopsAParallelCheck() throws IOException {
        Path dir = writeIndex(tmp.resolve("index"), 200, 20, 2);
        CancellationException stop = new CancellationException("stop");
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> check().check(dir, DirectoryKind.NIO, Check.Options.atLevel(3), _ -> {}, _ -> {
                    if (calls.incrementAndGet() > 1) {
                        throw stop;
                    }
                }))
                .isSameAs(stop);
    }

    @Test
    void listsTheSegmentsInCommitOrderWhenTheyGetSmaller() throws IOException {
        Path dir = writeIndex(tmp.resolve("index"), 200, 20, 2);

        Check.CheckResult result = check().check(dir, DirectoryKind.FS, Check.Options.atLevel(2), _ -> {}, _ -> {});

        assertThat(result.clean()).isTrue();
        assertThat(result.segments()).extracting(Check.SegmentCheck::name).containsExactly("_0", "_1", "_2");
        assertThat(result.segments()).extracting(Check.SegmentCheck::maxDoc).containsExactly(200, 20, 2);
    }

    /** Writes a commit for each of {@code docs} with 9.12, one segment per commit, with that many documents. */
    private static Path writeIndex(Path dir, int... docs) throws IOException {
        try (FSDirectory directory = FSDirectory.open(dir);
                IndexWriter writer =
                        new IndexWriter(directory, new IndexWriterConfig().setMergePolicy(NoMergePolicy.INSTANCE))) {
            for (int segment = 0; segment < docs.length; segment++) {
                for (int i = 0; i < docs[segment]; i++) {
                    Document doc = new Document();
                    doc.add(new StringField("id", segment + "-" + i, Field.Store.YES));
                    doc.add(new TextField("body", "some text for segment " + segment, Field.Store.NO));
                    writer.addDocument(doc);
                }
                writer.commit();
            }
        }
        return dir;
    }
}
