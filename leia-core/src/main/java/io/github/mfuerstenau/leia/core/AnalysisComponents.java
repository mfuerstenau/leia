package io.github.mfuerstenau.leia.core;

import java.util.List;

/**
 * The factories that a {@link AnalyzerSpec.Custom} can name, each sorted by name, ignoring case
 * (ADR 0014), and the analyzers that a {@link AnalyzerSpec.Preset} can name (ADR 0015).
 *
 * @param analyzers the presets, sorted by simple class name, ignoring case
 */
public record AnalysisComponents(
        List<AvailableAnalyzer> analyzers,
        List<Available> charFilters,
        List<Available> tokenizers,
        List<Available> tokenFilters) {

    public AnalysisComponents {
        analyzers = List.copyOf(analyzers);
        charFilters = List.copyOf(charFilters);
        tokenizers = List.copyOf(tokenizers);
        tokenFilters = List.copyOf(tokenFilters);
    }

    /**
     * A preset analyzer (ADR 0015).
     *
     * @param name the simple class name, such as {@code EnglishAnalyzer}
     * @param className the class name, which a preset takes
     * @param stopwords whether the preset takes stop words (ADR 0017)
     * @param stemExclusion whether it takes stem exclusions; never {@link WordSetSupport#REQUIRED}
     * @param options what else it takes (ADR 0023), in the enum's order
     */
    public record AvailableAnalyzer(
            String name,
            String className,
            WordSetSupport stopwords,
            WordSetSupport stemExclusion,
            List<PresetOption> options) {

        public AvailableAnalyzer {
            options = List.copyOf(options);
        }
    }

    /**
     * @param name the SPI name, such as {@code lowercase}
     * @param className the factory class, whose documentation lists the parameters
     */
    public record Available(String name, String className) {}
}
