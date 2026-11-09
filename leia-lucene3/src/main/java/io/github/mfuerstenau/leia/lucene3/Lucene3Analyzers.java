package io.github.mfuerstenau.leia.lucene3;

import io.github.mfuerstenau.leia.runtime.Search.AnalyzerSpec;
import io.github.mfuerstenau.leia.runtime.Search.InvalidAnalyzerException;
import io.github.mfuerstenau.leia.runtime.Search.UnsupportedAnalyzerException;
import io.github.mfuerstenau.leia.runtime.support.WordFiles;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import org.jspecify.annotations.Nullable;

/**
 * The analyzers of lucene-core 3.6 for the query parser (ADR 0045), each of {@link Version#LUCENE_36}.
 * Stop words and the longest token apply where 3.6 takes them; anything else is refused.
 */
final class Lucene3Analyzers {

    private static final String STOP = "StopAnalyzer";

    private static final String CLASSIC = "ClassicAnalyzer";

    private static final String STANDARD = "StandardAnalyzer";

    private static final Set<String> NAMES =
            Set.of(STANDARD, CLASSIC, "WhitespaceAnalyzer", "SimpleAnalyzer", STOP, "KeywordAnalyzer");

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
            case AnalyzerSpec.Standard _ -> named(STANDARD, null, null);
            case AnalyzerSpec.Preset preset -> {
                String name = preset.simpleName();
                if (NAMES.contains(name)
                        && (preset.stemExclusion() != null
                                || preset.stemOverride() != null
                                || preset.japanese() != null
                                || preset.korean() != null
                                || preset.collation() != null
                                || preset.japaneseCompletion() != null)) {
                    throw new UnsupportedAnalyzerException(
                            "options of " + name + " other than stop words and the longest token");
                }
                AnalyzerSpec.WordSet stopwordSet = preset.stopwords();
                List<String> stopwords = stopwordSet != null ? WordFiles.words("stop words", stopwordSet) : null;
                yield named(name, stopwords, preset.maxTokenLength());
            }
            case AnalyzerSpec.Custom _ -> throw new UnsupportedAnalyzerException("custom analyzers");
            case AnalyzerSpec.Wrapped wrapped -> {
                if (wrapped.shingles() != null) {
                    throw new UnsupportedAnalyzerException("shingles");
                }
                Optional<AnalyzerSpec.LimitTokenCount> limit = Optional.ofNullable(wrapped.limitTokenCount());
                if (limit.isEmpty()) {
                    yield analyzer(wrapped.analyzer());
                }
                int maxTokenCount = limit.get().maxTokenCount();
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

    private static Analyzer named(String name, @Nullable List<String> stopwords, @Nullable Integer maxTokenLength)
            throws UnsupportedAnalyzerException {
        if (!NAMES.contains(name)) {
            throw new UnsupportedAnalyzerException("Lucene " + Constants.LUCENE_MAIN_VERSION + " has no analyzer "
                    + name + "; it has " + String.join(", ", new TreeSet<>(NAMES)));
        }
        boolean takesStopwords = Set.of(STANDARD, CLASSIC, STOP).contains(name);
        boolean takesMaxTokenLength = Set.of(STANDARD, CLASSIC).contains(name);
        if (stopwords != null && !takesStopwords) {
            throw new UnsupportedAnalyzerException(
                    "The " + name + " of Lucene " + Constants.LUCENE_MAIN_VERSION + " takes no stop words");
        }
        if (maxTokenLength != null && !takesMaxTokenLength) {
            throw new UnsupportedAnalyzerException(
                    "The " + name + " of Lucene " + Constants.LUCENE_MAIN_VERSION + " takes no longest token");
        }
        @Nullable
        CharArraySet stopSet = stopwords == null ? null : new CharArraySet(Version.LUCENE_36, stopwords, false);
        return switch (name) {
            case STANDARD -> {
                StandardAnalyzer analyzer = stopSet != null
                        ? new StandardAnalyzer(Version.LUCENE_36, stopSet)
                        : new StandardAnalyzer(Version.LUCENE_36);
                if (maxTokenLength != null) {
                    analyzer.setMaxTokenLength(maxTokenLength);
                }
                yield analyzer;
            }
            case CLASSIC -> {
                ClassicAnalyzer analyzer = stopSet != null
                        ? new ClassicAnalyzer(Version.LUCENE_36, stopSet)
                        : new ClassicAnalyzer(Version.LUCENE_36);
                if (maxTokenLength != null) {
                    analyzer.setMaxTokenLength(maxTokenLength);
                }
                yield analyzer;
            }
            case STOP ->
                stopSet != null ? new StopAnalyzer(Version.LUCENE_36, stopSet) : new StopAnalyzer(Version.LUCENE_36);
            case "WhitespaceAnalyzer" -> new WhitespaceAnalyzer(Version.LUCENE_36);
            case "SimpleAnalyzer" -> new SimpleAnalyzer(Version.LUCENE_36);
            case "KeywordAnalyzer" -> new KeywordAnalyzer();
            default -> throw new IllegalStateException("Not in NAMES: " + name);
        };
    }
}
