package io.github.mfuerstenau.leia.protocol.index;

import java.util.List;
import java.util.OptionalInt;

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
 */
public record OpenIndexResult(
        String path,
        OpenMode mode,
        OptionalInt numDocs,
        OptionalInt maxDoc,
        List<IndexEntry> indexes,
        List<SkippedIndex> skipped) {}
