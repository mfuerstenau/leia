package io.github.mfuerstenau.leia.protocol.analysis;

import java.util.Optional;

/**
 * An analyzer created by class name (ADR 0015), with its default settings and, if given, stop words
 * and stem exclusions of its own (ADR 0017).
 *
 * @param className the fully qualified class name, one that {@code getAnalysisComponents} lists in
 *     {@code analyzers}
 * @param stopwords the stop words; absent for the analyzer's default ones
 * @param stemExclusion the words kept from stemming; absent for none
 */
public record PresetAnalyzer(String className, Optional<WordSet> stopwords, Optional<WordSet> stemExclusion) {}
