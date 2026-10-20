package io.github.mfuerstenau.leia.server.jars;

import io.github.mfuerstenau.leia.runtime.Extensions;
import io.github.mfuerstenau.leia.runtime.LuceneRuntime;
import io.github.mfuerstenau.leia.server.runtime.Runtimes;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * The jars with analysis components, codecs or formats of their own (ADR 0016, ADR 0018), loaded at
 * startup or added later (ADR 0024), as a chain of class loaders: each load is a child of the one
 * before. The runtimes reload their lists of factories, codecs and formats from it (ADR 0068). Those
 * lists are global to the JVM, so the chain is too.
 */
public final class JarChain {

    private static final Logger LOG = Logger.getLogger(JarChain.class.getName());

    private static final String SUFFIX = ".jar";

    private static final Comparator<String> NAME_ORDER =
            String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder());

    // Written while holding the class's lock, read without it. The jars are written first, so a
    // reader that sees a loader also sees its jars.
    private static volatile ClassLoader classLoader = JarChain.class.getClassLoader();
    private static volatile List<Path> jars = List.of();

    private JarChain() {}

    /**
     * Loads the jars at {@code paths}, through a class loader whose parent is the one loaded before,
     * and has the runtimes reload their lists from it. A directory stands for the jars directly in
     * it. A jar that is already loaded isn't loaded again.
     *
     * @return the jars that were loaded, as absolute paths, in the order they were given, a
     *     directory's sorted by name
     * @throws JarException if a path is neither a jar nor a directory with one, or the factories,
     *     codecs or formats can't be loaded; nothing is loaded then
     */
    public static synchronized List<Path> load(List<Path> paths) throws JarException {
        List<Path> fresh = split(paths).fresh();
        install(fresh);
        return fresh;
    }

    /**
     * Loads the jars at {@code paths} as {@link #load} does, and finds out what they added (ADR
     * 0024): the names of the runtimes' lists and the presets that weren't there before.
     *
     * @throws JarException as {@link #load} does; nothing is loaded then
     */
    public static synchronized AddedJars add(List<Path> paths) throws JarException {
        Split split = split(paths);
        Extensions.Added added = install(split.fresh());
        return new AddedJars(split.fresh(), split.alreadyLoaded(), added);
    }

    /** The class loader that sees every loaded jar. */
    public static ClassLoader classLoader() {
        return classLoader;
    }

    /** The loaded jars, as absolute paths, in the order they were loaded. */
    public static List<Path> jars() {
        return jars;
    }

    /** The jars at the paths that are new, and those that are already loaded, without duplicates. */
    private record Split(List<Path> fresh, List<Path> alreadyLoaded) {}

    private static Split split(List<Path> paths) throws JarException {
        Set<Path> fresh = new LinkedHashSet<>();
        Set<Path> alreadyLoaded = new LinkedHashSet<>();
        for (Path path : paths) {
            for (Path jar : jars(path.toAbsolutePath().normalize())) {
                (jars.contains(jar) ? alreadyLoaded : fresh).add(jar);
            }
        }
        return new Split(List.copyOf(fresh), List.copyOf(alreadyLoaded));
    }

    /**
     * Loads {@code fresh} through a new child loader. The runtimes reload their lists only once each
     * could build private copies of them from the loader, so a jar that fails leaves no names behind.
     */
    private static Extensions.Added install(List<Path> fresh) throws JarException {
        if (fresh.isEmpty()) {
            return Extensions.Added.NONE;
        }
        URL[] urls = new URL[fresh.size()];
        for (int i = 0; i < urls.length; i++) {
            try {
                urls[i] = fresh.get(i).toUri().toURL();
            } catch (MalformedURLException e) {
                throw new JarException("not a jar: " + fresh.get(i), e);
            }
        }
        URLClassLoader loader = new URLClassLoader(urls, classLoader);
        List<Extensions> extensions = extensions(loader);
        try {
            for (Extensions extension : extensions) {
                extension.check(loader);
            }
        } catch (Extensions.InvalidJarException e) {
            close(loader);
            // Lucene reads the service files of every loaded jar again, by path.
            Optional<Path> gone =
                    jars.stream().filter(jar -> !Files.isRegularFile(jar)).findFirst();
            if (gone.isPresent()) {
                throw new JarException(
                        "the jar " + gone.get() + " was loaded before and is gone; restart leia to load more jars", e);
            }
            throw new JarException(e.getMessage(), e);
        }
        List<Path> all = Stream.concat(jars.stream(), fresh.stream()).toList();
        List<Extensions.Added> added = new ArrayList<>();
        try {
            for (Extensions extension : extensions) {
                added.add(extension.reload(loader, all));
            }
        } catch (Extensions.InvalidJarException e) {
            // The same lookups again, so they hardly fail now. If one did, the names of the lists
            // that were reloaded before it stay.
            throw new JarException(e.getMessage(), e);
        }
        jars = all;
        classLoader = loader;
        return merge(added);
    }

    /**
     * The lists that {@code loader} reloads: those of the runtimes it finds, a runtime of these very
     * jars included (ADR 0071).
     */
    private static List<Extensions> extensions(ClassLoader loader) {
        List<Extensions> extensions = new ArrayList<>();
        for (LuceneRuntime runtime : Runtimes.load(loader).runtimes()) {
            runtime.extensions().ifPresent(extensions::add);
        }
        return extensions;
    }

    /** The names that any of {@code added} has, each sorted, ignoring case. */
    private static Extensions.Added merge(List<Extensions.Added> added) {
        return new Extensions.Added(
                merge(added, Extensions.Added::analyzers),
                merge(added, Extensions.Added::charFilters),
                merge(added, Extensions.Added::tokenizers),
                merge(added, Extensions.Added::tokenFilters),
                merge(added, Extensions.Added::codecs),
                merge(added, Extensions.Added::postingsFormats),
                merge(added, Extensions.Added::docValuesFormats),
                merge(added, Extensions.Added::knnVectorsFormats));
    }

    private static List<String> merge(List<Extensions.Added> added, Function<Extensions.Added, List<String>> names) {
        return added.stream()
                .flatMap(each -> names.apply(each).stream())
                .distinct()
                .sorted(NAME_ORDER)
                .toList();
    }

    private static void close(URLClassLoader loader) {
        try {
            loader.close();
        } catch (IOException e) {
            LOG.log(Level.FINE, "Cannot close the class loader of jars that failed", e);
        }
    }

    private static List<Path> jars(Path path) throws JarException {
        if (Files.isDirectory(path)) {
            List<Path> jars;
            try (Stream<Path> files = Files.list(path)) {
                jars = files.filter(file -> isJar(file) && Files.isRegularFile(file))
                        .sorted()
                        .toList();
            } catch (IOException e) {
                throw new JarException("cannot read " + path + ": " + e.getMessage(), e);
            }
            if (jars.isEmpty()) {
                throw new JarException("no jars in " + path, null);
            }
            return jars;
        }
        if (!Files.exists(path)) {
            throw new JarException("no such jar or directory: " + path, null);
        }
        if (!isJar(path) || !Files.isRegularFile(path)) {
            throw new JarException("not a jar: " + path, null);
        }
        return List.of(path);
    }

    private static boolean isJar(Path file) {
        Path name = file.getFileName();
        return name != null && name.toString().toLowerCase(Locale.ROOT).endsWith(SUFFIX);
    }
}
