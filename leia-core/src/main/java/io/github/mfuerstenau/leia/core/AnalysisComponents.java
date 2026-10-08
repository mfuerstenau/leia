package io.github.mfuerstenau.leia.core;

import java.util.List;

/**
 * The factories that a {@link AnalyzerSpec.Custom} can name, each sorted by name, ignoring case
 * (ADR 0014), and the analyzers that a {@link AnalyzerSpec.Preset} can name (ADR 0015).
 *
 * @param analyzers the presets: the simple class name, and the class name a preset takes
 */
public record AnalysisComponents(
        List<Available> analyzers,
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
     * @param name the SPI name, such as {@code lowercase}
     * @param className the factory class, whose documentation lists the parameters
     */
    public record Available(String name, String className) {}
}
