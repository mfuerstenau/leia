package io.github.mfuerstenau.leia.runtime;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * The commits of an index directory, read again on each call without a reader, so an index opened
 * without one has them too (ADR 0045). Nothing is written: no lock, no file.
 */
public interface Commits {

    /**
     * The commits of {@code dir}, newest first.
     *
     * @throws IOException if {@code dir} can't be listed
     */
    CommitList commits(Path dir, DirectoryKind kind) throws IOException;

    /**
     * One commit of {@code dir}, with its segments and files.
     *
     * @return empty if {@code dir} has no {@code segments_N} of that generation
     * @throws IOException if the commit can't be read
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
     * @param attributes what else its {@code segments_N} entry says, such as the doc store
     */
    record Segment(
            String name,
            int maxDoc,
            int docBase,
            int delCount,
            OptionalLong delGen,
            String luceneVersion,
            boolean compound,
            OptionalLong sizeInBytes,
            Map<String, String> diagnostics,
            Map<String, String> attributes) {}

    /**
     * A file of a commit.
     *
     * @param segment the segment it belongs to; empty for {@code segments_N}
     * @param size its size in bytes; empty if it can't be stat'ed
     */
    record CommitFile(String name, Optional<String> segment, OptionalLong size) {}

    /** A commit with its segments, in commit order, and its files, in name order. */
    record CommitDetails(Commit commit, List<Segment> segments, List<CommitFile> files) {}
}
