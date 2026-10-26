package io.github.mfuerstenau.leia.runtime;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/**
 * Lucene's {@code CheckIndex} on the latest commit of an index directory, without a reader and
 * read-only: it never fixes the index, writes no file and takes no lock (ADR 0045).
 */
public interface Check {

    /**
     * Whether the check takes leia's levels (ADR 0022): 1 checks the checksums only, 2 is Lucene's
     * default check, 3 adds the slow checks such as term vectors against the postings (ADR 0046).
     */
    boolean hasLevels();

    /**
     * Whether the check takes a thread count (ADR 0093): segments are checked in parallel, which
     * Lucene 9 and 10 do.
     */
    boolean hasThreads();

    /**
     * What to check and how.
     *
     * @param level the level from 1 to 3, if {@link #hasLevels()}; empty for the default, and ignored
     *     without levels
     * @param threads the threads that check segments at once, at least 1, if {@link #hasThreads()};
     *     empty for all the processors, and ignored without threads. A runtime uses no more threads
     *     than it has segments to check
     * @param segments the names of the segments of the latest commit to check; empty for all of them.
     *     A name that isn't in the commit is skipped without a word, as Lucene does
     */
    record Options(OptionalInt level, OptionalInt threads, Optional<List<String>> segments) {

        /** Every segment at the default level, on every processor. */
        public static Options defaults() {
            return new Options(OptionalInt.empty(), OptionalInt.empty(), Optional.empty());
        }

        /** Every segment at {@code level}, on every processor. */
        public static Options atLevel(int level) {
            return new Options(OptionalInt.of(level), OptionalInt.empty(), Optional.empty());
        }
    }

    /**
     * Checks the latest commit of {@code dir}. The report goes to {@code line}, line by line.
     *
     * @param progress receives the lines written so far, after each line and while the check reads;
     *     it may throw to stop the check, which then throws what it threw
     * @return the outcome; one with an {@link CheckResult#error()} if {@code dir} can't be checked
     *     at all
     */
    CheckResult check(Path dir, DirectoryKind kind, Options options, Consumer<String> line, LongConsumer progress);

    /**
     * The outcome of checking one directory.
     *
     * @param error why it could not be checked at all; then nothing else is known
     * @param missingSegments whether the latest commit could not be read
     * @param toolOutOfDate whether the index was written by a newer Lucene than the runtime's
     * @param badSegments the segments that failed a test
     * @param lostDocs the documents in the bad segments
     * @param validCounter whether the commit's counter for segment names is above every segment's name
     * @param segments each segment of the latest commit that was checked, in commit order
     */
    record CheckResult(
            boolean clean,
            Optional<String> error,
            Optional<String> segmentsFile,
            boolean missingSegments,
            boolean toolOutOfDate,
            int segmentCount,
            int badSegments,
            int lostDocs,
            boolean validCounter,
            List<SegmentCheck> segments) {

        /** A directory that could not be checked at all. */
        public static CheckResult failed(String error) {
            return new CheckResult(
                    false, Optional.of(error), Optional.empty(), false, false, 0, 0, 0, false, List.of());
        }
    }

    /**
     * The outcome of checking one segment.
     *
     * @param codec its codec; empty before codecs, as before Lucene 4
     * @param files the files it consists of
     * @param sizeBytes the size of those files
     * @param error the test it failed and why, with the causes; empty if it passed
     */
    record SegmentCheck(
            String name,
            int maxDoc,
            Optional<String> codec,
            boolean compound,
            int files,
            long sizeBytes,
            boolean hasDeletions,
            boolean openReaderPassed,
            Optional<String> error) {}
}
