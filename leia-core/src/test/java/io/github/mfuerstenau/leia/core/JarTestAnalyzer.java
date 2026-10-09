package io.github.mfuerstenau.leia.core;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.core.WhitespaceTokenizer;

/**
 * An analyzer that {@link ExtensionJarsTest} puts into a jar, so that the jar adds a preset (ADR
 * 0024). It is no preset without that jar: the test classes aren't scanned.
 */
public final class JarTestAnalyzer extends Analyzer {

    @Override
    protected TokenStreamComponents createComponents(String fieldName) {
        return new TokenStreamComponents(new WhitespaceTokenizer());
    }
}
