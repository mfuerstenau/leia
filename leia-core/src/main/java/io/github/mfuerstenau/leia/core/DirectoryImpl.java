package io.github.mfuerstenau.leia.core;

import java.io.IOException;
import java.nio.file.Path;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.MMapDirectory;
import org.apache.lucene.store.NIOFSDirectory;
import org.apache.lucene.store.NoLockFactory;

/**
 * The {@link Directory} an index is read through (ADR 0020). Each uses {@link NoLockFactory}, so no
 * lock file can be created (AGENTS.md, hard rule 1).
 */
public enum DirectoryImpl {
    /** {@link FSDirectory#open}: Lucene's choice for the platform, {@link MMapDirectory} on 64-bit. */
    FS,
    /** {@link MMapDirectory}: the index files are memory-mapped. */
    MMAP,
    /**
     * {@link NIOFSDirectory}: positional reads from file channels. A thread that reads through it
     * must never be interrupted, which closes the channel (AGENTS.md, hard rule 7).
     */
    NIO;

    /** Opens {@code dir} with this implementation and no locking. */
    Directory open(Path dir) throws IOException {
        return switch (this) {
            case FS -> FSDirectory.open(dir, NoLockFactory.INSTANCE);
            case MMAP -> new MMapDirectory(dir, NoLockFactory.INSTANCE);
            case NIO -> new NIOFSDirectory(dir, NoLockFactory.INSTANCE);
        };
    }
}
