package io.github.mfuerstenau.leia.protocol.analysis;

import java.util.List;

/**
 * Result of {@code getAnalysisComponents}: what a custom analyzer can be built from, each sorted by
 * name, ignoring case (ADR 0014).
 */
public record GetAnalysisComponentsResult(
        List<AvailableComponent> charFilters,
        List<AvailableComponent> tokenizers,
        List<AvailableComponent> tokenFilters) {}
