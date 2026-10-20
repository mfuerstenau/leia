package io.github.mfuerstenau.leia.lucene3;

import io.github.mfuerstenau.leia.runtime.Search.AnalyzerSpec;
import io.github.mfuerstenau.leia.runtime.Search.InvalidAnalyzerException;
import io.github.mfuerstenau.leia.runtime.Search.UnsupportedAnalyzerException;
import io.github.mfuerstenau.leia.runtime.support.WordFiles;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.KeywordAnalyzer;
import org.apache.lucene.analysis.LimitTokenCountAnalyzer;
import org.apache.lucene.analysis.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.SimpleAnalyzer;
import org.apache.lucene.analysis.StopAnalyzer;
import org.apache.lucene.analysis.WhitespaceAnalyzer;
import org.apache.lucene.analysis.standard.ClassicAnalyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.util.Constants;
import org.apache.lucene.util.Version;

/**
 * The analyzers of lucene-core 3.6 for the query parser (ADR 0045), each of {@link Version#LUCENE_36}.
 * Stop words and the longest token apply where 3.6 takes them; anything else is refused.
 */
final class Lucene3Analyzers {

    private static final Set<String> NAMES = Set.of(
            "StandardAnalyzer",
            "ClassicAnalyzer",
            "WhitespaceAnalyzer",
            "SimpleAnalyzer",
            "StopAnalyzer",
            "KeywordAnalyzer");

    private Lucene3Analyzers() {}

    /**
     * Builds {@code spec}: the standard analyzer, a preset of {@link #NAMES} by its simple name with
     * stop words and the longest token, a token limit, analyzers per field and auto stop words (ADR
     * 0067). 3.6's token limit always stops reading at the limit: {@code consumeAllTokens} changes no
     * token.
     *
     * @throws UnsupportedAnalyzerException for anything else
     * @throws InvalidAnalyzerException for a stop words file that can't be read, or a token limit
     *     below 1
     */
    static Analyzer analyzer(AnalyzerSpec spec) throws UnsupportedAnalyzerException, InvalidAnalyzerException {
        return switch (spec) {
            case AnalyzerSpec.Standard _ -> named("StandardAnalyzer", Optional.empty(), OptionalInt.empty());
            case AnalyzerSpec.Preset preset -> {
                String name = preset.simpleName();
                if (NAMES.contains(name)
                        && (preset.stemExclusion().isPresent()
                                || preset.stemOverride().isPresent()
                                || preset.japanese().isPresent()
                                || preset.korean().isPresent()
                                || preset.collation().isPresent()
                                || preset.japaneseCompletion().isPresent())) {
                    throw new UnsupportedAnalyzerException(
                            "options of " + name + " other than stop words and the longest token");
                }
                Optional<List<String>> stopwords = preset.stopwords().isPresent()
                        ? Optional.of(
                                WordFiles.words("stop words", preset.stopwords().get()))
                        : Optional.empty();
                yield named(name, stopwords, preset.maxTokenLength());
            }
            case AnalyzerSpec.Custom _ -> throw new UnsupportedAnalyzerException("custom analyzers");
            case AnalyzerSpec.Wrapped wrapped -> {
                if (wrapped.shingles().isPresent()) {
                    throw new UnsupportedAnalyzerException("shingles");
                }
                if (wrapped.limitTokenCount().isEmpty()) {
                    yield analyzer(wrapped.analyzer());
                }
                int maxTokenCount = wrapped.limitTokenCount().get().maxTokenCount();
                if (maxTokenCount < 1) {
                    throw new InvalidAnalyzerException(
                            "token limit: the maximum token count must be at least 1, not " + maxTokenCount, null);
                }
                yield new LimitTokenCountAnalyzer(analyzer(wrapped.analyzer()), maxTokenCount);
            }
            case AnalyzerSpec.PerField(AnalyzerSpec fallback, Map<String, AnalyzerSpec> fields) -> {
                Map<String, Analyzer> analyzers = new HashMap<>();
                for (Map.Entry<String, AnalyzerSpec> field : fields.entrySet()) {
                    analyzers.put(field.getKey(), analyzer(field.getValue()));
                }
                yield new PerFieldAnalyzerWrapper(analyzer(fallback), analyzers);
            }
            case AnalyzerSpec.AutoStopWords(AnalyzerSpec analyzer, Map<String, List<String>> words) ->
                new Lucene3AutoStopWordsAnalyzer(analyzer(analyzer), words);
        };
    }

    private static Analyzer named(String name, Optional<List<String>> stopwords, OptionalInt maxTokenLength)
            throws UnsupportedAnalyzerException {
        if (!NAMES.contains(name)) {
            throw new UnsupportedAnalyzerException("Lucene " + Constants.LUCENE_MAIN_VERSION + " has no analyzer "
                    + name + "; it has " + String.join(", ", new TreeSet<>(NAMES)));
        }
        boolean takesStopwords =
                Set.of("StandardAnalyzer", "ClassicAnalyzer", "StopAnalyzer").contains(name);
        boolean takesMaxTokenLength =
                Set.of("StandardAnalyzer", "ClassicAnalyzer").contains(name);
        if (stopwords.isPresent() && !takesStopwords) {
            throw new UnsupportedAnalyzerException(
                    "The " + name + " of Lucene " + Constants.LUCENE_MAIN_VERSION + " takes no stop words");
        }
        if (maxTokenLength.isPresent() && !takesMaxTokenLength) {
            throw new UnsupportedAnalyzerException(
                    "The " + name + " of Lucene " + Constants.LUCENE_MAIN_VERSION + " takes no longest token");
        }
        Optional<CharArraySet> stopSet = stopwords.map(words -> new CharArraySet(Version.LUCENE_36, words, false));
        return switch (name) {
            case "StandardAnalyzer" -> {
                StandardAnalyzer analyzer = stopSet.isPresent()
                        ? new StandardAnalyzer(Version.LUCENE_36, stopSet.get())
                        : new StandardAnalyzer(Version.LUCENE_36);
                maxTokenLength.ifPresent(analyzer::setMaxTokenLength);
                yield analyzer;
            }
            case "ClassicAnalyzer" -> {
                ClassicAnalyzer analyzer = stopSet.isPresent()
                        ? new ClassicAnalyzer(Version.LUCENE_36, stopSet.get())
                        : new ClassicAnalyzer(Version.LUCENE_36);
                maxTokenLength.ifPresent(analyzer::setMaxTokenLength);
                yield analyzer;
            }
            case "StopAnalyzer" ->
                stopSet.isPresent()
                        ? new StopAnalyzer(Version.LUCENE_36, stopSet.get())
                        : new StopAnalyzer(Version.LUCENE_36);
            case "WhitespaceAnalyzer" -> new WhitespaceAnalyzer(Version.LUCENE_36);
            case "SimpleAnalyzer" -> new SimpleAnalyzer(Version.LUCENE_36);
            case "KeywordAnalyzer" -> new KeywordAnalyzer();
            default -> throw new IllegalStateException("Not in NAMES: " + name);
        };
    }
}
