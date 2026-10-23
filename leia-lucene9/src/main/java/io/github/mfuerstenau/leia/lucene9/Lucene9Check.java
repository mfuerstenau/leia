package io.github.mfuerstenau.leia.lucene9;

import io.github.mfuerstenau.leia.runtime.Check;
import io.github.mfuerstenau.leia.runtime.DirectoryKind;
import io.github.mfuerstenau.leia.runtime.support.Cancellation;
import io.github.mfuerstenau.leia.runtime.support.CheckReport;
import io.github.mfuerstenau.leia.runtime.support.Failures;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import org.apache.lucene.index.CheckIndex;
import org.apache.lucene.index.SegmentCommitInfo;
import org.apache.lucene.index.SegmentInfos;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FilterDirectory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.store.Lock;
import org.apache.lucene.store.RandomAccessInput;

/**
 * Lucene 9.12's {@code CheckIndex}, read-only (ADR 0049), as leia-lucene7 runs 7.7's: the directory it
 * reads refuses every write, its lock is one that locks nothing, and {@code exorciseIndex} is never
 * called. The levels map to 9.12's modes: 1 checks the checksums only, 2 is the default check, 3 adds
 * the slow checks, which include the term vectors against the postings. A commit with several
 * segments is checked as Lucene 10's (ADR 0022 §5): by as many threads as there are processors, at most
 * one per segment, on an executor leia owns, and each segment's report then comes when the segment is
 * done; with one segment, it runs on the job's thread. The segments of the result are in commit order,
 * which 9.12's parallel check doesn't keep. The check stops when {@code progress} throws: each line of
 * the report asks it, and the inputs, their clones and slices ask on positioning and bulk reads.
 *
 * <p>The threads share the {@link Cancellation} and the {@link CheckReport}, which are synchronized;
 * each {@link CancellableInput} belongs to one segment's reader, so one thread uses it at a time; and
 * 9.12 buffers each segment's report and prints it from the calling thread.
 *
 * <p>9.12 catches every {@link Throwable} of a segment's check and goes on with the next segment, so
 * what {@code progress} threw is kept, thrown again at once by every later read, and rethrown when
 * {@code checkIndex} returns. Nothing calls {@link Thread#interrupt} (AGENTS.md, hard rule 7).
 */
final class Lucene9Check implements Check {

    /** How many positioning and bulk reads of an input go by between two questions to {@code progress}. */
    static final int CHECK_INTERVAL = 1024;

    @Override
    public boolean hasLevels() {
        return true;
    }

    /** {@inheritDoc} The level defaults to 2. */
    @Override
    public CheckResult check(
            Path dir, DirectoryKind kind, OptionalInt level, Consumer<String> line, LongConsumer progress) {
        int checkLevel = level.orElse(2);
        Cancellation cancellation = new Cancellation(progress);
        CheckReport report = new CheckReport(line, cancellation);
        CheckIndex.Status status;
        List<String> commit;
        try (Directory directory = new ReadOnlyDirectory(Directories.open(dir, kind), cancellation);
                PrintStream infoStream = new PrintStream(report, true, StandardCharsets.UTF_8);
                CheckIndex checker = new CheckIndex(directory, NoLock.INSTANCE)) {
            checker.setInfoStream(infoStream, false);
            checker.setChecksumsOnly(checkLevel == 1);
            checker.setDoSlowChecks(checkLevel >= 3);
            commit = segmentNames(directory);
            status = run(checker, commit.size());
            infoStream.flush();
            report.end();
        } catch (IOException | RuntimeException e) {
            // A check that was cancelled may fail in any way; it is still cancelled.
            cancellation.rethrow();
            report.end();
            return CheckResult.failed(Failures.describe(e));
        }
        cancellation.rethrow();
        return result(status, commit);
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
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    /**
     * The result, with the segments in the order of {@code commit}, if it names them: the parallel
     * check sorts them by size.
     */
    private static CheckResult result(CheckIndex.Status status, List<String> commit) {
        List<CheckIndex.Status.SegmentInfoStatus> checked = new ArrayList<>(status.segmentInfos);
        // A segment the commit doesn't name, which can't happen, goes last.
        checked.sort(Comparator.comparingInt(segment -> {
            int position = commit.indexOf(segment.name);
            return position < 0 ? Integer.MAX_VALUE : position;
        }));
        List<SegmentCheck> segments = new ArrayList<>();
        for (CheckIndex.Status.SegmentInfoStatus segment : checked) {
            segments.add(new SegmentCheck(
                    segment.name,
                    segment.maxDoc,
                    Optional.ofNullable(segment.codec).map(codec -> codec.getName()),
                    segment.compound,
                    segment.numFiles,
                    Math.round(segment.sizeMB * 1024 * 1024),
                    segment.hasDeletions,
                    segment.openReaderPassed,
                    error(segment)));
        }
        return new CheckResult(
                status.clean,
                Optional.empty(),
                Optional.ofNullable(status.segmentsFileName),
                status.missingSegments,
                status.toolOutOfDate,
                status.numSegments,
                status.numBadSegments,
                status.totLoseDocCount,
                status.validCounter,
                List.copyOf(segments));
    }

    /** The first test that {@code segment} failed, and why; empty if it passed them all. */
    private static Optional<String> error(CheckIndex.Status.SegmentInfoStatus segment) {
        List<Throwable> errors = new ArrayList<>();
        if (segment.liveDocStatus != null) {
            errors.add(segment.liveDocStatus.error);
        }
        if (segment.fieldInfoStatus != null) {
            errors.add(segment.fieldInfoStatus.error);
        }
        if (segment.fieldNormStatus != null) {
            errors.add(segment.fieldNormStatus.error);
        }
        if (segment.termIndexStatus != null) {
            errors.add(segment.termIndexStatus.error);
        }
        if (segment.storedFieldStatus != null) {
            errors.add(segment.storedFieldStatus.error);
        }
        if (segment.termVectorStatus != null) {
            errors.add(segment.termVectorStatus.error);
        }
        if (segment.docValuesStatus != null) {
            errors.add(segment.docValuesStatus.error);
        }
        if (segment.pointsStatus != null) {
            errors.add(segment.pointsStatus.error);
        }
        if (segment.indexSortStatus != null) {
            errors.add(segment.indexSortStatus.error);
        }
        if (segment.vectorValuesStatus != null) {
            errors.add(segment.vectorValuesStatus.error);
        }
        if (segment.softDeletesStatus != null) {
            errors.add(segment.softDeletesStatus.error);
        }
        for (Throwable error : errors) {
            if (error != null) {
                return Optional.of(Failures.describe(error));
            }
        }
        // 9.12 reports why on the output only.
        return segment.openReaderPassed ? Optional.empty() : Optional.of("open reader failed");
    }

    /** The lock of the check: it locks nothing, so the index is never locked (AGENTS.md, hard rule 1). */
    private static final class NoLock extends Lock {
        static final NoLock INSTANCE = new NoLock();

        @Override
        public void close() {}

        @Override
        public void ensureValid() {}

        @Override
        public String toString() {
            return "no lock";
        }
    }

    /** A directory that only reads, without locks, and whose inputs ask whether to go on. */
    private static final class ReadOnlyDirectory extends FilterDirectory {
        private final Cancellation cancellation;

        ReadOnlyDirectory(Directory in, Cancellation cancellation) {
            super(in);
            this.cancellation = cancellation;
        }

        @Override
        public IndexInput openInput(String name, IOContext context) throws IOException {
            cancellation.check();
            return new CancellableInput(in.openInput(name, context), cancellation);
        }

        @Override
        public Lock obtainLock(String name) {
            return NoLock.INSTANCE;
        }

        @Override
        public void deleteFile(String name) throws IOException {
            throw readOnly("delete " + name);
        }

        @Override
        public IndexOutput createOutput(String name, IOContext context) throws IOException {
            throw readOnly("create " + name);
        }

        @Override
        public void sync(Collection<String> names) throws IOException {
            throw readOnly("sync " + names);
        }

        @Override
        public IndexOutput createTempOutput(String prefix, String suffix, IOContext context) throws IOException {
            throw readOnly("create a temporary file " + prefix);
        }

        @Override
        public void syncMetaData() throws IOException {
            throw readOnly("sync the directory");
        }

        @Override
        public void rename(String source, String dest) throws IOException {
            throw readOnly("rename " + source);
        }

        private static IOException readOnly(String what) {
            return new IOException("Read-only: leia never writes to an index directory, so cannot " + what);
        }
    }

    /**
     * An input that asks whether to go on on {@code seek}, {@code readBytes}, {@code skipBytes},
     * {@code clone} and {@code slice}, and passes every other call on unchanged, so the reads of
     * single values stay as fast as the wrapped input's. An input is used by one thread at a time, so
     * its counter needs no synchronization.
     */
    private static final class CancellableInput extends IndexInput {
        private final IndexInput in;
        private final Cancellation cancellation;
        private int calls;

        CancellableInput(IndexInput in, Cancellation cancellation) {
            super(in.toString());
            this.in = in;
            this.cancellation = cancellation;
        }

        private void tick() {
            cancellation.rethrow();
            if (++calls % CHECK_INTERVAL == 0) {
                cancellation.check();
            }
        }

        @Override
        public void close() throws IOException {
            in.close();
        }

        @Override
        public long getFilePointer() {
            return in.getFilePointer();
        }

        @Override
        public void seek(long pos) throws IOException {
            tick();
            in.seek(pos);
        }

        @Override
        public void skipBytes(long numBytes) throws IOException {
            tick();
            in.skipBytes(numBytes);
        }

        @Override
        public long length() {
            return in.length();
        }

        @Override
        public CancellableInput clone() {
            tick();
            return new CancellableInput(in.clone(), cancellation);
        }

        @Override
        public IndexInput slice(String sliceDescription, long offset, long length) throws IOException {
            tick();
            return new CancellableInput(in.slice(sliceDescription, offset, length), cancellation);
        }

        @Override
        public RandomAccessInput randomAccessSlice(long offset, long length) throws IOException {
            tick();
            return in.randomAccessSlice(offset, length);
        }

        @Override
        public void readBytes(byte[] b, int offset, int len) throws IOException {
            tick();
            in.readBytes(b, offset, len);
        }

        @Override
        public void readBytes(byte[] b, int offset, int len, boolean useBuffer) throws IOException {
            tick();
            in.readBytes(b, offset, len, useBuffer);
        }

        @Override
        public byte readByte() throws IOException {
            return in.readByte();
        }

        @Override
        public short readShort() throws IOException {
            return in.readShort();
        }

        @Override
        public int readInt() throws IOException {
            return in.readInt();
        }

        @Override
        public long readLong() throws IOException {
            return in.readLong();
        }

        @Override
        public int readVInt() throws IOException {
            return in.readVInt();
        }

        @Override
        public long readVLong() throws IOException {
            return in.readVLong();
        }

        @Override
        public String readString() throws IOException {
            return in.readString();
        }
    }
}
