package io.github.mfuerstenau.leia.core;

import java.util.List;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.DelegatingAnalyzerWrapper;

/**
 * An analyzer and Lucene's wrappers around it (ADR 0023): it delegates to the outermost wrapper,
 * and closes the wrappers and the analyzer, which a Lucene wrapper doesn't.
 *
 * @see AnalyzerSpec.Wrapped
 */
final class WrappedAnalyzer extends DelegatingAnalyzerWrapper {

    private final Analyzer base;
    private final List<Analyzer> wrappers;

    /**
     * @param base what the first wrapper wraps
     * @param wrappers the wrappers, each wrapping the one before; at least one
     */
    WrappedAnalyzer(Analyzer base, List<Analyzer> wrappers) {
        super(PER_FIELD_REUSE_STRATEGY);
        this.base = base;
        this.wrappers = List.copyOf(wrappers);
    }

    /** What the first wrapper wraps. */
    Analyzer base() {
        return base;
    }

    /** The wrappers, innermost first. */
    List<Analyzer> wrappers() {
        return wrappers;
    }

    @Override
    protected Analyzer getWrappedAnalyzer(String fieldName) {
        return wrappers.getLast();
    }

    @Override
    public void close() {
        super.close();
        wrappers.reversed().forEach(Analyzer::close);
        base.close();
    }
}
