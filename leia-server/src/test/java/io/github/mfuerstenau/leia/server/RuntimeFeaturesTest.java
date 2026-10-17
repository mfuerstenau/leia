package io.github.mfuerstenau.leia.server;

import static io.github.mfuerstenau.leia.server.RealBackend.testFile;
import static io.github.mfuerstenau.leia.server.RealBackend.testIndex;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.index.Feature;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexParams;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexResult;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The feature table of {@code docs/runtimes.md} against what {@code openIndex} reports: one row per
 * {@link Feature}, in its order, and per runtime the features of its newest test index, which holds
 * doc values, points and vectors where the runtime has them (ADR 0046 §8, ADR 0048 §6, ADR 0049 §8).
 */
class RuntimeFeaturesTest {

    /** The newest test index of each runtime, by the runtime's column in the table. */
    private static final Map<String, String> INDEXES = Map.of(
            "3.6.2", "lucene3",
            "5.5.5", "lucene5",
            "7.7.3", "lucene7",
            "9.12.3", "lucene8to9",
            "10.5.2", "lucene9");

    /** The table: its features in their order, and each column's features. */
    private record Table(List<Feature> rows, Map<String, Set<Feature>> columns) {}

    private static Table table() throws IOException {
        List<String> lines = Files.readAllLines(testFile("leia.test.doc.runtimes"));
        int header = 0;
        while (!lines.get(header).startsWith("| Feature |")) {
            header++;
        }
        List<String> versions =
                cells(lines.get(header)).subList(1, cells(lines.get(header)).size());
        Map<String, Set<Feature>> columns = new LinkedHashMap<>();
        versions.forEach(version -> columns.put(version, EnumSet.noneOf(Feature.class)));
        List<Feature> rows = new ArrayList<>();
        // Below the header and its separator, until the table ends.
        for (int i = header + 2; i < lines.size() && lines.get(i).startsWith("|"); i++) {
            List<String> cells = cells(lines.get(i));
            Feature feature = Feature.valueOf(cells.getFirst().replace("`", ""));
            rows.add(feature);
            for (int column = 0; column < versions.size(); column++) {
                if (cells.get(column + 1).startsWith("✓")) {
                    columns.get(versions.get(column)).add(feature);
                }
            }
        }
        return new Table(rows, columns);
    }

    private static List<String> cells(String line) {
        String inner = line.strip().substring(1, line.strip().length() - 1);
        return Arrays.stream(inner.split("\\|")).map(String::strip).toList();
    }

    static Stream<Arguments> runtimes() {
        return INDEXES.entrySet().stream().map(entry -> Arguments.of(entry.getKey(), entry.getValue()));
    }

    @Test
    void hasARowForEachFeatureInOrder() throws IOException {
        assertThat(table().rows()).containsExactly(Feature.values());
    }

    @Test
    void hasAColumnForEachRuntime() throws IOException {
        assertThat(table().columns().keySet()).containsExactlyInAnyOrderElementsOf(INDEXES.keySet());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("runtimes")
    void listsTheFeaturesThatTheRuntimeReports(String runtime, String index) throws Exception {
        Set<Feature> documented = table().columns().get(runtime);
        assertThat(documented).as("the column %s", runtime).isNotNull();
        try (RealBackend backend = RealBackend.start()) {
            OpenIndexResult opened = backend.call(
                    Methods.OPEN_INDEX, new OpenIndexParams(testIndex(index).toString()));
            assertThat(opened.luceneRuntime()).isEqualTo(runtime);
            assertThat(opened.features())
                    .as("the features of %s in docs/runtimes.md", runtime)
                    .containsExactlyInAnyOrderElementsOf(documented);
        }
    }
}
