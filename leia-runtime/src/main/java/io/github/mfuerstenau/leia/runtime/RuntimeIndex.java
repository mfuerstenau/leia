package io.github.mfuerstenau.leia.runtime;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

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

    /** The classic query parser, term queries and search. */
    Optional<Search> search();

    /** The BKD trees of point fields (ADR 0048). */
    Optional<Points> points();

    /** KNN vectors and the queries for the nearest ones (ADR 0049). */
    Optional<Knn> knn();

    /** The HNSW graphs of KNN vector fields (ADR 0085). */
    Optional<KnnGraph> knnGraph();

    /** The rebuild of a document's terms and points (ADR 0059). */
    Optional<Rebuild> rebuild();

    /** The highlighter of query matches in stored text (ADR 0063). */
    Optional<Highlight> highlight();

    /** Search and explain with a similarity of choice, and search in an order of choice (ADR 0064). */
    Optional<Scoring> scoring();

    /** Queries over doc values (ADR 0066). */
    Optional<DocValuesSearch> docValuesSearch();

    /** The standard query parser, with point fields (ADR 0066). */
    Optional<StandardSearch> standardSearch();

    /** More-Like-This queries, by a document or a text (ADR 0066). */
    Optional<MoreLikeThisSearch> moreLikeThisSearch();

    /**
     * Opens the latest commit of each directory again if it differs from the opened one (ADR 0060).
     * The new index shares the directories, the readers of the directories that didn't change, and the
     * readers of the segments in both commits. It owns the directories from then on: closing this index
     * then closes only its readers. This index stays usable until it is closed. Neither the directories
     * nor the skipped ones change.
     *
     * @return the new index; empty if every latest commit is the opened one
     * @throws IOException if a latest commit can't be read; this index is unaffected. An {@link
     *     IndexReadException} if the runtime knows why, worded as by {@link LuceneRuntime#open} (ADR 0070)
     * @throws IllegalStateException if this index was reopened before
     */
    Optional<RuntimeIndex> reopen() throws IOException;

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
     * @param pointDimensionCount dimensions of its points; 0 without points, as before Lucene 6
     * @param pointNumBytes bytes of each dimension of its points; 0 without points
     * @param terms its postings statistics; empty if no segment has postings for it, or the runtime's
     *     Lucene doesn't record them
     * @param vectors its KNN vectors; empty without them, as before Lucene 9
     */
    record Field(
            String name,
            int number,
            IndexOptions indexOptions,
            boolean hasNorms,
            boolean hasPayloads,
            boolean hasTermVectors,
            DocValuesType docValuesType,
            int pointDimensionCount,
            int pointNumBytes,
            Optional<TermStats> terms,
            Optional<VectorFormat> vectors) {

        /** A field without KNN vectors, as before Lucene 9. */
        public Field(
                String name,
                int number,
                IndexOptions indexOptions,
                boolean hasNorms,
                boolean hasPayloads,
                boolean hasTermVectors,
                DocValuesType docValuesType,
                int pointDimensionCount,
                int pointNumBytes,
                Optional<TermStats> terms) {
            this(
                    name,
                    number,
                    indexOptions,
                    hasNorms,
                    hasPayloads,
                    hasTermVectors,
                    docValuesType,
                    pointDimensionCount,
                    pointNumBytes,
                    terms,
                    Optional.empty());
        }
    }

    /** How a field's KNN vectors are encoded, as Lucene's {@code VectorEncoding}. */
    enum VectorEncoding {
        BYTE,
        FLOAT32
    }

    /** How a field's KNN vectors are compared, as Lucene's {@code VectorSimilarityFunction}. */
    enum VectorSimilarity {
        EUCLIDEAN,
        DOT_PRODUCT,
        COSINE,
        MAXIMUM_INNER_PRODUCT
    }

    /** The KNN vectors of a field: each has {@code dimension} components of {@code encoding}. */
    record VectorFormat(int dimension, VectorEncoding encoding, VectorSimilarity similarity) {}

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
     * @param directoryClass the simple class name of the first directory's Lucene {@code Directory},
     *     such as {@code MMapDirectory} (ADR 0069)
     */
    record Stats(int numDocs, int maxDoc, List<IndexStats> indexes, List<Field> fields, String directoryClass) {}

    /**
     * A segment of the open reader (ADR 0051).
     *
     * @param name the segment's name, such as {@code _0}; {@code ?} if the reader doesn't say
     * @param docBase the document id of the segment's first document within its directory
     * @param maxDoc the documents of the segment, deleted ones included
     * @param numDocs the live documents of the segment
     */
    record Segment(String name, int docBase, int maxDoc, int numDocs) {}

    /**
     * The opened commit of one directory.
     *
     * @param docBase the document id of its first document in the opened index
     * @param segmentVersions the Lucene version that wrote each segment, such as {@code 3.6.2}; {@code
     *     2.x} for a segment written before 3.0, which didn't record it
     * @param luceneVersion the Lucene version that wrote the commit; empty if it isn't recorded, as
     *     before 5.3
     * @param createdVersionMajor the major version of the Lucene that created the index; empty if it
     *     isn't recorded, as before 7.0 (though 7.x reports 6 for a commit of 6.x)
     * @param segments the open reader's segments of the directory, in document order (ADR 0051)
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
            OptionalInt createdVersionMajor,
            Map<String, String> userData,
            List<Segment> segments) {

        /**
         * The major version of the Lucene that created the index, or else of its oldest segment. It
         * says how the norms are encoded: a segment that a newer Lucene rewrote keeps them as the
         * index's creator wrote them.
         */
        public int createdMajor() {
            return createdVersionMajor.orElseGet(this::oldestMajor);
        }

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

    /** A KNN vector (ADR 0049). The array is the caller's to keep, and is not copied. */
    sealed interface Vector {

        /** How many components it has. */
        int dimension();

        record Floats(float[] values) implements Vector {
            @Override
            public int dimension() {
                return values.length;
            }
        }

        /** Signed bytes. */
        record Bytes(byte[] values) implements Vector {
            @Override
            public int dimension() {
                return values.length;
            }
        }
    }
}
