package io.github.mfuerstenau.leia.runtime;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
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
    int API_VERSION = 4;

    /**
     * The version of this interface the runtime was built against: {@code return API_VERSION;}, which
     * the compiler inlines.
     */
    int apiVersion();

    /** The version of its Lucene, such as {@code 5.5.5}. */
    String luceneVersion();

    /** The major versions of the indexes it reads, such as {@code {4, 5}}. */
    Set<Integer> indexMajors();

    /**
     * Opens the latest commit of each directory of {@code dirs} read-only, with a reader, through a
     * directory without locks, and combines them as one index, in the order given (ADR 0045). A
     * directory that fails to open is skipped and listed in {@link RuntimeIndex#skipped()}.
     *
     * @param dirs the index directories, at least one
     * @throws IOException if none of them can be read: the error of the first, naming its directory
     *     if there are several
     */
    RuntimeIndex open(List<Path> dirs, DirectoryKind kind) throws IOException;

    /** The commits of an index directory, read without a reader; empty if the runtime can't read them. */
    Optional<Commits> commits();

    /** Lucene's {@code CheckIndex}, read-only, without a reader; empty if the runtime has none. */
    Optional<Check> check();
}
