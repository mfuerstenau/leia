package io.github.mfuerstenau.leia.protocol.analysis;

import java.util.List;

/**
 * Result of {@code getAnalysisComponents}: the analyzers that a preset can name (ADR 0015), and
 * what a custom analyzer can be built from (ADR 0014), each sorted by name, ignoring case.
 */
public record GetAnalysisComponentsResult(
        List<AvailableAnalyzer> analyzers,
        List<AvailableComponent> charFilters,
        List<AvailableComponent> tokenizers,
        List<AvailableComponent> tokenFilters) {}
