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
        DOCS_AND_FREQS_AND_POSITIONS
    }

    /** A field of the index. */
    record Field(
            String name,
            int number,
            IndexOptions indexOptions,
            boolean hasNorms,
            boolean hasPayloads,
            boolean hasTermVectors) {}

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
            return segmentVersions.stream().min(Comparator.naturalOrder());
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
