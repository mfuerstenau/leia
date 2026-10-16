package io.github.mfuerstenau.leia.server.runtime;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.runtime.LuceneRuntime;
import io.github.mfuerstenau.leia.runtime.RuntimeIndex;
import io.github.mfuerstenau.leia.server.RpcException;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The indexes of a path that a runtime opened (ADR 0044, ADR 0045), and the runtime.
 *
 * @param path the absolute, normalized path that was opened
 * @param skipped the index directories found below {@code path} but left out, in the order they were
 *     found: those the runtime failed to open and those it doesn't read
 */
public record OpenRuntime(Path path, LuceneRuntime runtime, RuntimeIndex index, List<RuntimeIndex.Skipped> skipped)
        implements Closeable {

    public OpenRuntime {
        skipped = List.copyOf(skipped);
    }

    /**
     * What a method that the runtime lacks fails with, such as "Not supported for an index written by
     * Lucene 2.x or 3.x (read with Lucene 3.6.2)".
     */
    public RpcException notSupported() {
        return notSupported(runtime);
    }

    /** As {@link #notSupported()}, for an index that {@code runtime} reads. */
    public static RpcException notSupported(LuceneRuntime runtime) {
        return new RpcException(
                ErrorCode.NOT_SUPPORTED,
                "Not supported for an index written by Lucene " + majors(runtime) + " (read with Lucene "
                        + runtime.luceneVersion() + ")");
    }

    /** The majors the runtime reads, such as "2.x or 3.x". */
    String majors() {
        return majors(runtime);
    }

    /** The majors {@code runtime} reads, such as "2.x or 3.x". */
    static String majors(LuceneRuntime runtime) {
        List<String> majors = runtime.indexMajors().stream()
                .sorted()
                .map(major -> major + ".x")
                .toList();
        if (majors.size() < 2) {
            return String.join("", majors);
        }
        return majors.subList(0, majors.size() - 1).stream().collect(Collectors.joining(", ")) + " or "
                + majors.getLast();
    }

    @Override
    public void close() throws IOException {
        index.close();
    }
}
