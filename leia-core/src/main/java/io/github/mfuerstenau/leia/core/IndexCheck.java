package io.github.mfuerstenau.leia.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.apache.lucene.index.CheckIndex;
import org.apache.lucene.index.SegmentCommitInfo;
import org.apache.lucene.index.SegmentInfos;
import org.apache.lucene.store.Directory;

/**
 * Checks an index directory with Lucene's {@link CheckIndex} (ADR 0022): the latest commit, every
 * segment and, depending on the level, every file's checksum and every part of each segment. The
 * directory is opened for the check alone, read-only, and closed afterwards; no reader of an opened
 * index is used, so this works for indexes that a reader can't open. Nothing is written (AGENTS.md,
 * hard rule 1).
 */
public final class IndexCheck {

    /** The lowest level: the checksums of every file. Lucene's default. */
    public static final int MIN_LEVEL = CheckIndex.Level.MIN_VALUE;

    /** The highest level: the integrity checks plus the slow ones. */
    public static final int MAX_LEVEL = CheckIndex.Level.MAX_VALUE;

    /** How many causes {@link #describe} names at most. */
    private static final int MAX_CAUSES = 8;

    private IndexCheck() {}

    /**
     * The outcome of checking one index directory.
     *
     * @param path the index directory
     * @param error why it could not be checked at all, such as no {@code segments_N}; then nothing
     *     else is known
     * @param segmentsFile the {@code segments_N} file of the latest commit
     * @param clean whether no problem was found
     * @param missingSegments whether the latest commit (or an older one) could not be read
     * @param toolOutOfDate whether the index was written by a newer Lucene than leia's
     * @param segmentCount the segments of the latest commit
     * @param badSegments the segments that failed a test
     * @param lostDocs the documents in the bad segments
     * @param validCounter whether the commit's counter for segment names is above every segment's
     *     name
     * @param segments each segment of the latest commit, in commit order
     */
    public record Result(
            Path path,
            Optional<String> error,
            Optional<String> segmentsFile,
            boolean clean,
            boolean missingSegments,
            boolean toolOutOfDate,
            int segmentCount,
            int badSegments,
            int lostDocs,
            boolean validCounter,
            List<SegmentResult> segments) {

        static Result failed(Path path, String error) {
            return new Result(
                    path, Optional.of(error), Optional.empty(), false, false, false, 0, 0, 0, false, List.of());
        }
    }

    /**
     * The outcome of checking one segment.
     *
     * @param name the segment's name, such as {@code _0}
     * @param maxDoc its documents, deleted ones included
     * @param codec its codec, if its {@code .si} file could be read
     * @param compound whether it is a compound file
     * @param files the files it consists of
     * @param sizeBytes the size of those files
     * @param hasDeletions whether it has deleted documents
     * @param openReaderPassed whether a reader could open it
     * @param error the test it failed and why, with the causes; empty if it passed
     */
    public record SegmentResult(
            String name,
            int maxDoc,
            Optional<String> codec,
            boolean compound,
            int files,
            long sizeBytes,
            boolean hasDeletions,
            boolean openReaderPassed,
            Optional<String> error) {}

    /**
     * Checks the latest commit of the index in {@code dir} at {@code level}. The report that {@code
     * CheckIndex} writes goes to {@code output}, line by line, from one thread at a time. A commit
     * with several segments is checked by as many threads as there are processors; each segment's
     * report then comes when the segment is done.
     *
     * <p>{@code progress} gets the number of lines written so far, after every line and regularly
     * while the check reads, from any of the threads that check; it can stop the check by throwing
     * a {@link CancellationException}, which this method then throws. Threads are never
     * interrupted.
     *
     * @param level from {@link #MIN_LEVEL} to {@link #MAX_LEVEL}
     * @return the outcome; one with an {@link Result#error()} if the directory can't be opened or
     *     holds no commit
     * @throws IllegalArgumentException if {@code level} is out of range
     * @throws CancellationException if {@code progress} stopped the check
     */
    public static Result check(
            Path dir, DirectoryImpl directoryImpl, int level, Consumer<String> output, ScanProgress progress) {
        CheckIndex.Level.checkIfLevelInBounds(level);
        // Closed by the PrintStream that wraps it (ADR 0088)
        @SuppressWarnings("java:S2095")
        Report report = new Report(output, progress);
        try (Directory directory = new CheckDirectory(directoryImpl.open(dir), report);
                CheckIndex checker = new CheckIndex(directory);
                PrintStream infoStream = new PrintStream(report, true, StandardCharsets.UTF_8)) {
            checker.setLevel(level);
            checker.setFailFast(false);
            checker.setInfoStream(infoStream);
            List<String> commit = segmentNames(directory);
            CheckIndex.Status status = run(checker, commit.size());
            infoStream.flush();
            report.end();
            report.check();
            return result(dir, status, commit);
        } catch (CancellationException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            // A check that was cancelled may fail in any way; it is still cancelled.
            report.end();
            report.check();
            return Result.failed(dir, describe(e));
        }
    }

    /** Checks segments in parallel if there are several, on an executor that is never interrupted. */
    private static CheckIndex.Status run(CheckIndex checker, int segments) throws IOException {
        int threads = Math.min(Runtime.getRuntime().availableProcessors(), segments);
        if (threads < 2) {
            return checker.checkIndex(null, null);
        }
        ExecutorService executor = Executors.newFixedThreadPool(
                threads, Thread.ofPlatform().daemon().name("leia-check-", 1).factory());
        try {
            return checker.checkIndex(null, executor);
        } finally {
            // Not shutdownNow: an interrupted thread breaks NIOFSDirectory (AGENTS.md, hard rule 7).
            executor.shutdown();
        }
    }

    /** The segments of the latest commit, in order; none if it can't be read, which the check then reports. */
    private static List<String> segmentNames(Directory directory) {
        try {
            List<String> names = new ArrayList<>();
            for (SegmentCommitInfo info : SegmentInfos.readLatestCommit(directory)) {
                names.add(info.info.name);
            }
            return names;
        } catch (IOException | RuntimeException _) {
            return List.of();
        }
    }

    /**
     * The result, with the segments in the order of {@code commit}, if it names them: the parallel
     * check sorts them by size.
     */
    private static Result result(Path dir, CheckIndex.Status status, List<String> commit) {
        List<CheckIndex.Status.SegmentInfoStatus> checked = new ArrayList<>(status.segmentInfos);
        // A segment the commit doesn't name, which can't happen, goes last.
        checked.sort(Comparator.comparingInt(segment -> {
            int position = commit.indexOf(segment.name);
            return position < 0 ? Integer.MAX_VALUE : position;
        }));
        List<SegmentResult> segments = new ArrayList<>();
        for (CheckIndex.Status.SegmentInfoStatus segment : checked) {
            segments.add(new SegmentResult(
                    segment.name,
                    segment.maxDoc,
                    Optional.ofNullable(segment.codec).map(Object::toString),
                    segment.compound,
                    segment.numFiles,
                    Math.round(segment.sizeMB * 1024 * 1024),
                    segment.hasDeletions,
                    segment.openReaderPassed,
                    Optional.ofNullable(segment.error).map(IndexCheck::describe)));
        }
        return new Result(
                dir,
                Optional.empty(),
                Optional.ofNullable(status.segmentsFileName),
                status.clean,
                status.missingSegments,
                status.toolOutOfDate,
                status.numSegments,
                status.numBadSegments,
                status.totLoseDocCount,
                status.validCounter,
                List.copyOf(segments));
    }

    /** The message of {@code t}, followed by those of its causes. */
    static String describe(Throwable t) {
        StringBuilder text = new StringBuilder(message(t));
        int depth = 0;
        for (Throwable cause = t.getCause(); cause != null && depth < MAX_CAUSES; cause = cause.getCause(), depth++) {
            text.append("; caused by ")
                    .append(cause.getClass().getSimpleName())
                    .append(": ")
                    .append(message(cause));
        }
        return text.toString();
    }

    private static String message(Throwable t) {
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    /**
     * The report of a check: splits what {@code CheckIndex} prints into lines, passes them on, and asks
     * the job whether to stop, for the printing thread and the reading ones alike.
     */
    private static final class Report extends OutputStream implements CheckDirectory.Cancellation {

        private final Consumer<String> output;
        private final ScanProgress progress;
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();
        private long lines;
        private volatile boolean stopped;

        Report(Consumer<String> output, ScanProgress progress) {
            this.output = output;
            this.progress = progress;
        }

        @Override
        public synchronized void write(int b) {
            if (b == '\n') {
                emit();
            } else {
                line.write(b);
            }
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            int start = off;
            for (int i = off; i < off + len; i++) {
                if (b[i] == '\n') {
                    line.write(b, start, i - start);
                    emit();
                    start = i + 1;
                }
            }
            line.write(b, start, off + len - start);
        }

        /** Passes on what is left of the last line, if anything. */
        synchronized void end() {
            if (line.size() > 0) {
                emit();
            }
        }

        private void emit() {
            String text = line.toString(StandardCharsets.UTF_8);
            line.reset();
            output.accept(text.endsWith("\r") ? text.substring(0, text.length() - 1) : text);
            lines++;
            check();
        }

        @Override
        public boolean stopped() {
            return stopped;
        }

        @Override
        public synchronized void check() {
            if (stopped) {
                throw new CancellationException("Check cancelled");
            }
            try {
                progress.scanned(lines);
            } catch (CancellationException e) {
                stopped = true;
                throw e;
            }
        }
    }
}
