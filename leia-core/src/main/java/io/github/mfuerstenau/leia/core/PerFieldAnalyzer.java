package io.github.mfuerstenau.leia.core;

import java.util.Map;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.DelegatingAnalyzerWrapper;

/**
 * An analyzer per field (ADR 0039): what Lucene's {@code PerFieldAnalyzerWrapper} does, and it also
 * closes the analyzers, which Lucene's doesn't.
 *
 * @see AnalyzerSpec.PerField
 */
final class PerFieldAnalyzer extends DelegatingAnalyzerWrapper {

    private final Analyzer defaultAnalyzer;
    private final Map<String, Analyzer> fields;

    PerFieldAnalyzer(Analyzer defaultAnalyzer, Map<String, Analyzer> fields) {
        super(PER_FIELD_REUSE_STRATEGY);
        this.defaultAnalyzer = defaultAnalyzer;
        this.fields = Map.copyOf(fields);
    }

    /** The analyzer of {@code field}: its own, or the default one. */
    Analyzer analyzer(String field) {
        return fields.getOrDefault(field, defaultAnalyzer);
    }

    @Override
    protected Analyzer getWrappedAnalyzer(String fieldName) {
        return analyzer(fieldName);
    }

    @Override
    public void close() {
        super.close();
        fields.values().forEach(Analyzer::close);
        defaultAnalyzer.close();
    }
}
