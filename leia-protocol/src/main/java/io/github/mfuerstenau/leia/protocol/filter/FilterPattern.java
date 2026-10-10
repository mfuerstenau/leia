package io.github.mfuerstenau.leia.protocol.filter;

import java.util.List;
import java.util.Optional;

/**
 * A regular expression over stored values as the source of a filter (ADR 0032): a document matches
 * if the pattern is found in one of its values, as {@code startStoredScan} finds it (ADR 0027).
 *
 * @param pattern a Java regular expression, found anywhere in a value ({@code Matcher.find}); flags
 *     such as {@code (?s)} can be given inline
 * @param caseInsensitive whether case doesn't matter, Unicode case included
 * @param fields the stored fields to scan; absent for all of them
 */
public record FilterPattern(String pattern, boolean caseInsensitive, Optional<List<String>> fields) {}
