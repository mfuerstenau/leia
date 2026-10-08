package io.github.mfuerstenau.leia.protocol.index;

import java.util.List;
import java.util.OptionalInt;

/**
 * Result of {@code reopenIndex} (ADR 0019, ADR 0021): whether an index changed, and what identifies
 * the indexes now, as in {@link OpenIndexResult}. The full statistics come from {@code
 * getIndexStats}.
 *
 * @param changed whether the latest commit of an index differs from the one that was open; the
 *     indexes are then replaced. If not, nothing happened
 * @param path the absolute, normalized path that was opened
 * @param mode how it was opened, always {@link OpenMode#READER}
 * @param numDocs live documents in all indexes
 * @param maxDoc documents in all indexes, including deleted ones
 * @param indexes the index directories that are open, in order; at least one
 * @param skipped the index directories that were left out when the path was opened
 */
public record ReopenIndexResult(
        boolean changed,
        String path,
        OpenMode mode,
        OptionalInt numDocs,
        OptionalInt maxDoc,
        List<IndexEntry> indexes,
        List<SkippedIndex> skipped) {}
