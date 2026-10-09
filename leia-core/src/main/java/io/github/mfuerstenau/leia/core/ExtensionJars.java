package io.github.mfuerstenau.leia.core;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.lucene.analysis.AnalysisSPILoader;
import org.apache.lucene.analysis.CharFilterFactory;
import org.apache.lucene.analysis.TokenFilterFactory;
import org.apache.lucene.analysis.TokenizerFactory;
import org.apache.lucene.codecs.Codec;
import org.apache.lucene.codecs.DocValuesFormat;
import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.codecs.PostingsFormat;
import org.apache.lucene.util.NamedSPILoader;

/**
 * Jars with analysis components, codecs or formats of their own: Lucene's other analysis modules, or
 * the user's factories, analyzers, codecs and formats (ADR 0016, ADR 0018), loaded at startup or
 * added later (ADR 0024). Lucene's lists of factories, codecs and formats are global to the JVM,
 * so these jars are too.
 */
public final class ExtensionJars {

    private static final Logger LOG = Logger.getLogger(ExtensionJars.class.getName());

    private static final String SUFFIX = ".jar";

    // Written while holding the class's lock, read without it: PresetAnalyzers reads them while
    // holding its own lock, which add() takes while holding this one. The jars are written first,
    // so a reader that sees a loader also sees its jars.
    private static volatile ClassLoader classLoader = ExtensionJars.class.getClassLoader();
    private static volatile List<Path> jars = List.of();

    private ExtensionJars() {}

    /**
     * Loads the jars at {@code paths}, through a class loader whose parent is the one loaded before,
     * and adds their char filters, tokenizers, token filters, codecs, postings formats, doc values
     * formats and KNN vectors formats to Lucene's lists. A factory, codec or format whose name is
     * taken is ignored. A directory stands for the jars directly in it. A jar that is already loaded
     * isn't loaded again.
     *
     * @return the jars that were loaded, as absolute paths, in the order they were given, a
     *     directory's sorted by name
     * @throws ExtensionJarException if a path is neither a jar nor a directory with one, or the
     *     factories, codecs or formats can't be loaded; nothing is loaded then
     */
    public static synchronized List<Path> load(List<Path> paths) throws ExtensionJarException {
        List<Path> fresh = split(paths).fresh();
        install(fresh);
        return fresh;
    }

    /**
     * Loads the jars at {@code paths} as {@link #load} does, and finds out what they added (ADR
     * 0024): the names of Lucene's lists and the presets that weren't there before.
     *
     * @throws ExtensionJarException as {@link #load} does; nothing is loaded then
     */
    public static synchronized AddedJars add(List<Path> paths) throws ExtensionJarException {
        Split split = split(paths);
        if (split.fresh().isEmpty()) {
            return new AddedJars(List.of(), split.alreadyLoaded(), JarComponents.NONE);
        }
        Names before = Names.now();
        install(split.fresh());
        return new AddedJars(split.fresh(), split.alreadyLoaded(), before.added(Names.now()));
    }

    /** The class loader that sees every loaded jar: the one that analysis loads classes by name with. */
    public static ClassLoader classLoader() {
        return classLoader;
    }

    /** The loaded jars, as absolute paths, in the order they were loaded. */
    public static List<Path> jars() {
        return jars;
    }

    /** The jars at the paths that are new, and those that are already loaded, without duplicates. */
    private record Split(List<Path> fresh, List<Path> alreadyLoaded) {}

    private static Split split(List<Path> paths) throws ExtensionJarException {
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
     * Loads {@code fresh} through a new child loader. Lucene's lists are reloaded only once private
     * copies of them could be built from the loader, so a jar that fails leaves no names behind.
     */
    private static void install(List<Path> fresh) throws ExtensionJarException {
        if (fresh.isEmpty()) {
            return;
        }
        URL[] urls = new URL[fresh.size()];
        for (int i = 0; i < urls.length; i++) {
            try {
                urls[i] = fresh.get(i).toUri().toURL();
            } catch (MalformedURLException e) {
                throw new ExtensionJarException("not a jar: " + fresh.get(i), e);
            }
        }
        URLClassLoader loader = new URLClassLoader(urls, classLoader);
        try {
            reload(loader, true);
        } catch (ExtensionJarException e) {
            close(loader);
            // Lucene reads the service files of every loaded jar again, by path.
            Optional<Path> gone =
                    jars.stream().filter(jar -> !Files.isRegularFile(jar)).findFirst();
            if (gone.isPresent()) {
                throw new ExtensionJarException(
                        "the jar " + gone.get() + " was loaded before and is gone; restart leia to load more jars", e);
            }
            throw e;
        }
        // The same lookups again, so they hardly fail now. If one did, the names of the lists that
        // were reloaded before it stay.
        reload(loader, false);
        jars = Stream.concat(jars.stream(), fresh.stream()).toList();
        classLoader = loader;
    }

    /** Reloads Lucene's seven lists from {@code loader}, or only builds copies of them in a dry run. */
    private static void reload(ClassLoader loader, boolean dryRun) throws ExtensionJarException {
        try {
            if (dryRun) {
                new AnalysisSPILoader<>(CharFilterFactory.class, loader);
                new AnalysisSPILoader<>(TokenizerFactory.class, loader);
                new AnalysisSPILoader<>(TokenFilterFactory.class, loader);
            } else {
                CharFilterFactory.reloadCharFilters(loader);
                TokenizerFactory.reloadTokenizers(loader);
                TokenFilterFactory.reloadTokenFilters(loader);
            }
        } catch (ServiceConfigurationError | LinkageError | RuntimeException e) {
            // A NoClassDefFoundError is most often a dependency that wasn't given.
            throw new ExtensionJarException("cannot load the analysis factories: " + e, e);
        }
        try {
            if (dryRun) {
                new NamedSPILoader<>(Codec.class, loader);
                new NamedSPILoader<>(PostingsFormat.class, loader);
                new NamedSPILoader<>(DocValuesFormat.class, loader);
                new NamedSPILoader<>(KnnVectorsFormat.class, loader);
            } else {
                Codec.reloadCodecs(loader);
                PostingsFormat.reloadPostingsFormats(loader);
                DocValuesFormat.reloadDocValuesFormats(loader);
                KnnVectorsFormat.reloadKnnVectorsFormat(loader);
            }
        } catch (ServiceConfigurationError | LinkageError | RuntimeException e) {
            throw new ExtensionJarException("cannot load the codecs and formats: " + e, e);
        }
    }

    private static void close(URLClassLoader loader) {
        try {
            loader.close();
        } catch (IOException e) {
            LOG.log(Level.FINE, "Cannot close the class loader of jars that failed", e);
        }
    }

    /** The names in Lucene's lists and the presets at one moment. */
    private record Names(
            Set<String> analyzers,
            Set<String> charFilters,
            Set<String> tokenizers,
            Set<String> tokenFilters,
            Set<String> codecs,
            Set<String> postingsFormats,
            Set<String> docValuesFormats,
            Set<String> knnVectorsFormats) {

        static Names now() {
            return new Names(
                    PresetAnalyzers.list().stream()
                            .map(AnalysisComponents.AvailableAnalyzer::className)
                            .collect(Collectors.toSet()),
                    CharFilterFactory.availableCharFilters(),
                    TokenizerFactory.availableTokenizers(),
                    TokenFilterFactory.availableTokenFilters(),
                    Codec.availableCodecs(),
                    PostingsFormat.availablePostingsFormats(),
                    DocValuesFormat.availableDocValuesFormats(),
                    KnnVectorsFormat.availableKnnVectorsFormats());
        }

        /** The names in {@code after} that aren't in this. */
        JarComponents added(Names after) {
            return new JarComponents(
                    added(analyzers, after.analyzers),
                    added(charFilters, after.charFilters),
                    added(tokenizers, after.tokenizers),
                    added(tokenFilters, after.tokenFilters),
                    added(codecs, after.codecs),
                    added(postingsFormats, after.postingsFormats),
                    added(docValuesFormats, after.docValuesFormats),
                    added(knnVectorsFormats, after.knnVectorsFormats));
        }

        private static List<String> added(Set<String> before, Set<String> after) {
            return after.stream()
                    .filter(name -> !before.contains(name))
                    .sorted(String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder()))
                    .toList();
        }
    }

    private static List<Path> jars(Path path) throws ExtensionJarException {
        if (Files.isDirectory(path)) {
            List<Path> jars;
            try (Stream<Path> files = Files.list(path)) {
                jars = files.filter(file -> isJar(file) && Files.isRegularFile(file))
                        .sorted()
                        .toList();
            } catch (IOException e) {
                throw new ExtensionJarException("cannot read " + path + ": " + e.getMessage(), e);
            }
            if (jars.isEmpty()) {
                throw new ExtensionJarException("no jars in " + path, null);
            }
            return jars;
        }
        if (!Files.exists(path)) {
            throw new ExtensionJarException("no such jar or directory: " + path, null);
        }
        if (!isJar(path) || !Files.isRegularFile(path)) {
            throw new ExtensionJarException("not a jar: " + path, null);
        }
        return List.of(path);
    }

    private static boolean isJar(Path file) {
        Path name = file.getFileName();
        return name != null && name.toString().toLowerCase(Locale.ROOT).endsWith(SUFFIX);
    }
}
