package io.github.mfuerstenau.leia.core;

import io.github.mfuerstenau.leia.core.AnalysisComponents.AvailableAnalyzer;
import io.github.mfuerstenau.leia.core.AnalyzerSpec.Collation;
import io.github.mfuerstenau.leia.core.AnalyzerSpec.Japanese;
import io.github.mfuerstenau.leia.core.AnalyzerSpec.StemDictionary;
import io.github.mfuerstenau.leia.core.AnalyzerSpec.WordSet;
import java.io.BufferedReader;
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
import java.util.LinkedHashMap;
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
import org.apache.lucene.analysis.CharArrayMap;
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
 * stem exclusions (ADR 0017); one that also takes a {@link CharArrayMap} takes a stem override
 * dictionary, and a public {@code setMaxTokenLength(int)} sets the longest token (ADR 0023). A
 * class with a public collator constructor is a preset too, which always gets a collator (ADR 0038).
 */
final class PresetAnalyzers {

    private static final Logger LOG = Logger.getLogger(PresetAnalyzers.class.getName());

    private static final String SUFFIX = ".class";

    /** The one analyzer whose {@code (int)} constructor takes the longest token (ADR 0023). */
    private static final String WHITESPACE = "org.apache.lucene.analysis.core.WhitespaceAnalyzer";

    /** The longest token that Lucene's tokenizers take: {@code StandardTokenizer.MAX_TOKEN_LENGTH_LIMIT}. */
    static final int MAX_TOKEN_LENGTH = 1024 * 1024;

    private PresetAnalyzers() {}

    /** The presets that {@code loader} sees. */
    private record Scan(ClassLoader loader, Map<String, Preset> byClassName) {}

    /**
     * A preset's class and its constructors: without arguments, with the stop words, and with the
     * stop words and the stem exclusions; at least one of the first two or {@code collation} is
     * present. Then what sets its options (ADR 0023, ADR 0038).
     *
     * @param stemOverride with the stop words, the stem exclusions and a stem override dictionary
     * @param japanese Kuromoji's {@code JapaneseAnalyzer} with its options and the stop words
     * @param korean Nori's {@code KoreanAnalyzer} with its options
     * @param collation with a JDK or ICU collator
     * @param japaneseCompletion Kuromoji's {@code JapaneseCompletionAnalyzer} with its options
     * @param maxTokenLength {@code WhitespaceAnalyzer(int)}
     * @param maxTokenLengthSetter the public {@code setMaxTokenLength(int)}
     * @param defaultStopSet the static {@code getDefaultStopSet()}, for an analyzer that is not a
     *     {@link StopwordAnalyzerBase}
     */
    private record Preset(
            Class<? extends Analyzer> type,
            Optional<Constructor<? extends Analyzer>> noArguments,
            Optional<Constructor<? extends Analyzer>> stopwords,
            Optional<Constructor<? extends Analyzer>> stopwordsAndStemExclusion,
            Optional<Constructor<? extends Analyzer>> stemOverride,
            Optional<Constructor<? extends Analyzer>> japanese,
            Optional<Constructor<? extends Analyzer>> korean,
            Optional<Constructor<? extends Analyzer>> collation,
            Optional<Constructor<? extends Analyzer>> japaneseCompletion,
            Optional<Constructor<? extends Analyzer>> maxTokenLength,
            Optional<Method> maxTokenLengthSetter,
            Optional<Method> defaultStopSet) {

        String name() {
            return type.getSimpleName();
        }

        WordSetSupport stopwordSupport() {
            if (stopwords.isEmpty()) {
                // JapaneseAnalyzer takes them only with its options.
                return japanese.isPresent() && hasDefaultStopwords() ? WordSetSupport.OPTIONAL : WordSetSupport.NONE;
            }
            return noArguments.isPresent() ? WordSetSupport.OPTIONAL : WordSetSupport.REQUIRED;
        }

        List<PresetOption> options() {
            List<PresetOption> options = new ArrayList<>();
            if (maxTokenLength.isPresent() || maxTokenLengthSetter.isPresent()) {
                options.add(PresetOption.MAX_TOKEN_LENGTH);
            }
            if (stemOverride.isPresent() && hasDefaultStopwords()) {
                options.add(PresetOption.STEM_OVERRIDE);
            }
            if (japanese.isPresent() && hasDefaultStopwords()) {
                options.add(PresetOption.JAPANESE);
            }
            if (korean.isPresent()) {
                options.add(PresetOption.KOREAN);
            }
            if (collation.isPresent()) {
                options.add(PresetOption.COLLATION);
            }
            if (japaneseCompletion.isPresent()) {
                options.add(PresetOption.JAPANESE_COMPLETION);
            }
            return options;
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
                        preset.stemExclusionSupport(),
                        preset.options()))
                .sorted(Comparator.comparing(AvailableAnalyzer::name, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(AvailableAnalyzer::className))
                .toList();
    }

    /**
     * A new analyzer for {@code spec}, of a listed class. With stem exclusions or a stem override
     * dictionary but no stop words, it gets its default stop words.
     *
     * @throws InvalidAnalyzerException if the class is not listed, doesn't take a set or an option
     *     it is given, needs stop words that are not given, an option is out of range, a file can't
     *     be read, or the constructor fails
     */
    static Analyzer create(AnalyzerSpec.Preset spec) throws InvalidAnalyzerException {
        Preset preset = presets().get(spec.className());
        if (preset == null) {
            throw new InvalidAnalyzerException("no preset analyzer '" + spec.className() + "'", null);
        }
        check(preset, spec);
        Optional<CharArraySet> stopwords = given("stop words", spec.stopwords());
        Optional<CharArraySet> stemExclusion = given("stem exclusions", spec.stemExclusion());
        Analyzer analyzer;
        if (preset.collation().isPresent()) {
            Constructor<? extends Analyzer> constructor = preset.collation().orElseThrow();
            Collation collation = spec.collation().orElse(new Collation("", Optional.empty(), Optional.empty()));
            return construct(preset, constructor, Collations.collator(constructor, collation));
        } else if (spec.japaneseCompletion().isPresent()) {
            Constructor<? extends Analyzer> constructor =
                    preset.japaneseCompletion().orElseThrow();
            analyzer = construct(
                    preset,
                    constructor,
                    KuromojiNori.japaneseCompletionArguments(
                            constructor, spec.japaneseCompletion().orElseThrow()));
        } else if (preset.japanese().isPresent()
                && (stopwords.isPresent() || spec.japanese().isPresent())) {
            Constructor<? extends Analyzer> constructor = preset.japanese().orElseThrow();
            Japanese options =
                    spec.japanese().orElse(new Japanese(Optional.empty(), Optional.empty(), Optional.empty()));
            CharArraySet set = stopwordsOrDefault(stopwords, preset);
            analyzer = construct(preset, constructor, KuromojiNori.japaneseArguments(constructor, set, options));
        } else if (spec.korean().isPresent()) {
            Constructor<? extends Analyzer> constructor = preset.korean().orElseThrow();
            analyzer = construct(
                    preset,
                    constructor,
                    KuromojiNori.koreanArguments(constructor, spec.korean().orElseThrow()));
        } else if (spec.stemOverride().isPresent()) {
            CharArrayMap<String> dictionary = read(spec.stemOverride().orElseThrow());
            analyzer = construct(
                    preset,
                    preset.stemOverride().orElseThrow(),
                    stopwordsOrDefault(stopwords, preset),
                    stemExclusion.orElse(CharArraySet.EMPTY_SET),
                    dictionary);
        } else if (stemExclusion.isPresent()) {
            analyzer = construct(
                    preset,
                    preset.stopwordsAndStemExclusion().orElseThrow(),
                    stopwordsOrDefault(stopwords, preset),
                    stemExclusion.orElseThrow());
        } else if (stopwords.isPresent()) {
            analyzer = construct(preset, preset.stopwords().orElseThrow(), stopwords.orElseThrow());
        } else if (spec.maxTokenLength().isPresent() && preset.maxTokenLength().isPresent()) {
            return construct(
                    preset,
                    preset.maxTokenLength().orElseThrow(),
                    spec.maxTokenLength().getAsInt());
        } else {
            analyzer = construct(preset, preset.noArguments().orElseThrow());
        }
        if (spec.maxTokenLength().isPresent()) {
            setMaxTokenLength(preset, analyzer, spec.maxTokenLength().getAsInt());
        }
        return analyzer;
    }

    /** Whether the preset takes what {@code spec} gives it, and the values are in range. */
    private static void check(Preset preset, AnalyzerSpec.Preset spec) throws InvalidAnalyzerException {
        if (spec.stopwords().isPresent() && preset.stopwordSupport() == WordSetSupport.NONE) {
            throw new InvalidAnalyzerException(preset.name() + " takes no stop words", null);
        }
        if (spec.stemExclusion().isPresent() && preset.stemExclusionSupport() == WordSetSupport.NONE) {
            throw new InvalidAnalyzerException(preset.name() + " takes no stem exclusions", null);
        }
        if (spec.stopwords().isEmpty() && preset.stopwordSupport() == WordSetSupport.REQUIRED) {
            throw new InvalidAnalyzerException(preset.name() + " needs stop words", null);
        }
        checkOption(preset, spec.maxTokenLength().isPresent(), PresetOption.MAX_TOKEN_LENGTH, "max token length");
        checkOption(preset, spec.stemOverride().isPresent(), PresetOption.STEM_OVERRIDE, "stem overrides");
        checkOption(preset, spec.japanese().isPresent(), PresetOption.JAPANESE, "Japanese options");
        checkOption(preset, spec.korean().isPresent(), PresetOption.KOREAN, "Korean options");
        checkOption(preset, spec.collation().isPresent(), PresetOption.COLLATION, "collation");
        checkOption(
                preset,
                spec.japaneseCompletion().isPresent(),
                PresetOption.JAPANESE_COMPLETION,
                "Japanese completion options");
        if (spec.maxTokenLength().isPresent()) {
            int length = spec.maxTokenLength().getAsInt();
            if (length < 1 || length > MAX_TOKEN_LENGTH) {
                throw new InvalidAnalyzerException(
                        "max token length: must be from 1 to " + MAX_TOKEN_LENGTH + ", not " + length, null);
            }
        }
    }

    /** Fails if {@code given} is an option that {@code preset} doesn't take. */
    private static void checkOption(Preset preset, boolean given, PresetOption option, String what)
            throws InvalidAnalyzerException {
        if (given && !preset.options().contains(option)) {
            throw new InvalidAnalyzerException(preset.name() + " takes no " + what, null);
        }
    }

    private static void setMaxTokenLength(Preset preset, Analyzer analyzer, int length)
            throws InvalidAnalyzerException {
        try {
            preset.maxTokenLengthSetter().orElseThrow().invoke(analyzer, length);
        } catch (InvocationTargetException e) {
            analyzer.close();
            throw failed(preset, e.getCause());
        } catch (ReflectiveOperationException | LinkageError e) {
            analyzer.close();
            throw new InvalidAnalyzerException(preset.name() + ": " + e, e);
        }
    }

    /** The set of {@code words}, if given. */
    private static Optional<CharArraySet> given(String what, Optional<WordSet> words) throws InvalidAnalyzerException {
        return words.isPresent() ? Optional.of(read(what, words.orElseThrow())) : Optional.empty();
    }

    private static CharArraySet stopwordsOrDefault(Optional<CharArraySet> stopwords, Preset preset)
            throws InvalidAnalyzerException {
        return stopwords.isPresent() ? stopwords.orElseThrow() : defaultStopwords(preset);
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
    static CharArraySet read(String what, WordSet set) throws InvalidAnalyzerException {
        return switch (set) {
            case WordSet.Words words -> new CharArraySet(words.words(), false);
            case WordSet.File file -> readFile(what, file.path(), WordlistLoader::getWordSet);
        };
    }

    /**
     * The stem override dictionary, case-sensitive as Lucene loads it: the entries, or a file of
     * {@code word<TAB>stem} lines read as {@code StemmerOverrideFilterFactory} reads it.
     */
    private static CharArrayMap<String> read(StemDictionary dictionary) throws InvalidAnalyzerException {
        String what = "stem overrides";
        Map<String, String> entries = switch (dictionary) {
            case StemDictionary.Entries given -> given.entries();
            case StemDictionary.File file ->
                readFile(what, file.path(), reader -> {
                    Map<String, String> lines = new LinkedHashMap<>();
                    for (String line : new BufferedReader(reader).lines().toList()) {
                        String trimmed = line.strip();
                        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                            continue;
                        }
                        String[] entry = trimmed.split("\t", 2);
                        if (entry.length != 2) {
                            throw new InvalidAnalyzerException(
                                    what + ": " + file.path() + ": '" + trimmed + "' is not word<TAB>stem", null);
                        }
                        lines.put(entry[0], entry[1]);
                    }
                    return lines;
                });
        };
        CharArrayMap<String> map = new CharArrayMap<>(entries.size(), false);
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            if (entry.getKey().isEmpty() || entry.getValue().isEmpty()) {
                throw new InvalidAnalyzerException(
                        what + ": an empty word or stem in '" + entry.getKey() + "' → '" + entry.getValue() + "'",
                        null);
            }
            map.put(entry.getKey(), entry.getValue());
        }
        return map;
    }

    /** Reads a file. */
    interface FileReader<T> {
        T read(Reader reader) throws IOException, ReflectiveOperationException, InvalidAnalyzerException;
    }

    /**
     * What {@code read} makes of the UTF-8 file at {@code path}; {@code what} names it in the
     * message of a file that can't be read or decoded, or that {@code read} fails on.
     */
    static <T> T readFile(String what, Path path, FileReader<T> read) throws InvalidAnalyzerException {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return read.read(reader);
        } catch (InvalidAnalyzerException e) {
            throw e;
        } catch (IOException | ReflectiveOperationException | RuntimeException e) {
            throw unreadable(what, path, e);
        }
    }

    private static InvalidAnalyzerException unreadable(String what, Path path, Throwable e) {
        return switch (e) {
            case InvocationTargetException invocation
            when invocation.getCause() != null -> unreadable(what, path, invocation.getCause());
            case UncheckedIOException unchecked -> unreadable(what, path, unchecked.getCause());
            case NoSuchFileException _ -> new InvalidAnalyzerException(what + ": no file " + path, e);
            case AccessDeniedException _ -> new InvalidAnalyzerException(what + ": cannot read " + path, e);
            case CharacterCodingException _ -> new InvalidAnalyzerException(what + ": " + path + " is not UTF-8", e);
            default -> {
                String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                yield new InvalidAnalyzerException(what + ": " + path + ": " + message, e);
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
        Optional<Constructor<? extends Analyzer>> collation = Collations.constructor(analyzer);
        if (noArguments.isEmpty() && stopwords.isEmpty() && collation.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Preset(
                analyzer,
                noArguments,
                stopwords,
                constructor(analyzer, CharArraySet.class, CharArraySet.class),
                constructor(analyzer, CharArraySet.class, CharArraySet.class, CharArrayMap.class),
                KuromojiNori.japaneseConstructor(analyzer),
                KuromojiNori.koreanConstructor(analyzer),
                collation,
                KuromojiNori.japaneseCompletionConstructor(analyzer),
                className.equals(WHITESPACE) ? constructor(analyzer, int.class) : Optional.empty(),
                maxTokenLengthSetter(analyzer),
                defaultStopSet(analyzer)));
    }

    /** The public {@code setMaxTokenLength(int)}, if there is one. */
    private static Optional<Method> maxTokenLengthSetter(Class<? extends Analyzer> type) {
        try {
            Method method = type.getMethod("setMaxTokenLength", int.class);
            return Modifier.isStatic(method.getModifiers()) ? Optional.empty() : Optional.of(method);
        } catch (NoSuchMethodException | LinkageError _) {
            return Optional.empty();
        }
    }

    /** The public constructor with these parameters, if there is one. */
    private static Optional<Constructor<? extends Analyzer>> constructor(
            Class<? extends Analyzer> type, Class<?>... parameters) {
        try {
            return Optional.of(type.getConstructor(parameters));
        } catch (NoSuchMethodException | LinkageError _) {
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
        } catch (NoSuchMethodException | LinkageError _) {
            return Optional.empty();
        }
    }
}
