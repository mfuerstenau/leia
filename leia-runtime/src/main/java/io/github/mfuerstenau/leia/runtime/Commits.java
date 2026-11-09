package io.github.mfuerstenau.leia.runtime;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The commits of an index directory, read again on each call without a reader, so an index opened
 * without one has them too (ADR 0045). Nothing is written: no lock, no file.
 */
public interface Commits {

    /**
     * The commits of {@code dir}, newest first.
     *
     * @throws IOException if {@code dir} can't be listed; an {@link IndexReadException} if the runtime
     *     knows why (ADR 0070)
     */
    CommitList commits(Path dir, DirectoryKind kind) throws IOException;

    /**
     * One commit of {@code dir}, with its segments and files.
     *
     * @return empty if {@code dir} has no {@code segments_N} of that generation
     * @throws IOException if the commit can't be read; an {@link IndexReadException} with Lucene's
     *     message if the runtime knows why (ADR 0070)
     */
    Optional<CommitDetails> commit(Path dir, DirectoryKind kind, long generation) throws IOException;

    /**
     * The commits of a directory.
     *
     * @param commits the readable ones, newest first
     * @param unreadable the {@code segments_N} files that could not be read
     */
    record CommitList(List<Commit> commits, List<Unreadable> unreadable) {}

    /** A {@code segments_N} file that could not be read, and why. */
    record Unreadable(String segmentsFile, String message) {}

    /**
     * A commit, as {@code segments_N} describes it.
     *
     * @param maxDoc documents in all segments, deleted ones included
     * @param delCount deleted documents in all segments
     * @param luceneVersion the Lucene version that wrote the commit, or its newest segment; {@code
     *     2.x} if that is all that is known
     */
    record Commit(
            long generation,
            String segmentsFile,
            long version,
            int segmentCount,
            int maxDoc,
            int delCount,
            String luceneVersion,
            Map<String, String> userData) {}

    /**
     * A segment of a commit.
     *
     * @param docBase the document id of its first document in the commit
     * @param delGen the generation of its deletions file; empty without deletions
     * @param luceneVersion the Lucene version that wrote it, such as {@code 3.6.2}; {@code 2.x} before 3.0
     * @param sizeInBytes the size of its files; empty if one of them can't be stat'ed
     * @param diagnostics how it came to be: source, Lucene version, OS, Java, ...
     * @param attributes the codec's attributes; before codecs, what else its {@code segments_N} entry
     *     says, such as the doc store
     * @param id its unique id in hex; empty before Lucene 5
     * @param codec its codec, such as {@code Lucene54}; empty before Lucene 4
     * @param formats the formats of its codec; empty without a codec
     * @param fieldFormats the formats of each field, by field name; empty if its field infos can't be
     *     read, or before codecs
     * @param fieldInfosError why its field infos can't be read; empty if they can
     * @param fieldInfosGen the generation of its field infos update; empty without one
     * @param docValuesGen the generation of its doc values updates; empty without one
     * @param softDelCount its soft-deleted documents, 0 before Lucene 7.4
     * @param minLuceneVersion the oldest Lucene version that wrote a part of it; empty before 7.0, or
     *     if it isn't known
     * @param indexSort how its documents are sorted, as Lucene's {@code Sort} describes it; empty if
     *     they aren't, as always before Lucene 6.2
     * @param hasBlocks whether it has document blocks, as Lucene records from 9.9 on (ADR 0069);
     *     false before
     */
    record Segment(
            String name,
            int maxDoc,
            int docBase,
            int delCount,
            @Nullable Long delGen,
            String luceneVersion,
            boolean compound,
            @Nullable Long sizeInBytes,
            Map<String, String> diagnostics,
            Map<String, String> attributes,
            @Nullable String id,
            @Nullable String codec,
            @Nullable Formats formats,
            List<FieldFormats> fieldFormats,
            @Nullable String fieldInfosError,
            @Nullable Long fieldInfosGen,
            @Nullable Long docValuesGen,
            int softDelCount,
            @Nullable String minLuceneVersion,
            @Nullable String indexSort,
            boolean hasBlocks) {

        /** A segment of Lucene 6.x to 9.8, which didn't record document blocks. */
        public Segment(
                String name,
                int maxDoc,
                int docBase,
                int delCount,
                @Nullable Long delGen,
                String luceneVersion,
                boolean compound,
                @Nullable Long sizeInBytes,
                Map<String, String> diagnostics,
                Map<String, String> attributes,
                @Nullable String id,
                @Nullable String codec,
                @Nullable Formats formats,
                List<FieldFormats> fieldFormats,
                @Nullable String fieldInfosError,
                @Nullable Long fieldInfosGen,
                @Nullable Long docValuesGen,
                int softDelCount,
                @Nullable String minLuceneVersion,
                @Nullable String indexSort) {
            this(
                    name,
                    maxDoc,
                    docBase,
                    delCount,
                    delGen,
                    luceneVersion,
                    compound,
                    sizeInBytes,
                    diagnostics,
                    attributes,
                    id,
                    codec,
                    formats,
                    fieldFormats,
                    fieldInfosError,
                    fieldInfosGen,
                    docValuesGen,
                    softDelCount,
                    minLuceneVersion,
                    indexSort,
                    false);
        }

        /** A segment of Lucene 4.x or 5.x: no soft deletes, no minimum version, no index sort. */
        public Segment(
                String name,
                int maxDoc,
                int docBase,
                int delCount,
                @Nullable Long delGen,
                String luceneVersion,
                boolean compound,
                @Nullable Long sizeInBytes,
                Map<String, String> diagnostics,
                Map<String, String> attributes,
                @Nullable String id,
                @Nullable String codec,
                @Nullable Formats formats,
                List<FieldFormats> fieldFormats,
                @Nullable String fieldInfosError,
                @Nullable Long fieldInfosGen,
                @Nullable Long docValuesGen) {
            this(
                    name,
                    maxDoc,
                    docBase,
                    delCount,
                    delGen,
                    luceneVersion,
                    compound,
                    sizeInBytes,
                    diagnostics,
                    attributes,
                    id,
                    codec,
                    formats,
                    fieldFormats,
                    fieldInfosError,
                    fieldInfosGen,
                    docValuesGen,
                    0,
                    null,
                    null);
        }

        /** A segment before codecs, as Lucene 2.x and 3.x write it. */
        public Segment(
                String name,
                int maxDoc,
                int docBase,
                int delCount,
                @Nullable Long delGen,
                String luceneVersion,
                boolean compound,
                @Nullable Long sizeInBytes,
                Map<String, String> diagnostics,
                Map<String, String> attributes) {
            this(
                    name,
                    maxDoc,
                    docBase,
                    delCount,
                    delGen,
                    luceneVersion,
                    compound,
                    sizeInBytes,
                    diagnostics,
                    attributes,
                    null,
                    null,
                    null,
                    List.of(),
                    null,
                    null,
                    null);
        }
    }

    /**
     * The formats of a segment's codec: the name of a named format (postings, doc values), otherwise
     * the simple class name.
     *
     * @param points empty before Lucene 6
     * @param knnVectors empty before Lucene 9
     */
    record Formats(
            String postings,
            String docValues,
            String storedFields,
            String termVectors,
            String norms,
            @Nullable String points,
            @Nullable String knnVectors,
            String fieldInfos,
            String segmentInfo,
            String liveDocs,
            String compound) {}

    /**
     * The formats of a field in a segment, as its field infos name them.
     *
     * @param postings empty if the field isn't indexed
     * @param docValues empty if the field has no doc values
     * @param knnVectors empty if the field has no KNN vectors
     */
    record FieldFormats(
            String field,
            @Nullable String postings,
            @Nullable String docValues,
            @Nullable String knnVectors) {}

    /**
     * A file of a commit.
     *
     * @param segment the segment it belongs to; empty for {@code segments_N}
     * @param size its size in bytes; empty if it can't be stat'ed
     */
    record CommitFile(
            String name, @Nullable String segment, @Nullable Long size) {}

    /** A commit with its segments, in commit order, and its files, in name order. */
    record CommitDetails(Commit commit, List<Segment> segments, List<CommitFile> files) {}
}
