package io.github.mfuerstenau.leia.protocol.analysis;

import java.util.List;
import java.util.Optional;

/**
 * The stop words or stem exclusions of a preset, as one of two alternatives: exactly one is present
 * (ADR 0017).
 *
 * @param words the words themselves; empty for a set without words
 * @param file a word list file that the server reads: UTF-8, one word per line, and lines starting
 *     with {@code #} are skipped
 */
public record WordSet(Optional<List<String>> words, Optional<String> file) {}
