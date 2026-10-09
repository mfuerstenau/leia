package io.github.mfuerstenau.leia.protocol.analysis;

import java.util.List;

/**
 * An analyzer that a preset can name (ADR 0015).
 *
 * @param name the simple class name, such as {@code EnglishAnalyzer}
 * @param className the fully qualified class name, which {@link PresetAnalyzer} takes
 * @param stopwords whether the preset takes stop words (ADR 0017)
 * @param stemExclusion whether it takes stem exclusions; never {@code REQUIRED}
 * @param options what else it takes (ADR 0023), in the enum's order
 */
public record AvailableAnalyzer(
        String name,
        String className,
        WordSetSupport stopwords,
        WordSetSupport stemExclusion,
        List<PresetOption> options) {}
