package io.github.mfuerstenau.leia.core;

import io.github.mfuerstenau.leia.core.Queries.Operator;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Optional;
import org.apache.lucene.document.DateTools;

/**
 * The options that the classic and the standard query parser share (ADR 0011, ADR 0026).
 *
 * @param defaultOperator how clauses without an operator are combined
 * @param allowLeadingWildcard whether a term may start with {@code *} or {@code ?}
 * @param phraseSlop the slop of phrases that give none
 * @param fuzzyPrefixLength how many leading characters of a fuzzy term must match exactly
 * @param enablePositionIncrements whether phrases keep the gaps that the analyzer leaves
 * @param dateResolution the resolution that range bounds which are dates are turned into terms
 *     with, for every field; empty, the bounds stay text
 * @param locale the locale that range bounds are read as dates in; empty, the JVM's default
 * @param timeZone the time zone that range bounds are read as dates in; empty, the JVM's default
 */
public record ParserOptions(
        Operator defaultOperator,
        boolean allowLeadingWildcard,
        int phraseSlop,
        int fuzzyPrefixLength,
        boolean enablePositionIncrements,
        Optional<DateTools.Resolution> dateResolution,
        Optional<Locale> locale,
        Optional<ZoneId> timeZone) {

    /** @throws IllegalArgumentException if a number is out of range */
    public ParserOptions {
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
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    /** Lucene's defaults, with {@code defaultOperator}. */
    public static ParserOptions defaults(Operator defaultOperator) {
        return new ParserOptions(defaultOperator, false, 0, 0, true);
    }

    /** These options with the date settings of range bounds. */
    public ParserOptions withDates(
            Optional<DateTools.Resolution> dateResolution, Optional<Locale> locale, Optional<ZoneId> timeZone) {
        return new ParserOptions(
                defaultOperator,
                allowLeadingWildcard,
                phraseSlop,
                fuzzyPrefixLength,
                enablePositionIncrements,
                dateResolution,
                locale,
                timeZone);
    }
}
