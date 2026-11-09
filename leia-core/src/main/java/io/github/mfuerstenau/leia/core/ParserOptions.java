package io.github.mfuerstenau.leia.core;

import io.github.mfuerstenau.leia.core.Queries.Operator;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Map;
import org.apache.lucene.document.DateTools;
import org.jspecify.annotations.Nullable;

/**
 * The options that the classic and the standard query parser share (ADR 0011, ADR 0026, ADR 0035).
 *
 * @param defaultOperator how clauses without an operator are combined
 * @param allowLeadingWildcard whether a term may start with {@code *} or {@code ?}
 * @param phraseSlop the slop of phrases that give none
 * @param fuzzyPrefixLength how many leading characters of a fuzzy term must match exactly
 * @param enablePositionIncrements whether phrases keep the gaps that the analyzer leaves
 * @param dateResolution the resolution that range bounds which are dates are turned into terms
 *     with, for the fields without one of their own; null, the bounds on those fields stay text
 * @param fieldDateResolutions the resolutions of single fields, by field name, which win over
 *     {@code dateResolution}
 * @param locale the locale that range bounds are read as dates in; null, the JVM's default
 * @param timeZone the time zone that range bounds are read as dates in; null, the JVM's default
 */
public record ParserOptions(
        Operator defaultOperator,
        boolean allowLeadingWildcard,
        int phraseSlop,
        int fuzzyPrefixLength,
        boolean enablePositionIncrements,
        DateTools.@Nullable Resolution dateResolution,
        Map<String, DateTools.Resolution> fieldDateResolutions,
        @Nullable Locale locale,
        @Nullable ZoneId timeZone) {

    /** @throws IllegalArgumentException if a number is out of range */
    public ParserOptions {
        fieldDateResolutions = Map.copyOf(fieldDateResolutions);
        if (phraseSlop < 0) {
            throw new IllegalArgumentException("phraseSlop must be 0 or more, not " + phraseSlop);
        }
        if (fuzzyPrefixLength < 0) {
            throw new IllegalArgumentException("fuzzyPrefixLength must be 0 or more, not " + fuzzyPrefixLength);
        }
    }

    /** Options without date settings. */
    public ParserOptions(
            Operator defaultOperator,
            boolean allowLeadingWildcard,
            int phraseSlop,
            int fuzzyPrefixLength,
            boolean enablePositionIncrements) {
        this(
                defaultOperator,
                allowLeadingWildcard,
                phraseSlop,
                fuzzyPrefixLength,
                enablePositionIncrements,
                null,
                Map.of(),
                null,
                null);
    }

    /** Lucene's defaults, with {@code defaultOperator}. */
    public static ParserOptions defaults(Operator defaultOperator) {
        return new ParserOptions(defaultOperator, false, 0, 0, true);
    }

    /** These options with the date settings of range bounds. */
    public ParserOptions withDates(
            DateTools.@Nullable Resolution dateResolution, @Nullable Locale locale, @Nullable ZoneId timeZone) {
        return new ParserOptions(
                defaultOperator,
                allowLeadingWildcard,
                phraseSlop,
                fuzzyPrefixLength,
                enablePositionIncrements,
                dateResolution,
                fieldDateResolutions,
                locale,
                timeZone);
    }

    /** These options with the resolutions of single fields. */
    public ParserOptions withFieldDateResolutions(Map<String, DateTools.Resolution> fieldDateResolutions) {
        return new ParserOptions(
                defaultOperator,
                allowLeadingWildcard,
                phraseSlop,
                fuzzyPrefixLength,
                enablePositionIncrements,
                dateResolution,
                fieldDateResolutions,
                locale,
                timeZone);
    }
}
