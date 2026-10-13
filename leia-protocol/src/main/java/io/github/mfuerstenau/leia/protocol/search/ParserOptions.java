package io.github.mfuerstenau.leia.protocol.search;

import java.util.List;
import java.util.Optional;

/**
 * The options that the classic and the standard query parser share (ADR 0011, ADR 0026, ADR 0035).
 *
 * @param allowLeadingWildcard whether a term may start with {@code *} or {@code ?}
 * @param phraseSlop the slop of phrases that give none, 0 or more
 * @param fuzzyPrefixLength how many leading characters of a fuzzy term must match exactly, 0 or more
 * @param enablePositionIncrements whether phrases keep the gaps that the analyzer leaves
 * @param dateResolution the resolution that range bounds which are dates are turned into terms with,
 *     for the fields without one of their own; absent, the bounds on those fields stay text
 * @param fieldDateResolutions the resolutions of single fields, which win over {@code
 *     dateResolution}; empty for none, and each field at most once
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
        List<FieldDateResolution> fieldDateResolutions,
        Optional<String> locale,
        Optional<String> timeZone) {}
