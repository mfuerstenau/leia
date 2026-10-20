package io.github.mfuerstenau.leia.protocol.jars;

import java.util.List;
import java.util.Optional;

/**
 * A runtime jar that leia knows (ADR 0044, ADR 0072).
 *
 * @param jar its file name in {@code runtimes/}, such as {@code leia-lucene3.jar}
 * @param indexMajors the major versions of Lucene whose indexes it reads, oldest first
 * @param luceneVersion the version of Lucene of the loaded runtime that reads them, such as {@code
 *     3.6.2}, or empty if none is loaded
 */
public record RuntimeJar(String jar, List<Integer> indexMajors, Optional<String> luceneVersion) {}
