package io.github.mfuerstenau.leia.lucene5;

import io.github.mfuerstenau.leia.runtime.Search.AnalyzerSpec;
import io.github.mfuerstenau.leia.runtime.Search.UnsupportedAnalyzerException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.ar.ArabicAnalyzer;
import org.apache.lucene.analysis.bg.BulgarianAnalyzer;
import org.apache.lucene.analysis.br.BrazilianAnalyzer;
import org.apache.lucene.analysis.ca.CatalanAnalyzer;
import org.apache.lucene.analysis.cjk.CJKAnalyzer;
import org.apache.lucene.analysis.ckb.SoraniAnalyzer;
import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.apache.lucene.analysis.core.SimpleAnalyzer;
import org.apache.lucene.analysis.core.StopAnalyzer;
import org.apache.lucene.analysis.core.UnicodeWhitespaceAnalyzer;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.analysis.cz.CzechAnalyzer;
import org.apache.lucene.analysis.da.DanishAnalyzer;
import org.apache.lucene.analysis.de.GermanAnalyzer;
import org.apache.lucene.analysis.el.GreekAnalyzer;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.es.SpanishAnalyzer;
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
import org.apache.lucene.analysis.miscellaneous.LimitTokenCountAnalyzer;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.nl.DutchAnalyzer;
import org.apache.lucene.analysis.no.NorwegianAnalyzer;
import org.apache.lucene.analysis.pt.PortugueseAnalyzer;
import org.apache.lucene.analysis.ro.RomanianAnalyzer;
import org.apache.lucene.analysis.ru.RussianAnalyzer;
import org.apache.lucene.analysis.standard.ClassicAnalyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.standard.UAX29URLEmailAnalyzer;
import org.apache.lucene.analysis.sv.SwedishAnalyzer;
import org.apache.lucene.analysis.th.ThaiAnalyzer;
import org.apache.lucene.analysis.tr.TurkishAnalyzer;
import org.apache.lucene.analysis.util.CharArraySet;
import org.apache.lucene.util.Version;

/**
 * The analyzers of Lucene 5.5 for the query parser (ADR 0046): the six of the core presets, {@code
 * UnicodeWhitespaceAnalyzer} and {@code UAX29URLEmailAnalyzer}, and the language analyzers of
 * analyzers-common. Stop words and the longest token apply where 5.5 takes them; anything else is
 * refused.
 *
 * <p>The analyzers are a table of class literals, which the relocation rewrites, built through their
 * no-arg and {@code (CharArraySet)} constructors.
 */
final class Lucene5Analyzers {

    private static final List<Class<? extends Analyzer>> TYPES = List.of(
            StandardAnalyzer.class,
            ClassicAnalyzer.class,
            WhitespaceAnalyzer.class,
            UnicodeWhitespaceAnalyzer.class,
            SimpleAnalyzer.class,
            StopAnalyzer.class,
            KeywordAnalyzer.class,
            UAX29URLEmailAnalyzer.class,
            ArabicAnalyzer.class,
            ArmenianAnalyzer.class,
            BasqueAnalyzer.class,
            BrazilianAnalyzer.class,
            BulgarianAnalyzer.class,
            CatalanAnalyzer.class,
            CJKAnalyzer.class,
            CzechAnalyzer.class,
            DanishAnalyzer.class,
            DutchAnalyzer.class,
            EnglishAnalyzer.class,
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
            NorwegianAnalyzer.class,
            PersianAnalyzer.class,
            PortugueseAnalyzer.class,
            RomanianAnalyzer.class,
            RussianAnalyzer.class,
            SoraniAnalyzer.class,
            SpanishAnalyzer.class,
            SwedishAnalyzer.class,
            ThaiAnalyzer.class,
            TurkishAnalyzer.class);

    private static final Map<String, Class<? extends Analyzer>> BY_NAME =
            TYPES.stream().collect(Collectors.toUnmodifiableMap(Class::getSimpleName, type -> type));

    static final Set<String> NAMES = BY_NAME.keySet();

    /** The analyzers whose longest token can be set. */
    private static final Set<Class<? extends Analyzer>> MAX_TOKEN_LENGTH =
            Set.of(StandardAnalyzer.class, ClassicAnalyzer.class, UAX29URLEmailAnalyzer.class);

    private Lucene5Analyzers() {}

    /** Builds {@code spec}. */
    static Analyzer analyzer(AnalyzerSpec spec) throws UnsupportedAnalyzerException {
        return switch (spec) {
            case AnalyzerSpec.Named named -> named(named.name(), named.stopwords(), named.maxTokenLength());
            case AnalyzerSpec.PerField(AnalyzerSpec fallback, Map<String, AnalyzerSpec> fields) -> {
                Map<String, Analyzer> analyzers = new HashMap<>();
                for (Map.Entry<String, AnalyzerSpec> field : fields.entrySet()) {
                    analyzers.put(field.getKey(), analyzer(field.getValue()));
                }
                yield new PerFieldAnalyzerWrapper(analyzer(fallback), analyzers);
            }
            case AnalyzerSpec.LimitTokenCount(AnalyzerSpec analyzer, int maxTokenCount) ->
                new LimitTokenCountAnalyzer(analyzer(analyzer), maxTokenCount);
            case AnalyzerSpec.AutoStopWords(AnalyzerSpec analyzer, Map<String, List<String>> words) ->
                new Lucene5AutoStopWordsAnalyzer(analyzer(analyzer), words);
        };
    }

    private static Analyzer named(String name, Optional<List<String>> stopwords, OptionalInt maxTokenLength)
            throws UnsupportedAnalyzerException {
        Class<? extends Analyzer> type = BY_NAME.get(name);
        if (type == null) {
            throw new UnsupportedAnalyzerException("Lucene " + Version.LATEST + " has no analyzer " + name + "; it has "
                    + String.join(", ", new TreeSet<>(NAMES)));
        }
        Optional<Constructor<? extends Analyzer>> withStopwords = constructor(type, CharArraySet.class);
        if (stopwords.isPresent() && withStopwords.isEmpty()) {
            throw new UnsupportedAnalyzerException(
                    "The " + name + " of Lucene " + Version.LATEST + " takes no stop words");
        }
        if (maxTokenLength.isPresent() && !MAX_TOKEN_LENGTH.contains(type)) {
            throw new UnsupportedAnalyzerException(
                    "The " + name + " of Lucene " + Version.LATEST + " takes no longest token");
        }
        Analyzer analyzer;
        try {
            analyzer = stopwords.isPresent()
                    ? withStopwords.orElseThrow().newInstance(new CharArraySet(stopwords.get(), false))
                    : type.getConstructor().newInstance();
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("Cannot build the " + name + ": " + e.getCause(), e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot build the " + name + ": " + e, e);
        }
        if (maxTokenLength.isPresent()) {
            int length = maxTokenLength.getAsInt();
            switch (analyzer) {
                case StandardAnalyzer standard -> standard.setMaxTokenLength(length);
                case ClassicAnalyzer classic -> classic.setMaxTokenLength(length);
                case UAX29URLEmailAnalyzer email -> email.setMaxTokenLength(length);
                default -> throw new IllegalStateException("Not in MAX_TOKEN_LENGTH: " + name);
            }
        }
        return analyzer;
    }

    /** The public constructor of {@code type} that takes a {@code parameter}; empty if it has none. */
    private static Optional<Constructor<? extends Analyzer>> constructor(
            Class<? extends Analyzer> type, Class<?> parameter) {
        try {
            return Optional.of(type.getConstructor(parameter));
        } catch (NoSuchMethodException e) {
            return Optional.empty();
        }
    }
}
