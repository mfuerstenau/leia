package io.github.mfuerstenau.leia.protocol.analysis;

import java.util.Map;

/**
 * A char filter, tokenizer or token filter of a custom analyzer (ADR 0014).
 *
 * @param name the factory's SPI name, such as {@code lowercase}; case doesn't matter
 * @param params the factory's parameters, such as {@code words: stopwords.txt}
 */
public record AnalysisComponent(String name, Map<String, String> params) {}
