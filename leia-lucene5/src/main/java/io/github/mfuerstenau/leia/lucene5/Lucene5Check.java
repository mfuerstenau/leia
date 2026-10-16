package io.github.mfuerstenau.leia.lucene5;

import io.github.mfuerstenau.leia.runtime.Check;
import io.github.mfuerstenau.leia.runtime.DirectoryKind;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import org.apache.lucene.index.CheckIndex;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FilterDirectory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.store.Lock;
import org.apache.lucene.store.RandomAccessInput;
import org.apache.lucene.util.Version;

/**
 * Lucene 5.5's {@code CheckIndex}, read-only (ADR 0046): the directory it reads refuses every write,
 * its lock is one that locks nothing, and {@code exorciseIndex} is never called. The levels map to
 * 5.5's modes: 1 checks the checksums only, 2 is the default check, 3 also checks the term vectors
 * against the postings. The check stops when {@code progress} throws: each line of the report asks
 * it, and the inputs, their clones and slices ask on positioning and bulk reads.
 *
 * <p>5.5 catches every {@link Throwable} of a segment's check and goes on with the next segment, so
 * what {@code progress} threw is kept, thrown again at once by every later read, and rethrown when
 * {@code checkIndex} returns. Nothing calls {@link Thread#interrupt} (AGENTS.md, hard rule 7).
 */
final class Lucene5Check implements Check {

    /** How many positioning and bulk reads of an input go by between two questions to {@code progress}. */
    static final int CHECK_INTERVAL = 1024;

    /** How many causes {@link #describe} names at most. */
    private static final int MAX_CAUSES = 8;

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
        Report report = new Report(line, cancellation);
        CheckIndex.Status status;
        try (Directory directory = new ReadOnlyDirectory(Directories.open(dir, kind), cancellation);
                PrintStream infoStream = new PrintStream(report, true, StandardCharsets.UTF_8);
                CheckIndex checker = new CheckIndex(directory, NoLock.INSTANCE)) {
            checker.setInfoStream(infoStream, false);
            checker.setChecksumsOnly(checkLevel == 1);
            checker.setCrossCheckTermVectors(checkLevel >= 3);
            status = checker.checkIndex();
            if (checkLevel == 1) {
                noChecksums(status, infoStream);
            }
            infoStream.flush();
            report.end();
        } catch (IOException | RuntimeException e) {
            // A check that was cancelled may fail in any way; it is still cancelled.
            cancellation.rethrow();
            report.end();
            return CheckResult.failed(describe(e));
        }
        cancellation.rethrow();
        return result(status);
    }

    /** Says which segments level 1 checked nothing in: those before 4.8, which have no checksums. */
    private static void noChecksums(CheckIndex.Status status, PrintStream out) {
        for (CheckIndex.Status.SegmentInfoStatus segment : status.segmentInfos) {
            if (segment.version != null && !segment.version.onOrAfter(Version.LUCENE_4_8_0)) {
                out.println("Segment " + segment.name + " was written by Lucene " + segment.version
                        + ", before checksums (4.8): checking the checksums checked nothing of it");
            }
        }
    }

    private static CheckResult result(CheckIndex.Status status) {
        List<SegmentCheck> segments = new ArrayList<>();
        for (CheckIndex.Status.SegmentInfoStatus segment : status.segmentInfos) {
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
                status.missingSegments || status.cantOpenSegments,
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
        for (Throwable error : errors) {
            if (error != null) {
                return Optional.of(describe(error));
            }
        }
        // 5.5 reports why on the output only.
        return segment.openReaderPassed ? Optional.empty() : Optional.of("open reader failed");
    }

    /** The message of {@code t}, followed by those of its causes. */
    private static String describe(Throwable t) {
        StringBuilder text = new StringBuilder(Directories.message(t));
        int depth = 0;
        for (Throwable cause = t.getCause(); cause != null && depth < MAX_CAUSES; cause = cause.getCause(), depth++) {
            text.append("; caused by ")
                    .append(cause.getClass().getSimpleName())
                    .append(": ")
                    .append(Directories.message(cause));
        }
        return text.toString();
    }

    /** Asks {@code progress} whether to go on, and remembers the first time it said no. */
    private static final class Cancellation {
        private final LongConsumer progress;
        private volatile RuntimeException stopped;
        private long lines;

        Cancellation(LongConsumer progress) {
            this.progress = progress;
        }

        synchronized void line() {
            lines++;
            check();
        }

        /** Throws again what {@code progress} threw, if it did; cheap, so it is asked on every read. */
        void rethrow() {
            if (stopped != null) {
                throw stopped;
            }
        }

        synchronized void check() {
            rethrow();
            try {
                progress.accept(lines);
            } catch (RuntimeException e) {
                stopped = e;
                throw e;
            }
        }
    }

    /** Splits what {@code CheckIndex} prints into lines and passes them on. */
    private static final class Report extends OutputStream {
        private final Consumer<String> output;
        private final Cancellation cancellation;
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();

        Report(Consumer<String> output, Cancellation cancellation) {
            this.output = output;
            this.cancellation = cancellation;
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
                output.accept(text());
            }
        }

        private void emit() {
            output.accept(text());
            cancellation.line();
        }

        private String text() {
            String text = line.toString(StandardCharsets.UTF_8);
            line.reset();
            return text.endsWith("\r") ? text.substring(0, text.length() - 1) : text;
        }
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
        public void renameFile(String source, String dest) throws IOException {
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
