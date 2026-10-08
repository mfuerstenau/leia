package io.github.mfuerstenau.leia.protocol.analysis;

/**
 * An analyzer that a preset can name (ADR 0015).
 *
 * @param name the simple class name, such as {@code EnglishAnalyzer}
 * @param className the fully qualified class name, which {@link PresetAnalyzer} takes
 */
public record AvailableAnalyzer(String name, String className) {}
