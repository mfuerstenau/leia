package io.github.mfuerstenau.leia.protocol.analysis;

/**
 * An analyzer created by class name with its public no-argument constructor (ADR 0015).
 *
 * @param className the fully qualified class name, one that {@code getAnalysisComponents} lists in
 *     {@code analyzers}
 */
public record PresetAnalyzer(String className) {}
