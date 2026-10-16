package io.github.mfuerstenau.leia.lucene7;

import io.github.mfuerstenau.leia.runtime.DirectoryKind;
import java.io.IOException;
import java.nio.file.Path;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.MMapDirectory;
import org.apache.lucene.store.NIOFSDirectory;
import org.apache.lucene.store.NoLockFactory;

/** Opens the directories of Lucene 7.7, without locks (AGENTS.md, hard rule 1). */
final class Directories {

    private Directories() {}

    /**
     * Opens {@code dir}. An {@link MMapDirectory}, which {@code FS} gives on 64-bit, unmaps only if
     * the scripts allowed {@code sun.misc.Unsafe}, as in leia-lucene5: Java 25 warns about it on the JVM's own stderr,
     * past the redirect into the log (ADR 0047). Otherwise mapped files are released at GC.
     */
    static Directory open(Path dir, DirectoryKind kind) throws IOException {
        Directory directory = switch (kind) {
            case FS -> FSDirectory.open(dir, NoLockFactory.INSTANCE);
            case MMAP -> new MMapDirectory(dir, NoLockFactory.INSTANCE);
            case NIO -> new NIOFSDirectory(dir, NoLockFactory.INSTANCE);
        };
        if (directory instanceof MMapDirectory mmap) {
            mmap.setUseUnmap(unmap(System.getProperty("sun.misc.unsafe.memory.access")));
        }
        return directory;
    }

    /** Whether to unmap, given the value of {@code sun.misc.unsafe.memory.access} (ADR 0047). */
    static boolean unmap(String access) {
        return "allow".equals(access) && MMapDirectory.UNMAP_SUPPORTED;
    }

    /** The message of {@code e}, or its class if it has none. */
    static String message(Throwable e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
