package io.github.mfuerstenau.leia.core;

import io.github.mfuerstenau.leia.core.AnalysisComponents.Available;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.CharFilterFactory;
import org.apache.lucene.analysis.TokenFilterFactory;
import org.apache.lucene.analysis.TokenizerFactory;

/**
 * The analyzers that can be created by class name (ADR 0015): the public, concrete {@link Analyzer}
 * classes with a public no-argument constructor in the jars that analysis comes from, the analysis
 * jars included (ADR 0016).
 */
final class PresetAnalyzers {

    private static final Logger LOG = Logger.getLogger(PresetAnalyzers.class.getName());

    private static final String SUFFIX = ".class";

    private PresetAnalyzers() {}

    /** The presets that {@code loader} sees. */
    private record Scan(ClassLoader loader, Map<String, Class<? extends Analyzer>> byClassName) {}

    /** Found when first needed, and again once analysis jars have been loaded. */
    private static Scan scan;

    private static synchronized Map<String, Class<? extends Analyzer>> presets() {
        ClassLoader loader = AnalysisJars.classLoader();
        if (scan == null || scan.loader() != loader) {
            scan = new Scan(loader, scan(loader));
        }
        return scan.byClassName();
    }

    /** The presets, sorted by simple name, ignoring case, then by class name. */
    static List<Available> list() {
        return presets().values().stream()
                .map(type -> new Available(type.getSimpleName(), type.getName()))
                .sorted(Comparator.comparing(Available::name, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(Available::className))
                .toList();
    }

    /**
     * A new analyzer of the listed class {@code className}.
     *
     * @throws InvalidAnalyzerException if the class is not listed, or its constructor fails
     */
    static Analyzer create(String className) throws InvalidAnalyzerException {
        Class<? extends Analyzer> type = presets().get(className);
        if (type == null) {
            throw new InvalidAnalyzerException("no preset analyzer '" + className + "'", null);
        }
        try {
            return type.getConstructor().newInstance();
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
            throw new InvalidAnalyzerException(type.getSimpleName() + ": " + message, cause);
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new InvalidAnalyzerException(type.getSimpleName() + ": " + e, e);
        }
    }

    private static Map<String, Class<? extends Analyzer>> scan(ClassLoader loader) {
        long start = System.nanoTime();
        Set<String> names = new TreeSet<>();
        for (Path location : locations()) {
            try {
                names.addAll(classNames(location));
            } catch (IOException | UncheckedIOException e) {
                LOG.log(Level.WARNING, "Cannot read " + location + " for analyzers", e);
            }
        }
        Map<String, Class<? extends Analyzer>> presets = names.stream()
                .map(name -> preset(name, loader))
                .flatMap(Optional::stream)
                .collect(Collectors.toMap(Class::getName, Function.identity(), (a, b) -> a));
        LOG.fine(() ->
                "Found " + presets.size() + " preset analyzers in " + (System.nanoTime() - start) / 1_000_000 + " ms");
        return Map.copyOf(presets);
    }

    /**
     * The jar or directory of Lucene's {@code Analyzer} and of every analysis factory, and the
     * analysis jars.
     */
    private static Set<Path> locations() {
        Stream<Class<?>> factories = Stream.of(
                        CharFilterFactory.availableCharFilters().stream().map(CharFilterFactory::lookupClass),
                        TokenizerFactory.availableTokenizers().stream().map(TokenizerFactory::lookupClass),
                        TokenFilterFactory.availableTokenFilters().stream().map(TokenFilterFactory::lookupClass))
                .flatMap(Function.identity());
        Set<Path> locations = new LinkedHashSet<>();
        Stream.concat(Stream.of(Analyzer.class), factories)
                .forEach(type -> location(type).ifPresent(locations::add));
        locations.addAll(AnalysisJars.jars());
        return locations;
    }

    private static Optional<Path> location(Class<?> type) {
        CodeSource source = type.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Path.of(source.getLocation().toURI()));
        } catch (URISyntaxException | IllegalArgumentException e) {
            LOG.log(Level.FINE, "Cannot read the location of " + type.getName(), e);
            return Optional.empty();
        }
    }

    /** The top-level classes in the jar or directory whose simple name contains {@code Analyzer}. */
    private static List<String> classNames(Path location) throws IOException {
        List<String> paths = new ArrayList<>();
        if (Files.isDirectory(location)) {
            try (Stream<Path> files = Files.walk(location)) {
                files.filter(Files::isRegularFile)
                        .map(file -> location.relativize(file)
                                .toString()
                                .replace(location.getFileSystem().getSeparator(), "/"))
                        .forEach(paths::add);
            }
        } else {
            try (ZipFile jar = new ZipFile(location.toFile())) {
                jar.stream().map(ZipEntry::getName).forEach(paths::add);
            }
        }
        return paths.stream()
                .filter(path -> path.endsWith(SUFFIX) && !path.startsWith("META-INF/"))
                .map(path -> path.substring(0, path.length() - SUFFIX.length()))
                .filter(path -> {
                    String simpleName = path.substring(path.lastIndexOf('/') + 1);
                    return simpleName.contains("Analyzer") && !simpleName.contains("$");
                })
                .map(path -> path.replace('/', '.'))
                .toList();
    }

    /** The class, if it is a preset; it is loaded but not initialized. */
    private static Optional<Class<? extends Analyzer>> preset(String className, ClassLoader loader) {
        Class<?> type;
        try {
            type = Class.forName(className, false, loader);
        } catch (ClassNotFoundException | LinkageError e) {
            LOG.log(Level.FINE, "Skipping " + className, e);
            return Optional.empty();
        }
        int modifiers = type.getModifiers();
        if (!Analyzer.class.isAssignableFrom(type)
                || !Modifier.isPublic(modifiers)
                || Modifier.isAbstract(modifiers)
                || type.isMemberClass()) {
            return Optional.empty();
        }
        try {
            // Only a public constructor is found.
            type.getConstructor();
        } catch (NoSuchMethodException | LinkageError e) {
            return Optional.empty();
        }
        return Optional.of(type.asSubclass(Analyzer.class));
    }
}
