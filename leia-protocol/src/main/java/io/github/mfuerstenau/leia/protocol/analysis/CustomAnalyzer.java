package io.github.mfuerstenau.leia.protocol.analysis;

import java.util.List;
import java.util.Optional;

/**
 * A {@code CustomAnalyzer} (ADR 0014): the text goes through the char filters, the tokenizer and
 * the token filters, in that order.
 *
 * @param charFilters the char filters, in order
 * @param tokenizer the tokenizer
 * @param tokenFilters the token filters, in order
 * @param configDir the directory that resource files, such as stop words, are read from before
 *     the classpath; absent for the classpath only
 */
public record CustomAnalyzer(
        List<AnalysisComponent> charFilters,
        AnalysisComponent tokenizer,
        List<AnalysisComponent> tokenFilters,
        Optional<String> configDir) {}
