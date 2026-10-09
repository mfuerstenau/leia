package io.github.mfuerstenau.leia.core;

import io.github.mfuerstenau.leia.core.AnalyzerSpec.Japanese;
import io.github.mfuerstenau.leia.core.AnalyzerSpec.Korean;
import java.io.Reader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.CharArraySet;

/**
 * Kuromoji's {@code JapaneseAnalyzer} and Nori's {@code KoreanAnalyzer} with options of their own
 * (ADR 0023). Their modules are extension jars (ADR 0016), so their types are found by name,
 * through the analyzer class's own loader.
 */
final class KuromojiNori {

    static final String JAPANESE = "org.apache.lucene.analysis.ja.JapaneseAnalyzer";
    static final String KOREAN = "org.apache.lucene.analysis.ko.KoreanAnalyzer";

    private static final String JAPANESE_DICTIONARY = "org.apache.lucene.analysis.ja.dict.UserDictionary";
    private static final String JAPANESE_TOKENIZER = "org.apache.lucene.analysis.ja.JapaneseTokenizer";
    private static final String JAPANESE_MODE = JAPANESE_TOKENIZER + "$Mode";
    private static final String KOREAN_DICTIONARY = "org.apache.lucene.analysis.ko.dict.UserDictionary";
    private static final String KOREAN_TOKENIZER = "org.apache.lucene.analysis.ko.KoreanTokenizer";
    private static final String KOREAN_MODE = KOREAN_TOKENIZER + "$DecompoundMode";
    private static final String KOREAN_STOP_FILTER = "org.apache.lucene.analysis.ko.KoreanPartOfSpeechStopFilter";
    private static final String KOREAN_TAG = "org.apache.lucene.analysis.ko.POS$Tag";

    private KuromojiNori() {}

    /**
     * {@code JapaneseAnalyzer(UserDictionary, Mode, CharArraySet, Set)}, if {@code type} is that
     * class.
     */
    static Optional<Constructor<? extends Analyzer>> japaneseConstructor(Class<? extends Analyzer> type) {
        if (!type.getName().equals(JAPANESE)) {
            return Optional.empty();
        }
        try {
            ClassLoader loader = type.getClassLoader();
            return Optional.of(type.getConstructor(
                    Class.forName(JAPANESE_DICTIONARY, false, loader),
                    Class.forName(JAPANESE_MODE, false, loader),
                    CharArraySet.class,
                    Set.class));
        } catch (ReflectiveOperationException | LinkageError e) {
            return Optional.empty();
        }
    }

    /**
     * {@code KoreanAnalyzer(UserDictionary, DecompoundMode, Set, boolean)}, if {@code type} is that
     * class.
     */
    static Optional<Constructor<? extends Analyzer>> koreanConstructor(Class<? extends Analyzer> type) {
        if (!type.getName().equals(KOREAN)) {
            return Optional.empty();
        }
        try {
            ClassLoader loader = type.getClassLoader();
            return Optional.of(type.getConstructor(
                    Class.forName(KOREAN_DICTIONARY, false, loader),
                    Class.forName(KOREAN_MODE, false, loader),
                    Set.class,
                    boolean.class));
        } catch (ReflectiveOperationException | LinkageError e) {
            return Optional.empty();
        }
    }

    /**
     * The arguments of {@link #japaneseConstructor}: the options' values, or the analyzer's
     * defaults.
     */
    static Object[] japaneseArguments(
            Constructor<? extends Analyzer> constructor, CharArraySet stopwords, Japanese options)
            throws InvalidAnalyzerException {
        ClassLoader loader = constructor.getDeclaringClass().getClassLoader();
        try {
            Object dictionary =
                    userDictionary(Class.forName(JAPANESE_DICTIONARY, true, loader), options.userDictionary());
            Class<?> modeType = Class.forName(JAPANESE_MODE, true, loader);
            Object mode = options.mode().isPresent()
                    ? constant(modeType, options.mode().orElseThrow().name())
                    : Class.forName(JAPANESE_TOKENIZER, true, loader)
                            .getField("DEFAULT_MODE")
                            .get(null);
            Set<?> stopTags;
            if (options.stopTags().isPresent()) {
                Set<String> tags = new HashSet<>();
                for (Object tag :
                        PresetAnalyzers.read("stop tags", options.stopTags().orElseThrow())) {
                    tags.add(new String((char[]) tag));
                }
                stopTags = tags;
            } else {
                stopTags = (Set<?>) constructor
                        .getDeclaringClass()
                        .getMethod("getDefaultStopTags")
                        .invoke(null);
            }
            return new Object[] {dictionary, mode, stopwords, stopTags};
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new InvalidAnalyzerException("JapaneseAnalyzer: " + e, e);
        }
    }

    /**
     * The arguments of {@link #koreanConstructor}: the options' values, or the analyzer's defaults.
     */
    static Object[] koreanArguments(Constructor<? extends Analyzer> constructor, Korean options)
            throws InvalidAnalyzerException {
        ClassLoader loader = constructor.getDeclaringClass().getClassLoader();
        try {
            Object dictionary =
                    userDictionary(Class.forName(KOREAN_DICTIONARY, true, loader), options.userDictionary());
            Object mode = options.decompoundMode().isPresent()
                    ? constant(
                            Class.forName(KOREAN_MODE, true, loader),
                            options.decompoundMode().orElseThrow().name())
                    : Class.forName(KOREAN_TOKENIZER, true, loader)
                            .getField("DEFAULT_DECOMPOUND")
                            .get(null);
            Set<?> stopTags;
            if (options.stopTags().isPresent()) {
                Class<?> tagType = Class.forName(KOREAN_TAG, true, loader);
                Set<Object> tags = new HashSet<>();
                for (Object tag :
                        PresetAnalyzers.read("stop tags", options.stopTags().orElseThrow())) {
                    String name = new String((char[]) tag);
                    Object constant = constant(tagType, name);
                    if (constant == null) {
                        throw new InvalidAnalyzerException("stop tags: no part of speech '" + name + "'", null);
                    }
                    tags.add(constant);
                }
                stopTags = tags;
            } else {
                stopTags = (Set<?>) Class.forName(KOREAN_STOP_FILTER, true, loader)
                        .getField("DEFAULT_STOP_TAGS")
                        .get(null);
            }
            return new Object[] {
                dictionary, mode, stopTags, options.outputUnknownUnigrams().orElse(false)
            };
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new InvalidAnalyzerException("KoreanAnalyzer: " + e, e);
        }
    }

    /** The user dictionary in the file, read with the module's {@code UserDictionary.open}; null for none. */
    private static Object userDictionary(Class<?> type, Optional<Path> file)
            throws InvalidAnalyzerException, ReflectiveOperationException {
        if (file.isEmpty()) {
            return null;
        }
        Method open = type.getMethod("open", Reader.class);
        if (!Modifier.isStatic(open.getModifiers())) {
            throw new NoSuchMethodException(type.getName() + ".open(Reader) is not static");
        }
        return PresetAnalyzers.readFile("user dictionary", file.orElseThrow(), reader -> open.invoke(null, reader));
    }

    /** The enum constant of {@code type} named {@code name}; null if there is none. */
    private static Object constant(Class<?> type, String name) {
        return Arrays.stream(type.getEnumConstants())
                .filter(constant -> ((Enum<?>) constant).name().equals(name))
                .findFirst()
                .orElse(null);
    }
}
