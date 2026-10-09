package io.github.mfuerstenau.leia.protocol.analysis;

import java.util.Optional;

/**
 * The options of Kuromoji's {@code JapaneseAnalyzer} (ADR 0023); each is the analyzer's default when
 * absent.
 *
 * @param userDictionary a user dictionary file in Kuromoji's CSV format, UTF-8; absent for none
 * @param mode the tokenizer's mode; absent for {@code SEARCH}
 * @param stopTags the parts of speech that are removed, such as {@code 助詞-格助詞-一般}; absent for the
 *     analyzer's default ones
 */
public record JapaneseOptions(
        Optional<String> userDictionary, Optional<JapaneseMode> mode, Optional<WordSet> stopTags) {}
