package io.github.mfuerstenau.leia.protocol.jars;

import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A runtime that leia knows, with its folder in {@code runtimes/} (ADR 0072, ADR 0073).
 *
 * @param folder its folder in {@code runtimes/}, such as {@code lucene3}
 * @param indexMajors the major versions of Lucene whose indexes it reads, oldest first
 * @param state whether it is loaded, and if not, why
 * @param luceneVersion the version of Lucene of the loaded runtime that reads them, such as {@code
 *     3.6.2}, or empty if none is loaded
 * @param problem for {@link RuntimeState#INCOMPLETE}, the jar that is missing from the folder; for
 *     {@link RuntimeState#REFUSED}, why the runtime is refused; empty otherwise
 * @param path the folder the loaded runtime came from, among the runtimes folders it is looked up in
 *     one runtime at a time (ADR 0122); empty if it isn't loaded, or loaded through the jars
 */
public record RuntimeFolder(
        String folder,
        List<Integer> indexMajors,
        RuntimeState state,
        Optional<String> luceneVersion,
        Optional<String> problem,
        Optional<String> path) {

    /**
     * A runtime that came from no known folder: not loaded, or loaded through the jars. A {@code null}
     * {@code luceneVersion} or {@code problem} means empty.
     */
    public RuntimeFolder(
            String folder,
            List<Integer> indexMajors,
            RuntimeState state,
            @Nullable String luceneVersion,
            @Nullable String problem) {
        this(
                folder,
                indexMajors,
                state,
                Optional.ofNullable(luceneVersion),
                Optional.ofNullable(problem),
                Optional.empty());
    }
}
