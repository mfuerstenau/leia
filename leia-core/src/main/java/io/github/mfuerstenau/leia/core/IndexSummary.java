package io.github.mfuerstenau.leia.core;

import java.nio.file.Path;
import java.util.Optional;
import org.apache.lucene.util.Version;

/**
 * What identifies an opened index: where it is, which commit was opened, and which Lucene wrote it.
 *
 * @param path the absolute, normalized index directory
 * @param segmentsFile the {@code segments_N} file of the opened commit
 * @param numDocs live documents
 * @param maxDoc documents including deleted ones
 * @param createdVersionMajor the major Lucene version that created the index
 * @param minSegmentLuceneVersion the oldest Lucene version that wrote one of its segments; empty if
 *     the index has no segments
 */
public record IndexSummary(
        Path path,
        String segmentsFile,
        int numDocs,
        int maxDoc,
        int createdVersionMajor,
        Optional<Version> minSegmentLuceneVersion) {}
