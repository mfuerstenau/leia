package io.github.mfuerstenau.leia.tui.app;

import io.github.mfuerstenau.leia.protocol.index.DirectoryImpl;
import io.github.mfuerstenau.leia.protocol.index.Feature;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * What was opened (ADR 0021).
 *
 * @param path the absolute path that was opened
 * @param noReader whether it was opened without a reader: only the commits and check screens work
 * @param directory the {@code Directory} it was opened through
 * @param indexes the index directories, in order; at least one
 * @param skipped the index directories that were found but left out
 * @param features what works on the index (ADR 0044); without a reader, the screens check {@code
 *     noReader} instead
 * @param oldLucene for an index that an old Lucene reads (ADR 0044), what the tab line says about
 *     it, such as {@code Lucene 2.x, read with 3.6.2}; empty for one that Lucene 10 reads
 */
record Opened(
        String path,
        boolean noReader,
        DirectoryImpl directory,
        List<String> indexes,
        List<String> skipped,
        Set<Feature> features,
        Optional<String> oldLucene) {

    Opened {
        indexes = List.copyOf(indexes);
        skipped = List.copyOf(skipped);
        features = Set.copyOf(features);
    }

    /** Whether the index can do {@code feature}. */
    boolean supports(Feature feature) {
        return features.contains(feature);
    }
}
