package io.github.mfuerstenau.leia.protocol.analysis;

import java.util.Map;
import java.util.Optional;

/**
 * A preset's stem override dictionary, as one of two alternatives: exactly one is present (ADR
 * 0023). It is case-sensitive.
 *
 * @param entries each word and its stem; empty for no overrides
 * @param file a dictionary file that the server reads: UTF-8, {@code word<TAB>stem} per line, and
 *     blank lines and lines starting with {@code #} are skipped
 */
public record StemDictionary(Optional<Map<String, String>> entries, Optional<String> file) {}
