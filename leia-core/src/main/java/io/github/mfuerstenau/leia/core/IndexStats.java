package io.github.mfuerstenau.leia.core;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.lucene.util.Version;
import org.jspecify.annotations.Nullable;

/**
 * Statistics of an opened path that are cheap to compute: everything comes from the commits and from
 * metadata that the reader loaded on open. Nothing here iterates over terms or documents. The path
 * may combine several index directories (ADR 0021); the counts and fields cover all of them.
 *
 * @param path the absolute, normalized path that was opened
 * @param directoryImpl the class of the Lucene {@code Directory}, such as {@code MMapDirectory}
 * @param segmentCount segments in all opened commits
 * @param numDocs live documents
 * @param maxDoc documents including deleted ones
 * @param indexes each index directory with its opened commit, in order
 * @param fields every field, merged over the index directories, ordered by field number
 */
public record IndexStats(
        Path path,
        String directoryImpl,
        int segmentCount,
        int numDocs,
        int maxDoc,
        List<CommitStats> indexes,
        List<FieldStats> fields) {

    /** Documents that are deleted but not yet merged away. */
    public int deletedDocs() {
        return maxDoc - numDocs;
    }

    /** The field called {@code name}, if the index has one. */
    public Optional<FieldStats> field(String name) {
        return fields.stream().filter(field -> field.name().equals(name)).findFirst();
    }

    /**
     * An index directory and its opened commit.
     *
     * @param summary what identifies the index directory
     * @param generation the generation of the opened commit, the {@code N} in {@code segments_N}
     * @param version the version of the opened commit, which changes with every change to the index
     * @param segmentCount segments in the commit
     * @param commitLuceneVersion the Lucene version that wrote the commit
     * @param userData the user data of the commit, sorted by key
     * @param segments the open reader's segments of the index directory, in document order (ADR
     *     0051); they come from the reader, not from the commit
     */
    public record CommitStats(
            IndexSummary summary,
            long generation,
            long version,
            int segmentCount,
            Version commitLuceneVersion,
            Map<String, String> userData,
            List<SegmentStats> segments) {}

    /**
     * A segment of the open reader.
     *
     * @param name the segment's name, such as {@code _0}; {@code ?} if the reader doesn't say
     * @param docBase the document id of the segment's first document in the opened path
     * @param maxDoc the documents of the segment, deleted ones included
     * @param numDocs the live documents of the segment
     * @param id the segment's unique id in hex; null if the reader doesn't say
     * @param files the segment's own files, sorted, without those of its deletions; empty if the
     *     reader doesn't say
     */
    public record SegmentStats(
            String name,
            int docBase,
            int maxDoc,
            int numDocs,
            @Nullable String id,
            List<String> files) {

        /** A segment without an id or files. */
        public SegmentStats(String name, int docBase, int maxDoc, int numDocs) {
            this(name, docBase, maxDoc, numDocs, null, List.of());
        }
    }
}
