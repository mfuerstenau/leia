package io.github.mfuerstenau.leia.protocol.search;

import java.util.List;
import java.util.Optional;

/**
 * Params of {@code startStoredScan} (ADR 0027): a regular expression to look for in the stored
 * values of every document.
 *
 * @param pattern a Java regular expression, found anywhere in a value ({@code Matcher.find}); flags
 *     such as {@code (?s)} can be given inline
 * @param caseInsensitive whether case doesn't matter, Unicode case included
 * @param fields the stored fields to scan; absent for all of them
 * @param includeDeleted whether to scan deleted documents too
 */
public record StartStoredScanParams(
        String pattern, boolean caseInsensitive, Optional<List<String>> fields, boolean includeDeleted) {

    /** The most hits a scan keeps; it counts the matching values after them. */
    public static final int MAX_HITS = 10_000;

    /** The most match spans a hit has; {@code matchCount} counts all of them. */
    public static final int MAX_SPANS = 100;

    /** The most values that ran out of time that the result names; it counts all of them. */
    public static final int MAX_TIMED_OUT = 100;
}
