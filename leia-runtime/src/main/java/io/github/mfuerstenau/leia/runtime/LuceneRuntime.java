package io.github.mfuerstenau.leia.runtime;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

/**
 * A Lucene that reads indexes Lucene 10 can't, in a jar of its own (ADR 0044). leia finds it with
 * {@link java.util.ServiceLoader}, so an implementation is public, has a public no-arg constructor
 * and is named in {@code META-INF/services/io.github.mfuerstenau.leia.runtime.LuceneRuntime}.
 *
 * <p>leia calls {@link #apiVersion()} before any other method, and uses a runtime only if it returns
 * leia's {@link #API_VERSION}.
 */
public interface LuceneRuntime {

    /** The version of this interface. It changes with every change that breaks a runtime built before. */
    int API_VERSION = 1;

    /**
     * The version of this interface the runtime was built against: {@code return API_VERSION;}, which
     * the compiler inlines.
     */
    int apiVersion();

    /** The version of its Lucene, such as {@code 3.6.2}. */
    String luceneVersion();

    /** The major versions of the indexes it reads, such as {@code {2, 3}}. */
    Set<Integer> indexMajors();

    /**
     * Opens the latest commit of {@code dir} read-only, with a reader, through a directory without
     * locks.
     *
     * @throws IOException if the index can't be read
     */
    RuntimeIndex open(Path dir, DirectoryKind kind) throws IOException;
}
