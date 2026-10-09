package io.github.mfuerstenau.leia.protocol.search;

import java.util.Optional;

/**
 * The options that the classic and the standard query parser share (ADR 0011, ADR 0026).
 *
 * @param allowLeadingWildcard whether a term may start with {@code *} or {@code ?}
 * @param phraseSlop the slop of phrases that give none, 0 or more
 * @param fuzzyPrefixLength how many leading characters of a fuzzy term must match exactly, 0 or more
 * @param enablePositionIncrements whether phrases keep the gaps that the analyzer leaves
 * @param dateResolution the resolution that range bounds which are dates are turned into terms with,
 *     for every field; absent, the bounds stay text
 * @param locale the BCP 47 tag of the locale that range bounds are read as dates in, such as
 *     {@code en-US}; absent, the server JVM's default
 * @param timeZone the time zone that range bounds are read as dates in, as {@code ZoneId.of} takes
 *     it, such as {@code UTC}, {@code Europe/Berlin} or {@code +02:00}; absent, the server JVM's
 *     default
 */
public record ParserOptions(
        boolean allowLeadingWildcard,
        int phraseSlop,
        int fuzzyPrefixLength,
        boolean enablePositionIncrements,
        Optional<DateResolution> dateResolution,
        Optional<String> locale,
        Optional<String> timeZone) {}
