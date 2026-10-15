package io.github.mfuerstenau.leia.server.runtime;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.runtime.LuceneRuntime;
import io.github.mfuerstenau.leia.runtime.RuntimeIndex;
import io.github.mfuerstenau.leia.server.RpcException;
import java.util.List;
import java.util.stream.Collectors;

/** An index that a runtime opened (ADR 0044), and the runtime. */
public record OpenRuntime(LuceneRuntime runtime, RuntimeIndex index) {

    /**
     * What a method that the runtime lacks fails with, such as "Not supported for an index written by
     * Lucene 2.x or 3.x (read with Lucene 3.6.2)".
     */
    public RpcException notSupported() {
        return new RpcException(
                ErrorCode.NOT_SUPPORTED,
                "Not supported for an index written by Lucene " + majors() + " (read with Lucene "
                        + runtime.luceneVersion() + ")");
    }

    /** The majors the runtime reads, such as "2.x or 3.x". */
    String majors() {
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
}
