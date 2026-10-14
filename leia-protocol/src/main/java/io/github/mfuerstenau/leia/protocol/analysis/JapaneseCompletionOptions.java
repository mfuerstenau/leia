package io.github.mfuerstenau.leia.protocol.analysis;

import java.util.Optional;

/**
 * The options of Kuromoji's {@code JapaneseCompletionAnalyzer} (ADR 0038); each is the analyzer's
 * default when absent.
 *
 * @param userDictionary a user dictionary file in Kuromoji's CSV format, UTF-8; absent for none
 * @param mode whether text is romanized for the index or for a query; absent for {@code INDEX}
 */
public record JapaneseCompletionOptions(Optional<String> userDictionary, Optional<JapaneseCompletionMode> mode) {}
