package io.github.mfuerstenau.leia.core;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.lucene.util.Version;

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
     */
    public record CommitStats(
            IndexSummary summary,
            long generation,
            long version,
            int segmentCount,
            Version commitLuceneVersion,
            Map<String, String> userData) {}
}
