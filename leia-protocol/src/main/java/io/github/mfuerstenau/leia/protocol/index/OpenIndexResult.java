package io.github.mfuerstenau.leia.protocol.index;

import java.util.List;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Result of {@code openIndex}: what identifies the opened indexes (ADR 0021). The full statistics
 * come from {@code getIndexStats}.
 *
 * @param path the absolute, normalized path that was opened
 * @param mode how it was opened
 * @param numDocs live documents in all indexes; absent without a reader
 * @param maxDoc documents in all indexes, including deleted ones; absent without a reader
 * @param indexes the index directories that were opened, in order; at least one
 * @param skipped the index directories that were found but left out
 * @param features what works on the opened index; other methods give {@code NOT_SUPPORTED} or
 *     {@code NO_READER} (ADR 0044)
 * @param luceneRuntime the version of the Lucene that reads the index, such as {@code 10.5.2}, or
 *     {@code 3.6.2} for an index written by Lucene 2.x or 3.x (ADR 0044); empty for one opened without
 *     a reader whose runtime isn't loaded (ADR 0071)
 */
public record OpenIndexResult(
        String path,
        OpenMode mode,
        OptionalInt numDocs,
        OptionalInt maxDoc,
        List<IndexEntry> indexes,
        List<SkippedIndex> skipped,
        Set<Feature> features,
        String luceneRuntime) {}
