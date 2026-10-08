package io.github.mfuerstenau.leia.core;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FilterDirectory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.store.RandomAccessInput;

/**
 * The directory a check reads through (ADR 0022): read-only, and cancellable. Every method that
 * writes throws, so nothing can be written even by a {@code CheckIndex} that tries to (AGENTS.md,
 * hard rule 1). Its inputs check {@link Cancellation} on positioning and bulk reads, in whichever
 * thread reads, so a check stops within a few reads; threads are never interrupted (hard rule 7).
 */
final class CheckDirectory extends FilterDirectory {

    /** How many positioning and bulk reads of an input go by between two checks with the job. */
    static final int CHECK_INTERVAL = 1024;

    private final Cancellation cancellation;

    CheckDirectory(Directory in, Cancellation cancellation) {
        super(in);
        this.cancellation = cancellation;
    }

    /** Whether a check should stop, asked by the threads that read. */
    interface Cancellation {

        /** Whether the check was found cancelled before; cheap, so it is asked on every read. */
        boolean stopped();

        /**
         * Asks the job whether the check is cancelled.
         *
         * @throws CancellationException if it is
         */
        void check();
    }

    @Override
    public IndexInput openInput(String name, IOContext context) throws IOException {
        cancellation.check();
        return new CheckInput(in.openInput(name, context), cancellation);
    }

    @Override
    public void deleteFile(String name) {
        throw readOnly("delete " + name);
    }

    @Override
    public IndexOutput createOutput(String name, IOContext context) {
        throw readOnly("create " + name);
    }

    @Override
    public IndexOutput createTempOutput(String prefix, String suffix, IOContext context) {
        throw readOnly("create a temporary file " + prefix);
    }

    @Override
    public void sync(Collection<String> names) {
        throw readOnly("sync " + names);
    }

    @Override
    public void rename(String source, String dest) {
        throw readOnly("rename " + source);
    }

    @Override
    public void syncMetaData() {
        throw readOnly("sync the directory");
    }

    private static UnsupportedOperationException readOnly(String what) {
        return new UnsupportedOperationException(
                "Read-only: leia never writes to an index directory, so cannot " + what);
    }

    /**
     * An input that checks the cancellation on {@code seek}, {@code readBytes}, {@code skipBytes},
     * {@code clone} and {@code slice}, and passes every other call on unchanged, so the reads of
     * single values stay as fast as the wrapped input's. Like any {@link IndexInput}, an instance is
     * used by one thread at a time, so its counter needs no synchronization.
     */
    private static final class CheckInput extends IndexInput {

        private final IndexInput in;
        private final Cancellation cancellation;
        private int calls;

        CheckInput(IndexInput in, Cancellation cancellation) {
            super(in.toString());
            this.in = in;
            this.cancellation = cancellation;
        }

        private void tick() {
            if (cancellation.stopped() || ++calls % CHECK_INTERVAL == 0) {
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
        public CheckInput clone() {
            tick();
            return new CheckInput(in.clone(), cancellation);
        }

        @Override
        public IndexInput slice(String sliceDescription, long offset, long length) throws IOException {
            tick();
            return new CheckInput(in.slice(sliceDescription, offset, length), cancellation);
        }

        @Override
        public IndexInput slice(String sliceDescription, long offset, long length, IOContext context)
                throws IOException {
            tick();
            return new CheckInput(in.slice(sliceDescription, offset, length, context), cancellation);
        }

        @Override
        public RandomAccessInput randomAccessSlice(long offset, long length) throws IOException {
            tick();
            return in.randomAccessSlice(offset, length);
        }

        @Override
        public void prefetch(long offset, long length) throws IOException {
            in.prefetch(offset, length);
        }

        @Override
        public void updateIOContext(IOContext context) throws IOException {
            in.updateIOContext(context);
        }

        @Override
        public Optional<Boolean> isLoaded() {
            return in.isLoaded();
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
        public int readZInt() throws IOException {
            return in.readZInt();
        }

        @Override
        public long readZLong() throws IOException {
            return in.readZLong();
        }

        @Override
        public void readLongs(long[] dst, int offset, int length) throws IOException {
            in.readLongs(dst, offset, length);
        }

        @Override
        public void readInts(int[] dst, int offset, int length) throws IOException {
            in.readInts(dst, offset, length);
        }

        @Override
        public void readFloats(float[] floats, int offset, int len) throws IOException {
            in.readFloats(floats, offset, len);
        }

        @Override
        public String readString() throws IOException {
            return in.readString();
        }

        @Override
        public Map<String, String> readMapOfStrings() throws IOException {
            return in.readMapOfStrings();
        }

        @Override
        public Set<String> readSetOfStrings() throws IOException {
            return in.readSetOfStrings();
        }
    }
}
