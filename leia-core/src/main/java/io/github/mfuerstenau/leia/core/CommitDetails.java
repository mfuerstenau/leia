package io.github.mfuerstenau.leia.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.apache.lucene.util.Version;

/**
 * One commit whole: its segments and its files (ADR 0009).
 *
 * @param commit the commit
 * @param segments the segments in commit order, which is the order of their document ids
 * @param files every file of the commit, {@code segments_N} included, sorted by name
 */
public record CommitDetails(CommitInfo commit, List<Segment> segments, List<CommitFile> files) {

    /**
     * A segment of a commit.
     *
     * @param name the segment name, such as {@code _0}
     * @param id the segment's unique id in hex, as Lucene prints it
     * @param maxDoc documents in the segment, deleted ones included
     * @param docBase the document id of the segment's first document in this commit
     * @param delCount deleted documents
     * @param softDelCount soft-deleted documents
     * @param delGen the generation of the live docs file; empty if there were no deletions
     * @param fieldInfosGen the generation of the field infos update; empty if there is none
     * @param docValuesGen the generation of the doc values updates; empty if there are none
     * @param version the Lucene version that wrote the segment
     * @param minVersion the oldest Lucene version that wrote a part of it; empty if unknown
     * @param codec the codec name
     * @param formats the formats of the codec
     * @param fieldFormats the postings, doc values and KNN vectors format of each field, read from
     *     the segment's field infos and sorted by field name (ADR 0037); empty if they can't be read
     * @param fieldInfosError why the field infos can't be read; empty if they can
     * @param compound whether the segment is in a compound file
     * @param hasBlocks whether the segment has document blocks (parent and child documents)
     * @param indexSort the sort of the segment's documents; empty if it is not sorted
     * @param sizeInBytes the size of the segment's files; empty if one of them can't be stat'ed
     * @param diagnostics how the segment came to be, sorted by key
     * @param attributes the codec's attributes, sorted by key
     */
    public record Segment(
            String name,
            String id,
            int maxDoc,
            int docBase,
            int delCount,
            int softDelCount,
            OptionalLong delGen,
            OptionalLong fieldInfosGen,
            OptionalLong docValuesGen,
            Version version,
            Optional<Version> minVersion,
            String codec,
            CodecFormats formats,
            List<FieldFormats> fieldFormats,
            Optional<IOException> fieldInfosError,
            boolean compound,
            boolean hasBlocks,
            Optional<String> indexSort,
            OptionalLong sizeInBytes,
            Map<String, String> diagnostics,
            Map<String, String> attributes) {

        /** Why the field infos can't be read, as opening an index would say; empty if they can. */
        public Optional<String> fieldInfosMessage() {
            // The path only shows in "not an index", which reading field infos never is.
            return fieldInfosError.map(
                    error -> ReadOnlyIndex.translate(Path.of(name), error).getMessage());
        }
    }

    /**
     * The formats of a field, as its field infos name them (ADR 0037). A format is the per-field
     * attribute that Lucene's per-field formats write; without it, the codec's format.
     *
     * @param field the field name
     * @param postings the postings format; empty if the field isn't indexed
     * @param docValues the doc values format; empty if the field has no doc values
     * @param knnVectors the KNN vectors format; empty if the field has no vectors
     */
    public record FieldFormats(
            String field, Optional<String> postings, Optional<String> docValues, Optional<String> knnVectors) {}

    /**
     * The formats of a codec: the name of a named format, otherwise the simple class name.
     */
    public record CodecFormats(
            String postings,
            String docValues,
            String storedFields,
            String termVectors,
            String norms,
            String points,
            String knnVectors,
            String fieldInfos,
            String segmentInfo,
            String liveDocs,
            String compound) {}

    /**
     * A file of a commit.
     *
     * @param name the file name
     * @param segment the segment it belongs to; empty for {@code segments_N}
     * @param size the size in bytes; empty if the file can't be stat'ed
     */
    public record CommitFile(String name, Optional<String> segment, OptionalLong size) {}
}
