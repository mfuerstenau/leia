package io.github.mfuerstenau.leia.tui.app;

import io.github.mfuerstenau.leia.protocol.index.DirectoryImpl;
import java.util.List;

/**
 * What was opened (ADR 0021).
 *
 * @param path the absolute path that was opened
 * @param noReader whether it was opened without a reader: only the commits and check screens work
 * @param directory the {@code Directory} it was opened through
 * @param indexes the index directories, in order; at least one
 * @param skipped the index directories that were found but left out
 */
record Opened(String path, boolean noReader, DirectoryImpl directory, List<String> indexes, List<String> skipped) {

    Opened {
        indexes = List.copyOf(indexes);
        skipped = List.copyOf(skipped);
    }
}
