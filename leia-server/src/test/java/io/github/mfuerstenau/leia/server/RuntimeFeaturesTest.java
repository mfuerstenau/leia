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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
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
            "10.5.2", "demo");

    /** The table: its features in their order, and each column's features. */
    private record Table(List<Feature> rows, Map<String, Set<Feature>> columns) {}

    private static Table table() throws IOException {
        List<String> lines = Files.readAllLines(testFile("leia.test.doc.runtimes"));
        int header = header(lines, "| Feature |");
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

    /** The line of the table whose header starts with {@code start}. */
    private static int header(List<String> lines, String start) {
        int header = 0;
        while (!lines.get(header).startsWith(start)) {
            header++;
        }
        return header;
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

    /**
     * The condensed table of the user README in the distribution ({@code leia-tui/src/dist/README.md},
     * ADR 0083) against the feature table: a row per group of features, in user terms, each cell ✓
     * if the runtime has all of them and – if it has none.
     */
    @Nested
    class DistReadme {

        /** Each row of the README's table with the features it stands for. */
        private static final Map<String, Set<Feature>> ROWS = rows();

        private static Map<String, Set<Feature>> rows() {
            Map<String, Set<Feature>> rows = new LinkedHashMap<>();
            rows.put(
                    "Overview, documents, stored values and fields, term vectors",
                    EnumSet.of(
                            Feature.INDEX_STATS,
                            Feature.TOP_TERMS,
                            Feature.DOCUMENTS,
                            Feature.STORED_VALUES,
                            Feature.STORED_FIELDS,
                            Feature.TERM_VECTORS,
                            Feature.RECONSTRUCT,
                            Feature.REOPEN));
            rows.put(
                    "Terms, postings and positions, export terms",
                    EnumSet.of(Feature.TERMS, Feature.POSTINGS, Feature.POSITIONS, Feature.EXPORT_TERMS));
            rows.put("Commits and check index", EnumSet.of(Feature.COMMITS, Feature.CHECK_INDEX));
            rows.put("Check levels", EnumSet.of(Feature.CHECK_LEVELS));
            rows.put("Check threads", EnumSet.of(Feature.CHECK_THREADS));
            rows.put("Points", EnumSet.of(Feature.POINTS));
            rows.put("Doc values", EnumSet.of(Feature.DOC_VALUES));
            rows.put("KNN vectors, KNN search and HNSW graphs", EnumSet.of(Feature.KNN_VECTORS, Feature.KNN_GRAPH));
            rows.put(
                    "Classic and term queries, explain, regex over stored values, auto stop words",
                    EnumSet.of(
                            Feature.CLASSIC_SEARCH,
                            Feature.TERM_SEARCH,
                            Feature.EXPLAIN,
                            Feature.STORED_SCAN,
                            Feature.AUTO_STOP_WORDS));
            rows.put("Filter documents by a query", EnumSet.of(Feature.FILTER));
            rows.put("Standard query parser", EnumSet.of(Feature.STANDARD_SEARCH));
            rows.put("More-Like-This", EnumSet.of(Feature.MORE_LIKE_THIS));
            rows.put("Doc values queries", EnumSet.of(Feature.DOC_VALUES_SEARCH));
            rows.put("Similarity and sort", EnumSet.of(Feature.SCORING));
            rows.put("Highlighting", EnumSet.of(Feature.HIGHLIGHT));
            return rows;
        }

        /** The README's table: its header and its rows, each a label with its cells. */
        private record Readme(List<String> columns, Map<String, List<String>> rows) {}

        private static Readme readme() throws IOException {
            List<String> lines = Files.readAllLines(testFile("leia.test.doc.distReadme"));
            int header = header(lines, "| Can |");
            List<String> columns =
                    cells(lines.get(header)).subList(1, cells(lines.get(header)).size());
            Map<String, List<String>> rows = new LinkedHashMap<>();
            for (int i = header + 2; i < lines.size() && lines.get(i).startsWith("|"); i++) {
                List<String> cells = cells(lines.get(i));
                rows.put(cells.getFirst(), cells.subList(1, cells.size()));
            }
            return new Readme(columns, rows);
        }

        @Test
        void hasTheRowsThatTheTestKnows() throws IOException {
            assertThat(readme().rows().keySet()).containsExactlyElementsOf(ROWS.keySet());
        }

        @Test
        void coversEachFeatureOnce() {
            List<Feature> covered = ROWS.values().stream().flatMap(Set::stream).toList();
            assertThat(covered).doesNotHaveDuplicates().containsExactlyInAnyOrder(Feature.values());
        }

        @Test
        void hasTheColumnsOfTheFeatureTable() throws IOException {
            assertThat(readme().columns())
                    .containsExactlyElementsOf(table().columns().keySet());
        }

        @Test
        void marksWhatEachRuntimeHas() throws IOException {
            Readme readme = readme();
            Table table = table();
            for (Map.Entry<String, List<String>> row : readme.rows().entrySet()) {
                Set<Feature> features = ROWS.get(row.getKey());
                for (int column = 0; column < readme.columns().size(); column++) {
                    String runtime = readme.columns().get(column);
                    Set<Feature> has = new HashSet<>(features);
                    has.retainAll(table.columns().get(runtime));
                    String cell = row.getValue().get(column);
                    String where = "the row \"%s\" of %s".formatted(row.getKey(), runtime);
                    assertThat(has.isEmpty() || has.equals(features))
                            .as(
                                    "%s mixes features the runtime has and lacks (%s of %s): split the row",
                                    where, has, features)
                            .isTrue();
                    if (has.isEmpty()) {
                        assertThat(cell).as(where).isEqualTo("–");
                    } else {
                        assertThat(cell).as(where).startsWith("✓");
                    }
                }
            }
        }
    }
}
