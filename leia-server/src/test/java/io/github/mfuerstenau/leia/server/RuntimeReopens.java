package io.github.mfuerstenau.leia.server;

import static io.github.mfuerstenau.leia.server.RealBackend.classic;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.filter.FilterResult;
import io.github.mfuerstenau.leia.protocol.filter.StartFilterParams;
import io.github.mfuerstenau.leia.protocol.filter.StepFilterParams;
import io.github.mfuerstenau.leia.protocol.index.Feature;
import io.github.mfuerstenau.leia.protocol.index.GetIndexStatsParams;
import io.github.mfuerstenau.leia.protocol.index.IndexEntry;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexParams;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexResult;
import io.github.mfuerstenau.leia.protocol.index.ReopenIndexParams;
import io.github.mfuerstenau.leia.protocol.index.ReopenIndexResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Reopen on an index of the runtimes' writers (ADR 0060), each with several commits: a copy without
 * the newest commit opens, and the commit is put back.
 */
final class RuntimeReopens {

    private RuntimeReopens() {}

    /**
     * Opens a copy of {@code index} without its newest commit {@code newest} in {@code copy}, reopens
     * it unchanged, then with the commit back: the new commit is open and the filter is forgotten. A
     * directory that is gone fails with {@code INDEX_NOT_FOUND}.
     *
     * @return the opened copy and the changed reopen's result, for the caller's own checks
     */
    static Reopened reopensAChangedIndex(RealBackend backend, Path index, Path copy, String newest) throws Exception {
        Files.createDirectories(copy);
        try (Stream<Path> files = Files.list(index)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (!name.equals(newest) && !name.equals("segments.gen")) {
                    Files.copy(file, copy.resolve(name));
                }
            }
        }
        OpenIndexResult opened = backend.call(Methods.OPEN_INDEX, new OpenIndexParams(copy.toString()));
        assertThat(opened.features()).contains(Feature.REOPEN);
        String older = segmentsFile(opened.indexes());
        assertThat(older).isNotEqualTo(newest);
        FilterResult filter =
                backend.filter(new StartFilterParams(Optional.of(classic("alpha")), Optional.empty(), false));

        ReopenIndexResult unchanged = backend.call(Methods.REOPEN_INDEX, new ReopenIndexParams());
        assertThat(unchanged.changed()).isFalse();
        assertThat(unchanged.path()).isEqualTo(opened.path());
        assertThat(unchanged.maxDoc()).isEqualTo(opened.maxDoc());
        assertThat(segmentsFile(unchanged.indexes())).isEqualTo(older);
        assertThat(unchanged.features()).isEqualTo(opened.features());
        // Nothing was replaced, so the filter is kept.
        backend.call(Methods.STEP_FILTER, new StepFilterParams(filter.filterId(), 0, true));

        Files.copy(index.resolve(newest), copy.resolve(newest));
        ReopenIndexResult changed = backend.call(Methods.REOPEN_INDEX, new ReopenIndexParams());
        assertThat(changed.changed()).isTrue();
        assertThat(changed.path()).isEqualTo(opened.path());
        assertThat(segmentsFile(changed.indexes())).isEqualTo(newest);
        assertThat(changed.maxDoc().orElseThrow()).isGreaterThan(opened.maxDoc().orElseThrow());
        assertThat(changed.features()).contains(Feature.REOPEN);
        assertThat(backend.call(Methods.GET_INDEX_STATS, new GetIndexStatsParams())
                        .indexes()
                        .getFirst()
                        .segmentsFile())
                .isEqualTo(newest);
        // The filter held document ids of the old index (ADR 0031 §5).
        backend.fails(
                Methods.STEP_FILTER, new StepFilterParams(filter.filterId(), 0, true), ErrorCode.FILTER_NOT_FOUND);
        // Unchanged again.
        assertThat(backend.call(Methods.REOPEN_INDEX, new ReopenIndexParams()).changed())
                .isFalse();

        Files.move(copy, copy.resolveSibling(copy.getFileName() + "-moved"));
        assertThat(backend.fails(Methods.REOPEN_INDEX, new ReopenIndexParams(), ErrorCode.INDEX_NOT_FOUND)
                        .getMessage())
                .isEqualTo("No such directory: " + copy);
        return new Reopened(opened, changed);
    }

    /** What {@link #reopensAChangedIndex} opened, and its changed reopen. */
    record Reopened(OpenIndexResult opened, ReopenIndexResult changed) {}

    private static String segmentsFile(List<IndexEntry> indexes) {
        return indexes.getFirst().reader().orElseThrow().segmentsFile();
    }
}
