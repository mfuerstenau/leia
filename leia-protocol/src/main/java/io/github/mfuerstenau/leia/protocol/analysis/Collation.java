package io.github.mfuerstenau.leia.protocol.analysis;

import java.util.Optional;

/**
 * The collator of a collation analyzer, such as {@code CollationKeyAnalyzer} (ADR 0038): the JDK's
 * {@code java.text.Collator} or ICU's {@code com.ibm.icu.text.Collator}, whichever the analyzer
 * takes.
 *
 * @param locale a BCP 47 language tag, such as {@code de} or {@code de-AT}; {@code ""} for the root
 *     locale. ICU also reads the {@code -u-} keywords of the tag, such as {@code de-u-co-phonebk}.
 * @param strength the collator's strength; absent for its default
 * @param decomposition the collator's decomposition; absent for its default
 */
public record Collation(
        String locale, Optional<CollationStrength> strength, Optional<CollationDecomposition> decomposition) {}
