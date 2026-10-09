package io.github.mfuerstenau.leia.protocol.analysis;

import java.util.Optional;

/**
 * The options of Nori's {@code KoreanAnalyzer} (ADR 0023); each is the analyzer's default when
 * absent.
 *
 * @param userDictionary a user dictionary file in Nori's format, UTF-8; absent for none
 * @param decompoundMode what happens to compound words; absent for {@code DISCARD}
 * @param stopTags the parts of speech that are removed, as {@code POS.Tag} names such as {@code JX};
 *     absent for the analyzer's default ones
 * @param outputUnknownUnigrams whether unknown words are split into unigrams; absent for {@code
 *     false}
 */
public record KoreanOptions(
        Optional<String> userDictionary,
        Optional<KoreanDecompoundMode> decompoundMode,
        Optional<WordSet> stopTags,
        Optional<Boolean> outputUnknownUnigrams) {}
