package io.github.mfuerstenau.leia.protocol.jars;

import java.util.List;
import java.util.Optional;

/**
 * The runtime of an Elasticsearch or OpenSearch installation that {@code --server} names (ADR 0090),
 * which reads the indexes of its majors with the installation's Lucene, codecs and formats.
 *
 * @param home the installation, or its {@code lib/}, as an absolute path
 * @param label the product and its version, such as {@code Elasticsearch 8.15.3}; empty if its
 *     {@code lib/} doesn't say
 * @param luceneVersion the version of the installation's Lucene, such as {@code 9.11.1}
 * @param indexMajors the major versions of Lucene whose indexes it reads, oldest first
 */
public record ServerRuntimeInfo(String home, Optional<String> label, String luceneVersion, List<Integer> indexMajors) {}
