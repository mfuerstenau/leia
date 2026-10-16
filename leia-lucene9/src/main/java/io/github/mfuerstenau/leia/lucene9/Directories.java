package io.github.mfuerstenau.leia.lucene9;

import io.github.mfuerstenau.leia.runtime.DirectoryKind;
import java.io.IOException;
import java.nio.file.Path;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.MMapDirectory;
import org.apache.lucene.store.NIOFSDirectory;
import org.apache.lucene.store.NoLockFactory;

/**
 * Opens the directories of Lucene 9.12, without locks (AGENTS.md, hard rule 1). An {@link
 * MMapDirectory}, which {@code FS} gives on 64-bit, maps files as memory segments on Java 19 and
 * later, through the classes of the jar's {@code META-INF/versions}, and unmaps them by closing their
 * arena: it never touches {@code sun.misc.Unsafe}, so it needs no switch as leia-lucene5 and
 * leia-lucene7 have (ADR 0047, ADR 0049). 9.12 has no switch for a directory anyway: {@code
 * setUseUnmap} throws.
 */
final class Directories {

    private Directories() {}

    static Directory open(Path dir, DirectoryKind kind) throws IOException {
        return switch (kind) {
            case FS -> FSDirectory.open(dir, NoLockFactory.INSTANCE);
            case MMAP -> new MMapDirectory(dir, NoLockFactory.INSTANCE);
            case NIO -> new NIOFSDirectory(dir, NoLockFactory.INSTANCE);
        };
    }
}
