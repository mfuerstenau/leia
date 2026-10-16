package io.github.mfuerstenau.leia.runtime;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * An index opened by a {@link LuceneRuntime}. What it can read beyond the statistics is in groups: a
 * group it lacks is empty, and leia refuses its methods with {@code NOT_SUPPORTED}.
 */
public interface RuntimeIndex extends Closeable {

    /** The directories that opened, in the order of {@link Stats#indexes()}. */
    List<Path> directories();

    /** The directories given to {@link LuceneRuntime#open} that failed to open, in the order given. */
    List<Skipped> skipped();

    /** The directory it was opened through. */
    DirectoryKind directory();

    int maxDoc();

    /** The fields of all its directories, by field number. */
    List<Field> fields();

    /** The opened commit of each directory, and the fields. */
    Stats stats() throws IOException;

    /** The documents, their stored values and term vectors. */
    Optional<Documents> documents();

    /** The terms of a field and their postings. */
    Optional<Terms> terms();

    /** The classic query parser and search. */
    Optional<Search> search();

    /** How a field is indexed. */
    enum IndexOptions {
        NONE,
        DOCS,
        DOCS_AND_FREQS,
        DOCS_AND_FREQS_AND_POSITIONS,
        DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS
    }

    /** The doc values of a field, as Lucene's {@code DocValuesType}. */
    enum DocValuesType {
        NONE,
        NUMERIC,
        BINARY,
        SORTED,
        SORTED_NUMERIC,
        SORTED_SET
    }

    /**
     * A field of the index.
     *
     * @param terms its postings statistics; empty if no segment has postings for it, or the runtime's
     *     Lucene doesn't record them
     */
    record Field(
            String name,
            int number,
            IndexOptions indexOptions,
            boolean hasNorms,
            boolean hasPayloads,
            boolean hasTermVectors,
            DocValuesType docValuesType,
            Optional<TermStats> terms) {}

    /**
     * The postings statistics of a field, summed over all segments, deleted documents included.
     *
     * @param sumTotalTermFreq equal to {@code sumDocFreq} if frequencies are not indexed
     */
    record TermStats(int docCount, long sumDocFreq, long sumTotalTermFreq) {}

    /**
     * The opened commits and the fields.
     *
     * @param numDocs live documents of all directories
     * @param maxDoc documents of all directories, deleted ones included
     * @param indexes each directory that opened, in document order
     */
    record Stats(int numDocs, int maxDoc, List<IndexStats> indexes, List<Field> fields) {}

    /**
     * The opened commit of one directory.
     *
     * @param docBase the document id of its first document in the opened index
     * @param segmentVersions the Lucene version that wrote each segment, such as {@code 3.6.2}; {@code
     *     2.x} for a segment written before 3.0, which didn't record it
     * @param luceneVersion the Lucene version that wrote the commit; empty if it isn't recorded, as
     *     before 5.3
     */
    record IndexStats(
            Path path,
            int docBase,
            String segmentsFile,
            long generation,
            long version,
            int numDocs,
            int maxDoc,
            List<String> segmentVersions,
            Optional<String> luceneVersion,
            Map<String, String> userData) {

        /** The major version of the oldest segment, which stands in for the version that created the index. */
        public int oldestMajor() {
            return segmentVersions.stream()
                    .mapToInt(version -> Integer.parseInt(version.substring(0, version.indexOf('.'))))
                    .min()
                    .orElse(3);
        }

        /** The oldest segment version, such as {@code 2.x}; empty without segments. */
        public Optional<String> oldestSegmentVersion() {
            return segmentVersions.stream().min(VERSION_ORDER);
        }

        /** The version that wrote the commit, or else its newest segment; {@code fallback} without segments. */
        public String commitVersion(String fallback) {
            return luceneVersion
                    .or(() -> segmentVersions.stream().max(VERSION_ORDER))
                    .orElse(fallback);
        }
    }

    /**
     * Orders Lucene versions such as {@code 2.x}, {@code 4.9.0} and {@code 4.10.4} by their numbers, a
     * part that is not a number (such as {@code x}) before every number.
     */
    Comparator<String> VERSION_ORDER = (a, b) -> {
        String[] left = a.split("\\.");
        String[] right = b.split("\\.");
        for (int i = 0; i < Math.min(left.length, right.length); i++) {
            int order = Long.compare(versionPart(left[i]), versionPart(right[i]));
            if (order != 0) {
                return order;
            }
        }
        return Integer.compare(left.length, right.length);
    };

    private static long versionPart(String part) {
        try {
            return Long.parseLong(part);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** A directory that failed to open, and why. */
    record Skipped(Path path, String message) {}

    /** A stored value: text, bytes or a number. */
    sealed interface StoredValue {
        record Text(String text) implements StoredValue {}

        record Binary(byte[] bytes) implements StoredValue {}

        /** An {@link Integer}, {@link Long}, {@link Float} or {@link Double}. */
        record Numeric(Number number) implements StoredValue {}
    }
}
