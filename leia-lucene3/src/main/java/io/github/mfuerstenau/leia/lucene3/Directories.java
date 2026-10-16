package io.github.mfuerstenau.leia.lucene3;

import io.github.mfuerstenau.leia.runtime.DirectoryKind;
import java.io.IOException;
import java.nio.file.Path;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.MMapDirectory;
import org.apache.lucene.store.NIOFSDirectory;
import org.apache.lucene.store.NoLockFactory;

/** Opens the directories of Lucene 3.6, without locks (AGENTS.md, hard rule 1). */
final class Directories {

    private Directories() {}

    static Directory open(Path dir, DirectoryKind kind) throws IOException {
        NoLockFactory noLocks = NoLockFactory.getNoLockFactory();
        return switch (kind) {
            case FS -> FSDirectory.open(dir.toFile(), noLocks);
            case MMAP -> new MMapDirectory(dir.toFile(), noLocks);
            case NIO -> new NIOFSDirectory(dir.toFile(), noLocks);
        };
    }

    /** The message of {@code e}, or its class if it has none. */
    static String message(Throwable e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
