package io.github.mfuerstenau.leia.lucene9;

import io.github.mfuerstenau.leia.runtime.Analysis.PresetOption;
import io.github.mfuerstenau.leia.runtime.Analysis.WordSetSupport;
import io.github.mfuerstenau.leia.runtime.Search.AnalyzerSpec;
import io.github.mfuerstenau.leia.runtime.Search.AnalyzerSpec.Collation;
import io.github.mfuerstenau.leia.runtime.Search.InvalidAnalyzerException;
import io.github.mfuerstenau.leia.runtime.Search.UnsupportedAnalyzerException;
import io.github.mfuerstenau.leia.runtime.support.Failures;
import io.github.mfuerstenau.leia.runtime.support.WordFiles;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.text.Collator;
import java.util.ArrayList;
import java.util.IllformedLocaleException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.CharArrayMap;
import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.StopwordAnalyzerBase;
import org.apache.lucene.analysis.ar.ArabicAnalyzer;
import org.apache.lucene.analysis.bg.BulgarianAnalyzer;
import org.apache.lucene.analysis.bn.BengaliAnalyzer;
import org.apache.lucene.analysis.br.BrazilianAnalyzer;
import org.apache.lucene.analysis.ca.CatalanAnalyzer;
import org.apache.lucene.analysis.cjk.CJKAnalyzer;
import org.apache.lucene.analysis.ckb.SoraniAnalyzer;
import org.apache.lucene.analysis.classic.ClassicAnalyzer;
import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.apache.lucene.analysis.core.SimpleAnalyzer;
import org.apache.lucene.analysis.core.StopAnalyzer;
import org.apache.lucene.analysis.core.UnicodeWhitespaceAnalyzer;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.analysis.cz.CzechAnalyzer;
import org.apache.lucene.analysis.da.DanishAnalyzer;
import org.apache.lucene.analysis.de.GermanAnalyzer;
import org.apache.lucene.analysis.el.GreekAnalyzer;
import org.apache.lucene.analysis.email.UAX29URLEmailAnalyzer;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.es.SpanishAnalyzer;
import org.apache.lucene.analysis.et.EstonianAnalyzer;
import org.apache.lucene.analysis.eu.BasqueAnalyzer;
import org.apache.lucene.analysis.fa.PersianAnalyzer;
import org.apache.lucene.analysis.fi.FinnishAnalyzer;
import org.apache.lucene.analysis.fr.FrenchAnalyzer;
import org.apache.lucene.analysis.ga.IrishAnalyzer;
import org.apache.lucene.analysis.gl.GalicianAnalyzer;
import org.apache.lucene.analysis.hi.HindiAnalyzer;
import org.apache.lucene.analysis.hu.HungarianAnalyzer;
import org.apache.lucene.analysis.hy.ArmenianAnalyzer;
import org.apache.lucene.analysis.id.IndonesianAnalyzer;
import org.apache.lucene.analysis.it.ItalianAnalyzer;
import org.apache.lucene.analysis.lt.LithuanianAnalyzer;
import org.apache.lucene.analysis.lv.LatvianAnalyzer;
import org.apache.lucene.analysis.ne.NepaliAnalyzer;
import org.apache.lucene.analysis.nl.DutchAnalyzer;
import org.apache.lucene.analysis.no.NorwegianAnalyzer;
import org.apache.lucene.analysis.pt.PortugueseAnalyzer;
import org.apache.lucene.analysis.ro.RomanianAnalyzer;
import org.apache.lucene.analysis.ru.RussianAnalyzer;
import org.apache.lucene.analysis.sr.SerbianAnalyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.sv.SwedishAnalyzer;
import org.apache.lucene.analysis.ta.TamilAnalyzer;
import org.apache.lucene.analysis.te.TeluguAnalyzer;
import org.apache.lucene.analysis.th.ThaiAnalyzer;
import org.apache.lucene.analysis.tr.TurkishAnalyzer;
import org.apache.lucene.collation.CollationKeyAnalyzer;
import org.apache.lucene.util.Version;
import org.jspecify.annotations.Nullable;

/**
 * The preset analyzers of Lucene 9.12 (ADR 0049, ADR 0067): the analyzers of lucene-core and
 * analysis-common, matched by the simple name of a preset's class name. What each takes is found by
 * reflection, as leia-core's {@code PresetAnalyzers} finds it on Lucene 10, and it is checked and
 * created in leia-core's order, with leia-core's messages: a constructor that takes one {@link
 * CharArraySet} takes the stop words, one that takes two the stop words and the stem exclusions, one
 * that also takes a {@link CharArrayMap} a stem override dictionary; a public {@code
 * setMaxTokenLength(int)} sets the longest token, and {@link CollationKeyAnalyzer} takes the JDK's
 * collator. 9.12 has no Kuromoji, Nori or ICU.
 *
 * <p>The analyzers are a table of class literals of this runtime's Lucene.
 */
final class Lucene9Presets {

    private static final List<Class<? extends Analyzer>> TYPES = List.of(
            StandardAnalyzer.class,
            ClassicAnalyzer.class,
            WhitespaceAnalyzer.class,
            UnicodeWhitespaceAnalyzer.class,
            SimpleAnalyzer.class,
            StopAnalyzer.class,
            KeywordAnalyzer.class,
            UAX29URLEmailAnalyzer.class,
            CollationKeyAnalyzer.class,
            ArabicAnalyzer.class,
            ArmenianAnalyzer.class,
            BasqueAnalyzer.class,
            BengaliAnalyzer.class,
            BrazilianAnalyzer.class,
            BulgarianAnalyzer.class,
            CatalanAnalyzer.class,
            CJKAnalyzer.class,
            CzechAnalyzer.class,
            DanishAnalyzer.class,
            DutchAnalyzer.class,
            EnglishAnalyzer.class,
            EstonianAnalyzer.class,
            FinnishAnalyzer.class,
            FrenchAnalyzer.class,
            GalicianAnalyzer.class,
            GermanAnalyzer.class,
            GreekAnalyzer.class,
            HindiAnalyzer.class,
            HungarianAnalyzer.class,
            IndonesianAnalyzer.class,
            IrishAnalyzer.class,
            ItalianAnalyzer.class,
            LatvianAnalyzer.class,
            LithuanianAnalyzer.class,
            NepaliAnalyzer.class,
            NorwegianAnalyzer.class,
            PersianAnalyzer.class,
            PortugueseAnalyzer.class,
            RomanianAnalyzer.class,
            RussianAnalyzer.class,
            SerbianAnalyzer.class,
            SoraniAnalyzer.class,
            SpanishAnalyzer.class,
            SwedishAnalyzer.class,
            TamilAnalyzer.class,
            TeluguAnalyzer.class,
            ThaiAnalyzer.class,
            TurkishAnalyzer.class);

    /** The longest token that Lucene's tokenizers take: {@code StandardTokenizer.MAX_TOKEN_LENGTH_LIMIT}. */
    private static final int MAX_TOKEN_LENGTH = 1024 * 1024;

    /**
     * A preset's class and its constructors: without arguments, with the stop words, and with the
     * stop words and the stem exclusions. Then what sets its options.
     *
     * @param stemOverride with the stop words, the stem exclusions and a stem override dictionary
     * @param collation with a JDK collator
     * @param maxTokenLength {@code WhitespaceAnalyzer(int)}
     * @param maxTokenLengthSetter the public {@code setMaxTokenLength(int)}
     * @param defaultStopSet the static {@code getDefaultStopSet()}, for an analyzer that is not a
     *     {@link StopwordAnalyzerBase}
     */
    private record Preset(
            Class<? extends Analyzer> type,
            @Nullable Constructor<? extends Analyzer> noArguments,
            @Nullable Constructor<? extends Analyzer> stopwords,
            @Nullable Constructor<? extends Analyzer> stopwordsAndStemExclusion,
            @Nullable Constructor<? extends Analyzer> stemOverride,
            @Nullable Constructor<? extends Analyzer> collation,
            @Nullable Constructor<? extends Analyzer> maxTokenLength,
            @Nullable Method maxTokenLengthSetter,
            @Nullable Method defaultStopSet) {

        String name() {
            return type.getSimpleName();
        }

        WordSetSupport stopwordSupport() {
            if (stopwords == null) {
                return WordSetSupport.NONE;
            }
            return noArguments != null ? WordSetSupport.OPTIONAL : WordSetSupport.REQUIRED;
        }

        WordSetSupport stemExclusionSupport() {
            return stopwordsAndStemExclusion != null && hasDefaultStopwords()
                    ? WordSetSupport.OPTIONAL
                    : WordSetSupport.NONE;
        }

        List<PresetOption> options() {
            List<PresetOption> options = new ArrayList<>();
            if (maxTokenLength != null || maxTokenLengthSetter != null) {
                options.add(PresetOption.MAX_TOKEN_LENGTH);
            }
            if (stemOverride != null && hasDefaultStopwords()) {
                options.add(PresetOption.STEM_OVERRIDE);
            }
            if (collation != null) {
                options.add(PresetOption.COLLATION);
            }
            return options;
        }

        /** Whether the default stop words can be found, for stem exclusions without stop words. */
        boolean hasDefaultStopwords() {
            return (StopwordAnalyzerBase.class.isAssignableFrom(type) && noArguments != null) || defaultStopSet != null;
        }
    }

    private static final Map<String, Preset> BY_NAME = TYPES.stream()
            .map(Lucene9Presets::preset)
            .collect(Collectors.toUnmodifiableMap(Preset::name, Function.identity()));

    private Lucene9Presets() {}

    /**
     * A new analyzer for {@code spec}. With stem exclusions or a stem override dictionary but no stop
     * words, it gets its default stop words.
     *
     * @throws UnsupportedAnalyzerException if 9.12 has no analyzer of that simple name, or for the
     *     options of Kuromoji and Nori
     * @throws InvalidAnalyzerException if the analyzer doesn't take a set or an option it is given,
     *     needs stop words that are not given, an option is out of range, a file can't be read, or the
     *     constructor fails
     */
    static Analyzer create(AnalyzerSpec.Preset spec) throws UnsupportedAnalyzerException, InvalidAnalyzerException {
        Preset preset = BY_NAME.get(spec.simpleName());
        if (preset == null) {
            throw new UnsupportedAnalyzerException("Lucene " + Version.LATEST + " has no analyzer " + spec.simpleName()
                    + "; it has " + String.join(", ", new TreeSet<>(BY_NAME.keySet())));
        }
        checkSupported(spec);
        check(preset, spec);
        @Nullable CharArraySet stopwords = given("stop words", spec.stopwords());
        @Nullable CharArraySet stemExclusion = given("stem exclusions", spec.stemExclusion());
        Analyzer analyzer;
        if (preset.collation() != null) {
            Collation collation = spec.collation() != null ? spec.collation() : new Collation("", null, null);
            return construct(preset, preset.collation(), collator(collation));
        } else if (spec.stemOverride() != null) {
            CharArrayMap<String> dictionary = dictionary(spec.stemOverride());
            analyzer = construct(
                    preset,
                    Objects.requireNonNull(preset.stemOverride()),
                    stopwordsOrDefault(stopwords, preset),
                    stemExclusion != null ? stemExclusion : CharArraySet.EMPTY_SET,
                    dictionary);
        } else if (stemExclusion != null) {
            analyzer = construct(
                    preset,
                    Objects.requireNonNull(preset.stopwordsAndStemExclusion()),
                    stopwordsOrDefault(stopwords, preset),
                    stemExclusion);
        } else if (stopwords != null) {
            analyzer = construct(preset, Objects.requireNonNull(preset.stopwords()), stopwords);
        } else if (spec.maxTokenLength() != null && preset.maxTokenLength() != null) {
            return construct(preset, preset.maxTokenLength(), spec.maxTokenLength());
        } else {
            analyzer = construct(preset, Objects.requireNonNull(preset.noArguments()));
        }
        if (spec.maxTokenLength() != null) {
            setMaxTokenLength(preset, analyzer, spec.maxTokenLength());
        }
        return analyzer;
    }

    /** Whether the preset takes what {@code spec} gives it, and the values are in range. */
    private static void check(Preset preset, AnalyzerSpec.Preset spec) throws InvalidAnalyzerException {
        List<PresetOption> options = preset.options();
        if (spec.stopwords() != null && preset.stopwordSupport() == WordSetSupport.NONE) {
            throw new InvalidAnalyzerException(preset.name() + " takes no stop words", null);
        }
        if (spec.stemExclusion() != null && preset.stemExclusionSupport() == WordSetSupport.NONE) {
            throw new InvalidAnalyzerException(preset.name() + " takes no stem exclusions", null);
        }
        if (spec.stopwords() == null && preset.stopwordSupport() == WordSetSupport.REQUIRED) {
            throw new InvalidAnalyzerException(preset.name() + " needs stop words", null);
        }
        if (spec.maxTokenLength() != null && !options.contains(PresetOption.MAX_TOKEN_LENGTH)) {
            throw new InvalidAnalyzerException(preset.name() + " takes no max token length", null);
        }
        if (spec.stemOverride() != null && !options.contains(PresetOption.STEM_OVERRIDE)) {
            throw new InvalidAnalyzerException(preset.name() + " takes no stem overrides", null);
        }
        if (spec.collation() != null && !options.contains(PresetOption.COLLATION)) {
            throw new InvalidAnalyzerException(preset.name() + " takes no collation", null);
        }
        checkMaxTokenLength(spec);
    }

    private static void checkMaxTokenLength(AnalyzerSpec.Preset spec) throws InvalidAnalyzerException {
        if (spec.maxTokenLength() != null) {
            int length = spec.maxTokenLength();
            if (length < 1 || length > MAX_TOKEN_LENGTH) {
                throw new InvalidAnalyzerException(
                        "max token length: must be from 1 to " + MAX_TOKEN_LENGTH + ", not " + length, null);
            }
        }
    }

    /** Fails for the Japanese and Korean options, which only Lucene 10's runtime has. */
    private static void checkSupported(AnalyzerSpec.Preset spec) throws UnsupportedAnalyzerException {
        if (spec.japanese() != null) {
            throw new UnsupportedAnalyzerException("Japanese options");
        }
        if (spec.korean() != null) {
            throw new UnsupportedAnalyzerException("Korean options");
        }
        if (spec.japaneseCompletion() != null) {
            throw new UnsupportedAnalyzerException("Japanese completion options");
        }
    }

    /** The words of {@code set}, case-sensitive as Lucene loads them; {@code what} names the set. */
    private static CharArraySet set(String what, AnalyzerSpec.WordSet set) throws InvalidAnalyzerException {
        return new CharArraySet(WordFiles.words(what, set), false);
    }

    private static CharArrayMap<String> dictionary(AnalyzerSpec.StemDictionary dictionary)
            throws InvalidAnalyzerException {
        Map<String, String> entries = WordFiles.stemOverrides(dictionary);
        CharArrayMap<String> map = new CharArrayMap<>(entries.size(), false);
        entries.forEach(map::put);
        return map;
    }

    /** The JDK's collator for {@code collation}, as leia-core makes it. */
    private static Collator collator(Collation collation) throws InvalidAnalyzerException {
        Locale locale = Locale.ROOT;
        if (!collation.locale().isEmpty()) {
            try {
                locale = new Locale.Builder().setLanguageTag(collation.locale()).build();
            } catch (IllformedLocaleException e) {
                throw new InvalidAnalyzerException(
                        "collation: '" + collation.locale() + "' is not a language tag: " + e.getMessage(), e);
            }
        }
        Collator collator = Collator.getInstance(locale);
        if (collation.strength() != null) {
            collator.setStrength(
                    switch (collation.strength()) {
                        case PRIMARY -> Collator.PRIMARY;
                        case SECONDARY -> Collator.SECONDARY;
                        case TERTIARY -> Collator.TERTIARY;
                        case IDENTICAL -> Collator.IDENTICAL;
                        case QUATERNARY ->
                            throw new InvalidAnalyzerException(
                                    "collation: the JDK's collator has no strength QUATERNARY", null);
                    });
        }
        if (collation.decomposition() != null) {
            collator.setDecomposition(
                    switch (collation.decomposition()) {
                        case NO -> Collator.NO_DECOMPOSITION;
                        case CANONICAL -> Collator.CANONICAL_DECOMPOSITION;
                        case FULL -> Collator.FULL_DECOMPOSITION;
                    });
        }
        return collator;
    }

    private static void setMaxTokenLength(Preset preset, Analyzer analyzer, int length)
            throws InvalidAnalyzerException {
        try {
            Objects.requireNonNull(preset.maxTokenLengthSetter()).invoke(analyzer, length);
        } catch (InvocationTargetException e) {
            analyzer.close();
            throw failed(preset, e.getTargetException());
        } catch (ReflectiveOperationException e) {
            analyzer.close();
            throw new InvalidAnalyzerException(preset.name() + ": " + e, e);
        }
    }

    /** The set of {@code words}, if given. */
    private static @Nullable CharArraySet given(String what, AnalyzerSpec.@Nullable WordSet words)
            throws InvalidAnalyzerException {
        return words != null ? set(what, words) : null;
    }

    private static CharArraySet stopwordsOrDefault(@Nullable CharArraySet stopwords, Preset preset)
            throws InvalidAnalyzerException {
        return stopwords != null ? stopwords : defaultStopwords(preset);
    }

    /** What the preset uses without stop words of its own. */
    private static CharArraySet defaultStopwords(Preset preset) throws InvalidAnalyzerException {
        if (StopwordAnalyzerBase.class.isAssignableFrom(preset.type()) && preset.noArguments() != null) {
            try (Analyzer analyzer = construct(preset, Objects.requireNonNull(preset.noArguments()))) {
                return ((StopwordAnalyzerBase) analyzer).getStopwordSet();
            }
        }
        try {
            return (CharArraySet)
                    Objects.requireNonNull(preset.defaultStopSet()).invoke(null);
        } catch (InvocationTargetException e) {
            throw failed(preset, e.getTargetException());
        } catch (ReflectiveOperationException e) {
            throw new InvalidAnalyzerException(preset.name() + ": " + e, e);
        }
    }

    private static Analyzer construct(Preset preset, Constructor<? extends Analyzer> constructor, Object... arguments)
            throws InvalidAnalyzerException {
        try {
            return constructor.newInstance(arguments);
        } catch (InvocationTargetException e) {
            throw failed(preset, e.getTargetException());
        } catch (ReflectiveOperationException e) {
            throw new InvalidAnalyzerException(preset.name() + ": " + e, e);
        }
    }

    private static InvalidAnalyzerException failed(Preset preset, Throwable cause) {
        return new InvalidAnalyzerException(preset.name() + ": " + Failures.message(cause), cause);
    }

    private static Preset preset(Class<? extends Analyzer> type) {
        return new Preset(
                type,
                constructor(type).orElse(null),
                constructor(type, CharArraySet.class).orElse(null),
                constructor(type, CharArraySet.class, CharArraySet.class).orElse(null),
                constructor(type, CharArraySet.class, CharArraySet.class, CharArrayMap.class)
                        .orElse(null),
                constructor(type, Collator.class).orElse(null),
                type == WhitespaceAnalyzer.class ? constructor(type, int.class).orElse(null) : null,
                maxTokenLengthSetter(type).orElse(null),
                defaultStopSet(type).orElse(null));
    }

    /** The public {@code setMaxTokenLength(int)}, if there is one. */
    private static Optional<Method> maxTokenLengthSetter(Class<? extends Analyzer> type) {
        try {
            Method method = type.getMethod("setMaxTokenLength", int.class);
            return Modifier.isStatic(method.getModifiers()) ? Optional.empty() : Optional.of(method);
        } catch (NoSuchMethodException _) {
            return Optional.empty();
        }
    }

    /** The public constructor with these parameters, if there is one. */
    private static Optional<Constructor<? extends Analyzer>> constructor(
            Class<? extends Analyzer> type, Class<?>... parameters) {
        try {
            return Optional.of(type.getConstructor(parameters));
        } catch (NoSuchMethodException _) {
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
        } catch (NoSuchMethodException _) {
            return Optional.empty();
        }
    }
}
