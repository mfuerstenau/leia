package io.github.mfuerstenau.leia.core;

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
            boolean compound,
            boolean hasBlocks,
            Optional<String> indexSort,
            OptionalLong sizeInBytes,
            Map<String, String> diagnostics,
            Map<String, String> attributes) {}

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
