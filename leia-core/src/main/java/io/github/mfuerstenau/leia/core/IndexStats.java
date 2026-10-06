package io.github.mfuerstenau.leia.core;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.lucene.util.Version;

/**
 * Statistics of an opened index that are cheap to compute: everything comes from the commit and
 * from metadata that the reader loaded on open. Nothing here iterates over terms or documents.
 *
 * @param summary what identifies the index
 * @param directoryImpl the class of the Lucene {@code Directory}, such as {@code MMapDirectory}
 * @param generation the generation of the opened commit, the {@code N} in {@code segments_N}
 * @param version the version of the opened commit, which changes with every change to the index
 * @param segmentCount segments in the commit
 * @param commitLuceneVersion the Lucene version that wrote the commit
 * @param userData the user data of the commit, sorted by key
 * @param fields every field in the index, ordered by field number
 */
public record IndexStats(
        IndexSummary summary,
        String directoryImpl,
        long generation,
        long version,
        int segmentCount,
        Version commitLuceneVersion,
        Map<String, String> userData,
        List<FieldStats> fields) {

    /** Documents that are deleted but not yet merged away. */
    public int deletedDocs() {
        return summary.maxDoc() - summary.numDocs();
    }

    /** The field called {@code name}, if the index has one. */
    public Optional<FieldStats> field(String name) {
        return fields.stream().filter(field -> field.name().equals(name)).findFirst();
    }
}
