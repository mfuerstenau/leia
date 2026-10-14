package io.github.mfuerstenau.leia.protocol.analysis;

import java.util.Optional;
import java.util.OptionalInt;

/**
 * An analyzer created by class name (ADR 0015), with its default settings and, if given, stop words
 * and stem exclusions of its own (ADR 0017), and the options it takes (ADR 0023, ADR 0038).
 *
 * @param className the fully qualified class name, one that {@code getAnalysisComponents} lists in
 *     {@code analyzers}
 * @param stopwords the stop words; absent for the analyzer's default ones
 * @param stemExclusion the words kept from stemming; absent for none
 * @param maxTokenLength the longest token, from 1 to 1,048,576; absent for the analyzer's default
 *     ({@link PresetOption#MAX_TOKEN_LENGTH})
 * @param stemOverride the words whose stem is given; absent for the analyzer's default ones ({@link
 *     PresetOption#STEM_OVERRIDE})
 * @param japanese the options of Kuromoji's {@code JapaneseAnalyzer}; absent for its defaults
 *     ({@link PresetOption#JAPANESE})
 * @param korean the options of Nori's {@code KoreanAnalyzer}; absent for its defaults ({@link
 *     PresetOption#KOREAN})
 * @param collation the collator of a collation analyzer; absent for the root locale and the
 *     collator's defaults ({@link PresetOption#COLLATION}, ADR 0038)
 * @param japaneseCompletion the options of Kuromoji's {@code JapaneseCompletionAnalyzer}; absent for
 *     its defaults ({@link PresetOption#JAPANESE_COMPLETION}, ADR 0038)
 */
public record PresetAnalyzer(
        String className,
        Optional<WordSet> stopwords,
        Optional<WordSet> stemExclusion,
        OptionalInt maxTokenLength,
        Optional<StemDictionary> stemOverride,
        Optional<JapaneseOptions> japanese,
        Optional<KoreanOptions> korean,
        Optional<Collation> collation,
        Optional<JapaneseCompletionOptions> japaneseCompletion) {}
