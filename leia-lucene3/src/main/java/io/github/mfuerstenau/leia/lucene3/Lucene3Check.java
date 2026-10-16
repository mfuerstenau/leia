package io.github.mfuerstenau.leia.lucene3;

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
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import org.apache.lucene.index.CheckIndex;
import org.apache.lucene.store.BufferedIndexInput;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.store.NoLockFactory;

/**
 * Lucene 3.6's {@code CheckIndex}, read-only (ADR 0045): the directory it reads refuses every write,
 * takes no lock, and {@code fixIndex} is never called. The check stops when {@code progress} throws:
 * each read of a buffer and each line of the report asks it.
 *
 * <p>3.6 catches every {@link Throwable} of a segment's check and goes on with the next segment, so
 * what {@code progress} threw is kept, thrown again at once by every later read, and rethrown when
 * {@code checkIndex} returns. Nothing calls {@link Thread#interrupt} (AGENTS.md, hard rule 7).
 */
final class Lucene3Check implements Check {

    /** {@inheritDoc} 3.6 checks at one level. */
    @Override
    public boolean hasLevels() {
        return false;
    }

    @Override
    public CheckResult check(
            Path dir, DirectoryKind kind, OptionalInt level, Consumer<String> line, LongConsumer progress) {
        Cancellation cancellation = new Cancellation(progress);
        CheckReport report = new CheckReport(line, cancellation);
        CheckIndex.Status status;
        try (Directory directory = new ReadOnlyDirectory(Directories.open(dir, kind), cancellation);
                PrintStream infoStream = new PrintStream(report, true, StandardCharsets.UTF_8)) {
            CheckIndex checker = new CheckIndex(directory);
            checker.setInfoStream(infoStream);
            status = checker.checkIndex();
            infoStream.flush();
            report.end();
        } catch (IOException | RuntimeException e) {
            // A check that was cancelled may fail in any way; it is still cancelled.
            cancellation.rethrow();
            report.end();
            return CheckResult.failed(Failures.describe(e));
        }
        cancellation.rethrow();
        return result(status);
    }

    private static CheckResult result(CheckIndex.Status status) {
        List<SegmentCheck> segments = new ArrayList<>();
        for (CheckIndex.Status.SegmentInfoStatus segment : status.segmentInfos) {
            segments.add(new SegmentCheck(
                    segment.name,
                    segment.docCount,
                    Optional.empty(),
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
        for (Throwable error : errors) {
            if (error != null) {
                return Optional.of(Failures.describe(error));
            }
        }
        // 3.6 reports why on the output only.
        return segment.openReaderPassed ? Optional.empty() : Optional.of("open reader failed");
    }

    /** A directory that only reads, without locks, and whose inputs ask whether to go on. */
    private static final class ReadOnlyDirectory extends Directory {
        private final Directory delegate;
        private final Cancellation cancellation;

        ReadOnlyDirectory(Directory delegate, Cancellation cancellation) throws IOException {
            this.delegate = delegate;
            this.cancellation = cancellation;
            setLockFactory(NoLockFactory.getNoLockFactory());
        }

        @Override
        public String[] listAll() throws IOException {
            return delegate.listAll();
        }

        @Override
        public boolean fileExists(String name) throws IOException {
            return delegate.fileExists(name);
        }

        @Override
        public long fileModified(String name) throws IOException {
            return delegate.fileModified(name);
        }

        @Override
        public long fileLength(String name) throws IOException {
            return delegate.fileLength(name);
        }

        @Override
        public IndexInput openInput(String name) throws IOException {
            return new CancellableInput(name, delegate.openInput(name), cancellation);
        }

        @Override
        public IndexInput openInput(String name, int bufferSize) throws IOException {
            return new CancellableInput(name, delegate.openInput(name, bufferSize), cancellation);
        }

        @Override
        public void touchFile(String name) throws IOException {
            throw readOnly("touch", name);
        }

        @Override
        public void deleteFile(String name) throws IOException {
            throw readOnly("delete", name);
        }

        @Override
        public IndexOutput createOutput(String name) throws IOException {
            throw readOnly("write", name);
        }

        @Override
        public void sync(String name) throws IOException {
            throw readOnly("sync", name);
        }

        @Override
        public void sync(Collection<String> names) throws IOException {
            throw readOnly("sync", String.join(", ", names));
        }

        private static IOException readOnly(String what, String name) {
            return new IOException("leia is read-only: cannot " + what + " " + name);
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }

        @Override
        public String toString() {
            return delegate.toString();
        }
    }

    /**
     * Reads the wrapped input a buffer at a time and asks whether to go on before each: 3.6 reads
     * byte by byte, which the buffer keeps fast.
     */
    private static final class CancellableInput extends BufferedIndexInput {
        private IndexInput delegate;
        private final Cancellation cancellation;

        CancellableInput(String name, IndexInput delegate, Cancellation cancellation) {
            super(name);
            this.delegate = delegate;
            this.cancellation = cancellation;
        }

        @Override
        protected void readInternal(byte[] b, int offset, int length) throws IOException {
            cancellation.check();
            long position = getFilePointer();
            if (delegate.getFilePointer() != position) {
                delegate.seek(position);
            }
            delegate.readBytes(b, offset, length);
        }

        @Override
        protected void seekInternal(long pos) {
            // readInternal seeks the wrapped input.
        }

        @Override
        public long length() {
            return delegate.length();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }

        @Override
        public Object clone() {
            CancellableInput clone = (CancellableInput) super.clone();
            clone.delegate = (IndexInput) delegate.clone();
            return clone;
        }
    }
}
