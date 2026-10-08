package io.github.mfuerstenau.leia.protocol.index;

/**
 * The Lucene {@code Directory} an index is read through (ADR 0020); serialized as its name. Each
 * opens without locking.
 */
public enum DirectoryImpl {
    /** {@code FSDirectory.open}: Lucene's choice for the platform, {@code MMapDirectory} on 64-bit. */
    FS,
    /** {@code MMapDirectory}. */
    MMAP,
    /** {@code NIOFSDirectory}. */
    NIO
}
