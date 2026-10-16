package io.github.mfuerstenau.leia.lucene5;

import io.github.mfuerstenau.leia.runtime.DirectoryKind;
import java.io.IOException;
import java.nio.file.Path;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.MMapDirectory;
import org.apache.lucene.store.NIOFSDirectory;
import org.apache.lucene.store.NoLockFactory;

/** Opens the directories of Lucene 5.5, without locks (AGENTS.md, hard rule 1). */
final class Directories {

    private Directories() {}

    static Directory open(Path dir, DirectoryKind kind) throws IOException {
        return switch (kind) {
            case FS -> FSDirectory.open(dir, NoLockFactory.INSTANCE);
            case MMAP -> new MMapDirectory(dir, NoLockFactory.INSTANCE);
            case NIO -> new NIOFSDirectory(dir, NoLockFactory.INSTANCE);
        };
    }

    /** The message of {@code e}, or its class if it has none. */
    static String message(Throwable e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
