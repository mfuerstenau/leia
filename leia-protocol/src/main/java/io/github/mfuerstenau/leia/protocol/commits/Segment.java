package io.github.mfuerstenau.leia.protocol.commits;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * A segment of a commit.
 *
 * @param name the segment name, such as {@code _0}
 * @param id the segment's unique id in hex, as Lucene prints it
 * @param maxDoc documents in the segment, deleted ones included
 * @param docBase the document id of the segment's first document in this commit
 * @param delCount deleted documents
 * @param softDelCount soft-deleted documents
 * @param delGen the generation of the live docs file; absent if there were no deletions
 * @param fieldInfosGen the generation of the field infos update; absent if there is none
 * @param docValuesGen the generation of the doc values updates; absent if there are none
 * @param luceneVersion the Lucene version that wrote the segment
 * @param minLuceneVersion the oldest Lucene version that wrote a part of it; absent if unknown
 * @param codec the codec name, such as {@code Lucene103}
 * @param formats the formats of the codec
 * @param compound whether the segment is in a compound file
 * @param hasBlocks whether the segment has document blocks (parent and child documents)
 * @param indexSort the sort of the segment's documents; absent if it is not sorted
 * @param sizeInBytes the size of the segment's files; absent if one of them can't be stat'ed
 * @param diagnostics how the segment came to be: source, Lucene version, OS, Java, ...
 * @param attributes the codec's attributes
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
        String luceneVersion,
        Optional<String> minLuceneVersion,
        String codec,
        CodecFormats formats,
        boolean compound,
        boolean hasBlocks,
        Optional<String> indexSort,
        OptionalLong sizeInBytes,
        Map<String, String> diagnostics,
        Map<String, String> attributes) {}
