package io.github.mfuerstenau.leia.core;

import java.nio.file.Path;
import org.apache.lucene.util.Version;
import org.jspecify.annotations.Nullable;

/**
 * What identifies an opened index directory: where it is, which commit was opened, and which Lucene
 * wrote it.
 *
 * @param path the absolute, normalized index directory
 * @param docBase the document id of its first document in the opened path, which may combine several
 *     index directories (ADR 0021); 0 for the first
 * @param segmentsFile the {@code segments_N} file of the opened commit
 * @param numDocs live documents
 * @param maxDoc documents including deleted ones
 * @param createdVersionMajor the major Lucene version that created the index
 * @param minSegmentLuceneVersion the oldest Lucene version that wrote one of its segments; null if
 *     the index has no segments
 */
public record IndexSummary(
        Path path,
        int docBase,
        String segmentsFile,
        int numDocs,
        int maxDoc,
        int createdVersionMajor,
        @Nullable Version minSegmentLuceneVersion) {}
