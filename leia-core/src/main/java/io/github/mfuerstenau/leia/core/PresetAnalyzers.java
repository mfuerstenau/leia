package io.github.mfuerstenau.leia.core;

import io.github.mfuerstenau.leia.core.AnalysisComponents.AvailableAnalyzer;
import io.github.mfuerstenau.leia.core.AnalyzerSpec.WordSet;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
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
import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.CharFilterFactory;
import org.apache.lucene.analysis.StopwordAnalyzerBase;
import org.apache.lucene.analysis.TokenFilterFactory;
import org.apache.lucene.analysis.TokenizerFactory;
import org.apache.lucene.analysis.WordlistLoader;

/**
 * The analyzers that can be created by class name (ADR 0015): the public, concrete {@link Analyzer}
 * classes with a public no-argument or stop words constructor in the jars that analysis comes from,
 * the extension jars included (ADR 0016). By Lucene's convention, a constructor that takes one
 * {@link CharArraySet} takes the stop words, and one that takes two takes the stop words and the
 * stem exclusions (ADR 0017).
 */
final class PresetAnalyzers {

    private static final Logger LOG = Logger.getLogger(PresetAnalyzers.class.getName());

    private static final String SUFFIX = ".class";

    private PresetAnalyzers() {}

    /** The presets that {@code loader} sees. */
    private record Scan(ClassLoader loader, Map<String, Preset> byClassName) {}

    /**
     * A preset's class and its constructors: without arguments, with the stop words, and with the
     * stop words and the stem exclusions; at least one of the first two is present.
     *
     * @param defaultStopSet the static {@code getDefaultStopSet()}, for an analyzer that is not a
     *     {@link StopwordAnalyzerBase}
     */
    private record Preset(
            Class<? extends Analyzer> type,
            Optional<Constructor<? extends Analyzer>> noArguments,
            Optional<Constructor<? extends Analyzer>> stopwords,
            Optional<Constructor<? extends Analyzer>> stopwordsAndStemExclusion,
            Optional<Method> defaultStopSet) {

        String name() {
            return type.getSimpleName();
        }

        WordSetSupport stopwordSupport() {
            if (stopwords.isEmpty()) {
                return WordSetSupport.NONE;
            }
            return noArguments.isPresent() ? WordSetSupport.OPTIONAL : WordSetSupport.REQUIRED;
        }

        WordSetSupport stemExclusionSupport() {
            return stopwordsAndStemExclusion.isPresent() && hasDefaultStopwords()
                    ? WordSetSupport.OPTIONAL
                    : WordSetSupport.NONE;
        }

        /** Whether the default stop words can be found, for stem exclusions without stop words. */
        boolean hasDefaultStopwords() {
            return (StopwordAnalyzerBase.class.isAssignableFrom(type) && noArguments.isPresent())
                    || defaultStopSet.isPresent();
        }
    }

    /** Found when first needed, and again once extension jars have been loaded. */
    private static Scan scan;

    private static synchronized Map<String, Preset> presets() {
        ClassLoader loader = ExtensionJars.classLoader();
        if (scan == null || scan.loader() != loader) {
            scan = new Scan(loader, scan(loader));
        }
        return scan.byClassName();
    }

    /** The presets, sorted by simple name, ignoring case, then by class name. */
    static List<AvailableAnalyzer> list() {
        return presets().values().stream()
                .map(preset -> new AvailableAnalyzer(
                        preset.name(),
                        preset.type().getName(),
                        preset.stopwordSupport(),
                        preset.stemExclusionSupport()))
                .sorted(Comparator.comparing(AvailableAnalyzer::name, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(AvailableAnalyzer::className))
                .toList();
    }

    /**
     * A new analyzer for {@code spec}, of a listed class. With stem exclusions but no stop words, it
     * gets its default stop words.
     *
     * @throws InvalidAnalyzerException if the class is not listed, doesn't take a set it is given,
     *     needs stop words that are not given, a word list file can't be read, or the constructor
     *     fails
     */
    static Analyzer create(AnalyzerSpec.Preset spec) throws InvalidAnalyzerException {
        Preset preset = presets().get(spec.className());
        if (preset == null) {
            throw new InvalidAnalyzerException("no preset analyzer '" + spec.className() + "'", null);
        }
        if (spec.stopwords().isPresent() && preset.stopwordSupport() == WordSetSupport.NONE) {
            throw new InvalidAnalyzerException(preset.name() + " takes no stop words", null);
        }
        if (spec.stemExclusion().isPresent() && preset.stemExclusionSupport() == WordSetSupport.NONE) {
            throw new InvalidAnalyzerException(preset.name() + " takes no stem exclusions", null);
        }
        if (spec.stopwords().isEmpty() && preset.stopwordSupport() == WordSetSupport.REQUIRED) {
            throw new InvalidAnalyzerException(preset.name() + " needs stop words", null);
        }
        Optional<CharArraySet> stopwords = spec.stopwords().isPresent()
                ? Optional.of(read("stop words", spec.stopwords().orElseThrow()))
                : Optional.empty();
        if (spec.stemExclusion().isPresent()) {
            CharArraySet stemExclusion =
                    read("stem exclusions", spec.stemExclusion().orElseThrow());
            return construct(
                    preset,
                    preset.stopwordsAndStemExclusion().orElseThrow(),
                    stopwords.isPresent() ? stopwords.orElseThrow() : defaultStopwords(preset),
                    stemExclusion);
        }
        if (stopwords.isPresent()) {
            return construct(preset, preset.stopwords().orElseThrow(), stopwords.orElseThrow());
        }
        return construct(preset, preset.noArguments().orElseThrow());
    }

    /** What the preset uses without stop words of its own. */
    private static CharArraySet defaultStopwords(Preset preset) throws InvalidAnalyzerException {
        if (StopwordAnalyzerBase.class.isAssignableFrom(preset.type())
                && preset.noArguments().isPresent()) {
            try (Analyzer analyzer = construct(preset, preset.noArguments().orElseThrow())) {
                return ((StopwordAnalyzerBase) analyzer).getStopwordSet();
            }
        }
        try {
            return (CharArraySet) preset.defaultStopSet().orElseThrow().invoke(null);
        } catch (InvocationTargetException e) {
            throw failed(preset, e.getCause());
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new InvalidAnalyzerException(preset.name() + ": " + e, e);
        }
    }

    private static Analyzer construct(Preset preset, Constructor<? extends Analyzer> constructor, Object... arguments)
            throws InvalidAnalyzerException {
        try {
            return constructor.newInstance(arguments);
        } catch (InvocationTargetException e) {
            throw failed(preset, e.getCause());
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new InvalidAnalyzerException(preset.name() + ": " + e, e);
        }
    }

    private static InvalidAnalyzerException failed(Preset preset, Throwable cause) {
        String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        return new InvalidAnalyzerException(preset.name() + ": " + message, cause);
    }

    /** The words of {@code set}, case-sensitive as Lucene loads them; {@code what} names the set. */
    private static CharArraySet read(String what, WordSet set) throws InvalidAnalyzerException {
        return switch (set) {
            case WordSet.Words words -> new CharArraySet(words.words(), false);
            case WordSet.File file -> {
                Path path = file.path();
                try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    yield WordlistLoader.getWordSet(reader);
                } catch (NoSuchFileException e) {
                    throw new InvalidAnalyzerException(what + ": no file " + path, e);
                } catch (AccessDeniedException e) {
                    throw new InvalidAnalyzerException(what + ": cannot read " + path, e);
                } catch (CharacterCodingException e) {
                    throw new InvalidAnalyzerException(what + ": " + path + " is not UTF-8", e);
                } catch (IOException | UncheckedIOException e) {
                    throw new InvalidAnalyzerException(what + ": " + path + ": " + e.getMessage(), e);
                }
            }
        };
    }

    private static Map<String, Preset> scan(ClassLoader loader) {
        long start = System.nanoTime();
        Set<String> names = new TreeSet<>();
        for (Path location : locations()) {
            try {
                names.addAll(classNames(location));
            } catch (IOException | UncheckedIOException e) {
                LOG.log(Level.WARNING, "Cannot read " + location + " for analyzers", e);
            }
        }
        Map<String, Preset> presets = names.stream()
                .map(name -> preset(name, loader))
                .flatMap(Optional::stream)
                .collect(Collectors.toMap(preset -> preset.type().getName(), Function.identity(), (a, b) -> a));
        LOG.fine(() ->
                "Found " + presets.size() + " preset analyzers in " + (System.nanoTime() - start) / 1_000_000 + " ms");
        return Map.copyOf(presets);
    }

    /**
     * The jar or directory of Lucene's {@code Analyzer} and of every analysis factory, and the
     * extension jars.
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
        locations.addAll(ExtensionJars.jars());
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
    private static Optional<Preset> preset(String className, ClassLoader loader) {
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
        Class<? extends Analyzer> analyzer = type.asSubclass(Analyzer.class);
        Optional<Constructor<? extends Analyzer>> noArguments = constructor(analyzer);
        Optional<Constructor<? extends Analyzer>> stopwords = constructor(analyzer, CharArraySet.class);
        if (noArguments.isEmpty() && stopwords.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Preset(
                analyzer,
                noArguments,
                stopwords,
                constructor(analyzer, CharArraySet.class, CharArraySet.class),
                defaultStopSet(analyzer)));
    }

    /** The public constructor with these parameters, if there is one. */
    private static Optional<Constructor<? extends Analyzer>> constructor(
            Class<? extends Analyzer> type, Class<?>... parameters) {
        try {
            return Optional.of(type.getConstructor(parameters));
        } catch (NoSuchMethodException | LinkageError e) {
            return Optional.empty();
        }
    }

    /** The public, static {@code getDefaultStopSet()}, if there is one. */
    private static Optional<Method> defaultStopSet(Class<? extends Analyzer> type) {
        try {
            Method method = type.getMethod("getDefaultStopSet");
            return Modifier.isStatic(method.getModifiers())
                            && CharArraySet.class.isAssignableFrom(method.getReturnType())
                    ? Optional.of(method)
                    : Optional.empty();
        } catch (NoSuchMethodException | LinkageError e) {
            return Optional.empty();
        }
    }
}
