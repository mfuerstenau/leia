package io.github.mfuerstenau.leia.lucene9;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.AnalyzerWrapper;
import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.StopFilter;

/**
 * An analyzer whose tokens of some fields lose stop words after its whole chain, as Lucene's {@code
 * QueryAutoStopWordAnalyzer} removes them (ADR 0039, ADR 0061): a copy of leia-core's {@code
 * AutoStopWordsAnalyzer} (ADR 0050). It closes the analyzer it wraps.
 */
final class Lucene9AutoStopWordsAnalyzer extends AnalyzerWrapper {

    private final Analyzer delegate;
    private final Map<String, CharArraySet> words;

    Lucene9AutoStopWordsAnalyzer(Analyzer delegate, Map<String, List<String>> words) {
        // The words differ by field, so the components do.
        super(PER_FIELD_REUSE_STRATEGY);
        this.delegate = delegate;
        this.words = words.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey, entry -> new CharArraySet(entry.getValue(), false)));
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
