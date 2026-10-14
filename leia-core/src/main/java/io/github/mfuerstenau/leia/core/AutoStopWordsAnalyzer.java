package io.github.mfuerstenau.leia.core;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.AnalyzerWrapper;
import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.StopFilter;

/**
 * An analyzer whose tokens of some fields lose stop words (ADR 0039): what Lucene's {@code
 * QueryAutoStopWordAnalyzer} does with the words it finds, here with words given, since Lucene's
 * constructor scans the index itself. It closes the analyzer it wraps.
 *
 * @see AnalyzerSpec.AutoStopWords
 */
final class AutoStopWordsAnalyzer extends AnalyzerWrapper {

    private final Analyzer delegate;
    private final Map<String, CharArraySet> words;

    AutoStopWordsAnalyzer(Analyzer delegate, Map<String, List<String>> words) {
        // The words differ by field, so the components do.
        super(PER_FIELD_REUSE_STRATEGY);
        this.delegate = delegate;
        this.words = words.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey, entry -> new CharArraySet(entry.getValue(), false)));
    }

    /** What is wrapped. */
    Analyzer delegate() {
        return delegate;
    }

    /** The stop words of {@code field}; empty if it has no entry. */
    Optional<CharArraySet> words(String field) {
        return Optional.ofNullable(words.get(field));
    }

    @Override
    protected Analyzer getWrappedAnalyzer(String fieldName) {
        return delegate;
    }

    @Override
    protected TokenStreamComponents wrapComponents(String fieldName, TokenStreamComponents components) {
        CharArraySet stopWords = words.get(fieldName);
        if (stopWords == null) {
            return components;
        }
        return new TokenStreamComponents(
                components.getSource(), new StopFilter(components.getTokenStream(), stopWords));
    }

    @Override
    public void close() {
        super.close();
        delegate.close();
    }
}
