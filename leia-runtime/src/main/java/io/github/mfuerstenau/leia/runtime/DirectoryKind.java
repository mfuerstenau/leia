package io.github.mfuerstenau.leia.runtime;

/** The Lucene {@code Directory} to open an index through, as leia's {@code --directory} (ADR 0020). */
public enum DirectoryKind {
    /** Lucene's {@code FSDirectory.open}, which picks one. */
    FS,
    MMAP,
    NIO
}
