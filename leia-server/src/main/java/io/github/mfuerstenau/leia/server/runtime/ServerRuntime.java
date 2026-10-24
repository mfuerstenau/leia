package io.github.mfuerstenau.leia.server.runtime;

import io.github.mfuerstenau.leia.runtime.Analysis;
import io.github.mfuerstenau.leia.runtime.Check;
import io.github.mfuerstenau.leia.runtime.Commits;
import io.github.mfuerstenau.leia.runtime.DirectoryKind;
import io.github.mfuerstenau.leia.runtime.Extensions;
import io.github.mfuerstenau.leia.runtime.LuceneRuntime;
import io.github.mfuerstenau.leia.runtime.RuntimeIndex;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/**
 * A runtime on the Lucene of an Elasticsearch or OpenSearch installation, with its codecs and formats
 * (ADR 0090): leia's adapter for that Lucene's major, in a class loader over the adapter and the
 * jars of the installation's {@code lib/}. It reads the indexes of that major and the one before,
 * whatever the adapter says, and has no analysis tab nor lists that {@code --jar} reloads: those
 * stay with leia's own runtimes. Its calls run with the server's class loader as the thread's context
 * class loader, through which Elasticsearch finds some of its classes; {@code CurrentIndex} sets it
 * for the calls of an open index.
 */
public final class ServerRuntime implements LuceneRuntime {

    private final LuceneRuntime delegate;
    private final Path home;
    private final Optional<String> label;
    private final LuceneJarVersion lucene;
    private final String builtFor;
    private final ClassLoader loader;

    /**
     * @param delegate leia's adapter, loaded with the installation's Lucene
     * @param home the installation, or its {@code lib/} as it was given
     * @param label the product and version, such as {@code Elasticsearch 8.15.3}, if {@code lib/}
     *     says
     * @param lucene the version of the installation's {@code lucene-core}
     * @param builtFor the Lucene version the adapter is built for, such as {@code 9.12.3}
     * @param loader the class loader of the adapter and the server's jars
     */
    ServerRuntime(
            LuceneRuntime delegate,
            Path home,
            Optional<String> label,
            LuceneJarVersion lucene,
            String builtFor,
            ClassLoader loader) {
        this.delegate = delegate;
        this.home = home;
        this.label = label;
        this.lucene = lucene;
        this.builtFor = builtFor;
        this.loader = loader;
    }

    /** The installation, or its {@code lib/}, as {@code --server} gave it. */
    public Path home() {
        return home;
    }

    /** The product and version, such as {@code Elasticsearch 8.15.3}; empty if {@code lib/} doesn't say. */
    public Optional<String> label() {
        return label;
    }

    /** How a person reads which server it is: its {@link #label()}, or else its {@link #home()}. */
    public String name() {
        return label.orElseGet(() -> "the server " + home);
    }

    /** The version of the installation's {@code lucene-core} jar. */
    public LuceneJarVersion lucene() {
        return lucene;
    }

    /** The Lucene version the adapter is built for, such as {@code 9.12.3}. */
    public String builtFor() {
        return builtFor;
    }

    /**
     * The class loader of the adapter and the server's jars: the context class loader while one of
     * its indexes is open, since Elasticsearch finds some of its classes through it.
     */
    public ClassLoader loader() {
        return loader;
    }

    /** The name of {@link #loader()}, as a stack frame of its classes has it. */
    String loaderName() {
        return loader.getName();
    }

    @Override
    public int apiVersion() {
        return delegate.apiVersion();
    }

    @Override
    public String luceneVersion() {
        return delegate.luceneVersion();
    }

    @Override
    public Set<Integer> indexMajors() {
        return Set.of(lucene.major() - 1, lucene.major());
    }

    @Override
    public RuntimeIndex open(List<Path> dirs, DirectoryKind kind) throws IOException {
        return inLoader(() -> delegate.open(dirs, kind));
    }

    @Override
    public Optional<Commits> commits() {
        return delegate.commits().map(commits -> new Commits() {
            @Override
            public CommitList commits(Path dir, DirectoryKind kind) throws IOException {
                return inLoader(() -> commits.commits(dir, kind));
            }

            @Override
            public Optional<CommitDetails> commit(Path dir, DirectoryKind kind, long generation) throws IOException {
                return inLoader(() -> commits.commit(dir, kind, generation));
            }
        });
    }

    @Override
    public Optional<Check> check() {
        return delegate.check().map(check -> new Check() {
            @Override
            public boolean hasLevels() {
                return check.hasLevels();
            }

            @Override
            public CheckResult check(
                    Path dir, DirectoryKind kind, OptionalInt level, Consumer<String> line, LongConsumer progress) {
                return inLoader(() -> check.check(dir, kind, level, line, progress));
            }
        });
    }

    /** A call of the adapter, which may throw {@code E}. */
    @FunctionalInterface
    private interface Call<T, E extends Exception> {
        T run() throws E;
    }

    /** Runs {@code call} with the server's class loader as the thread's context class loader. */
    private <T, E extends Exception> T inLoader(Call<T, E> call) throws E {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            return call.run();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    @Override
    public Optional<Analysis> analysis() {
        return Optional.empty();
    }

    @Override
    public Optional<Extensions> extensions() {
        return Optional.empty();
    }

    @Override
    public String toString() {
        return name() + " (Lucene " + lucene.version() + ")";
    }
}
