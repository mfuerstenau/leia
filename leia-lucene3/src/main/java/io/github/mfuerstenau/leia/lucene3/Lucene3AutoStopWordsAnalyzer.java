package io.github.mfuerstenau.leia.lucene3;

import java.io.Reader;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.StopFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.document.Fieldable;
import org.apache.lucene.util.Version;

/**
 * An analyzer whose tokens of some fields lose stop words after its whole chain, as Lucene's {@code
 * QueryAutoStopWordAnalyzer} removes them (ADR 0039, ADR 0061): leia-core's {@code
 * AutoStopWordsAnalyzer} for 3.6 (ADR 0050), which has no {@code AnalyzerWrapper}. It is final, as
 * 3.6 asserts of an analyzer that doesn't make {@code tokenStream} final, and closes the analyzer it
 * wraps.
 */
final class Lucene3AutoStopWordsAnalyzer extends Analyzer {

    private final Analyzer delegate;
    private final Map<String, CharArraySet> words;

    Lucene3AutoStopWordsAnalyzer(Analyzer delegate, Map<String, List<String>> words) {
        this.delegate = delegate;
        this.words = words.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey, entry -> new CharArraySet(Version.LUCENE_36, entry.getValue(), false)));
    }

    @Override
    public TokenStream tokenStream(String fieldName, Reader reader) {
        TokenStream tokens = delegate.tokenStream(fieldName, reader);
        CharArraySet stopWords = words.get(fieldName);
        return stopWords == null ? tokens : new StopFilter(Version.LUCENE_36, tokens, stopWords);
    }

    @Override
    public int getPositionIncrementGap(String fieldName) {
        return delegate.getPositionIncrementGap(fieldName);
    }

    @Override
    public int getOffsetGap(Fieldable field) {
        return delegate.getOffsetGap(field);
    }

    @Override
    public void close() {
        super.close();
        delegate.close();
    }
}
