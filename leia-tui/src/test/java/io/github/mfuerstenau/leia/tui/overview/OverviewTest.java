package io.github.mfuerstenau.leia.tui.overview;

import static io.github.mfuerstenau.leia.tui.TestScreens.key;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tamboui.tui.event.KeyCode;
import io.github.mfuerstenau.leia.protocol.document.BytesValue;
import io.github.mfuerstenau.leia.protocol.index.DocValuesType;
import io.github.mfuerstenau.leia.protocol.index.GetIndexStatsResult;
import io.github.mfuerstenau.leia.protocol.index.IndexCommitStats;
import io.github.mfuerstenau.leia.protocol.index.IndexField;
import io.github.mfuerstenau.leia.protocol.index.IndexOptions;
import io.github.mfuerstenau.leia.protocol.index.ReaderSegment;
import io.github.mfuerstenau.leia.protocol.index.SkippedIndex;
import io.github.mfuerstenau.leia.protocol.index.TermStats;
import io.github.mfuerstenau.leia.protocol.index.VectorEncoding;
import io.github.mfuerstenau.leia.protocol.index.VectorSimilarity;
import io.github.mfuerstenau.leia.protocol.terms.FieldTerms;
import io.github.mfuerstenau.leia.protocol.terms.TopTerm;
import io.github.mfuerstenau.leia.tui.TestScreens;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** The tests of {@code overview.rs}, ported. */
class OverviewTest {

    private static IndexField field(String name, int number) {
        return new IndexField(
                name,
                number,
                IndexOptions.NONE,
                false,
                false,
                false,
                DocValuesType.NONE,
                0,
                0,
                0,
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static IndexField indexed(String name, int number) {
        return new IndexField(
                name,
                number,
                IndexOptions.DOCS,
                false,
                false,
                false,
                DocValuesType.NONE,
                0,
                0,
                0,
                Optional.empty(),
                Optional.empty(),
                Optional.of(new TermStats(3, 3, 3)));
    }

    private static TopTerm top(String term, boolean binary, int docFreq) {
        return new TopTerm(term, binary, docFreq, docFreq + 1L);
    }

    private static FieldTerms terms(String field, long uniqueTerms, List<TopTerm> topTerms) {
        return new FieldTerms(field, uniqueTerms, topTerms);
    }

    private static GetIndexStatsResult stats(List<IndexField> fields) {
        return new GetIndexStatsResult(
                "/data/products",
                "MMapDirectory",
                1,
                2,
                3,
                List.of(new IndexCommitStats(
                        "/data/products",
                        0,
                        "segments_2",
                        2,
                        7,
                        1,
                        2,
                        3,
                        9,
                        "9.12.3",
                        Optional.of("9.12.3"),
                        Map.of("writer", "test"),
                        List.of(new ReaderSegment("_0", 0, 3, 2)))),
                fields);
    }

    private static Overview overview(List<IndexField> fields, int topN) {
        return new Overview(stats(fields), topN);
    }

    private static String render(Overview overview, int width, int height) {
        return TestScreens.render(frame -> overview.render(frame, frame.area()), width, height);
    }

    /** The row of the fields table that holds {@code name}. */
    private static String row(String screen, String name) {
        // The name cell is padded; a panel title is followed by its border.
        return screen.lines()
                .filter(line -> line.contains(" " + name + "  "))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row " + name + " in\n" + screen));
    }

    /** The field names in table order. */
    private static List<String> names(Overview overview) {
        List<String> names = new java.util.ArrayList<>();
        overview.handleKey(key(KeyCode.HOME));
        for (int i = 0; i < overview.fields().size(); i++) {
            names.add(overview.selectedField().orElseThrow().name());
            overview.handleKey(key(KeyCode.DOWN));
        }
        return names;
    }

    private static void done(Overview overview, String field, long uniqueTerms) {
        overview.setScan(field, Optional.of(FieldScan.done(terms(field, uniqueTerms, List.of()), 50)));
    }

    private static Optional<String> selected(Overview overview) {
        return overview.selectedField().map(IndexField::name);
    }

    @Test
    void theVectorColumnSaysDimensionEncodingAndSimilarity() {
        IndexField vectors = new IndexField(
                "embedding",
                0,
                IndexOptions.NONE,
                false,
                false,
                false,
                DocValuesType.NONE,
                0,
                0,
                768,
                Optional.of(VectorEncoding.BYTE),
                Optional.of(VectorSimilarity.MAXIMUM_INNER_PRODUCT),
                Optional.empty());
        IndexField none = new IndexField(
                "id",
                1,
                IndexOptions.DOCS,
                false,
                false,
                false,
                DocValuesType.NONE,
                0,
                0,
                0,
                Optional.empty(),
                Optional.empty(),
                Optional.empty());

        assertThat(Overview.vector(vectors)).isEqualTo("768d byte mip");
        assertThat(Overview.vector(none)).isEqualTo("-");
    }

    @Test
    void theSummaryHasNoLogo() {
        Overview overview = overview(List.of(field("id", 0)), 50);

        String screen = render(overview, 140, 40);
        List<String> lines = screen.lines().toList();
        assertThat(lines.get(0)).contains(" Index ");
        assertThat(lines.get(1)).contains("Path");
        // The panel ends after the summary: border, its seven rows, border.
        int bottom = IntStream.range(1, lines.size())
                .filter(i -> lines.get(i).startsWith("└"))
                .findFirst()
                .orElseThrow();
        assertThat(bottom).isEqualTo(8);
    }

    @Test
    void showsSummaryAndFields() {
        IndexField body = new IndexField(
                "body",
                1,
                IndexOptions.DOCS_AND_FREQS_AND_POSITIONS,
                true,
                false,
                true,
                DocValuesType.NONE,
                0,
                0,
                0,
                Optional.empty(),
                Optional.empty(),
                Optional.of(new TermStats(3, 6, 9)));
        IndexField price = new IndexField(
                "price",
                2,
                IndexOptions.NONE,
                false,
                false,
                false,
                DocValuesType.SORTED_NUMERIC,
                1,
                4,
                0,
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
        Overview overview = overview(List.of(field("id", 0), body, price), 50);

        String screen = render(overview, 120, 20);

        assertThat(screen)
                .contains(
                        "Path       /data/products",
                        "Commit     segments_2 (generation 2, version 7)",
                        "Segments   1 (fully merged)",
                        "Documents  2 live, 1 deleted, 3 max",
                        "Lucene     created by 9, commit written by 9.12.3, oldest segment by 9.12.3",
                        "User data  writer=test",
                        "Fields (3)");
        assertThat(row(screen, "body")).contains("positions", "NV-", "3", "6", "9");
        assertThat(row(screen, "price")).contains("sorted numeric", "1×4B");
    }

    @Test
    void movesTheSelectionWithinTheFields() {
        Overview overview =
                overview(IntStream.range(0, 30).mapToObj(i -> field("f" + i, i)).toList(), 50);
        assertThat(selected(overview)).hasValue("f0");

        assertThat(overview.handleKey(key(KeyCode.DOWN))).isTrue();
        assertThat(selected(overview)).hasValue("f1");
        overview.handleKey(key(KeyCode.UP));
        overview.handleKey(key(KeyCode.UP));
        assertThat(selected(overview)).hasValue("f0");
        overview.handleKey(key(KeyCode.PAGE_DOWN));
        assertThat(selected(overview)).hasValue("f10");
        overview.handleKey(key('G'));
        assertThat(selected(overview)).hasValue("f29");
        overview.handleKey(key(KeyCode.DOWN));
        assertThat(selected(overview)).hasValue("f29");
        overview.handleKey(key('g'));
        assertThat(selected(overview)).hasValue("f0");
        assertThat(overview.handleKey(key('x'))).isFalse();

        // The selected row is scrolled into view.
        overview.handleKey(key(KeyCode.END));
        assertThat(render(overview, 120, 30)).contains(" f29  ");
    }

    @Test
    void dropsWholeColumnsFromTheRightWhenNarrow() {
        // "#" 1, "Name" 20 (counts as 16), then 9 and 5, with 2 cells between columns.
        int[] widths = {1, 20, 9, 5};
        assertThat(Overview.shownColumns(widths, 40)).isEqualTo(4);
        assertThat(Overview.shownColumns(widths, 1 + 2 + 16 + 2 + 9 + 2 + 5)).isEqualTo(4);
        assertThat(Overview.shownColumns(widths, 1 + 2 + 16 + 2 + 9 + 2 + 4)).isEqualTo(3);
        assertThat(Overview.shownColumns(widths, 1 + 2 + 16 + 2 + 8)).isEqualTo(2);
        // The number and the name stay, however narrow.
        assertThat(Overview.shownColumns(widths, 5)).isEqualTo(2);
    }

    @Test
    void hidesColumnsThatDoNotFit() {
        String screen = render(overview(List.of(field("id", 0)), 50), 60, 15);

        assertThat(screen).contains("Postings").doesNotContain("Σ term freq");
    }

    @Test
    void handlesAnIndexWithoutFields() {
        Overview overview = overview(List.of(), 50);

        assertThat(render(overview, 80, 15)).contains("Fields (0)");
        assertThat(overview.selectedField()).isEmpty();
        overview.handleKey(key(KeyCode.DOWN));
        assertThat(overview.selectedField()).isEmpty();
    }

    @Test
    void showsTermCountAndTopTermsNextToTheFields() {
        Overview overview = overview(List.of(indexed("body", 0), field("price", 1)), 50);
        overview.setScan(
                "body",
                Optional.of(FieldScan.done(
                        terms(
                                "body",
                                1234,
                                List.of(top("red", false, 7), top("ff0041", true, 2), top("a\nb", false, 1))),
                        50)));

        String screen = render(overview, 140, 30);

        assertThat(row(screen, "body")).contains("1234");
        assertThat(screen)
                .contains(
                        "Fields (2, 1234 terms)",
                        "body: 1234 terms, top 3",
                        "#  Docs  Freq  Term",
                        "1     7     8  red",
                        "2     2     3  0xff0041",
                        "3     1     2  a\\nb");
    }

    @Test
    void putsTheTermsBelowTheFieldsWhenNarrow() {
        List<String> lines = render(overview(List.of(indexed("body", 0)), 50), 100, 30)
                .lines()
                .toList();

        int fields = IntStream.range(0, lines.size())
                .filter(i -> lines.get(i).contains("Fields (1)"))
                .findFirst()
                .orElseThrow();
        int terms = IntStream.range(0, lines.size())
                .filter(i -> lines.get(i).contains("Terms: body"))
                .findFirst()
                .orElseThrow();
        assertThat(fields).isLessThan(terms);
    }

    @Test
    void theTermsColumnShowsTheScan() {
        Overview overview = overview(
                List.of(
                        indexed("none", 0),
                        indexed("queued", 1),
                        indexed("running", 2),
                        indexed("failed", 3),
                        field("price", 4)),
                50);
        overview.setScan("queued", Optional.of(new FieldScan.Queued()));
        overview.setScan("running", Optional.of(new FieldScan.Running(1024)));
        overview.setScan("failed", Optional.of(new FieldScan.Failed("boom")));

        String screen = render(overview, 140, 30);

        assertThat(row(screen, "none")).contains("?");
        assertThat(row(screen, "queued")).contains("queued");
        assertThat(row(screen, "running")).contains("1024…");
        assertThat(row(screen, "failed")).contains("failed");
        assertThat(row(screen, "price")).contains("-");
        // Not every field has been counted.
        assertThat(screen).contains("Fields (5)");
    }

    @Test
    void theTermsPanelSaysWhatToDo() {
        Overview overview = overview(List.of(indexed("body", 0), field("price", 1)), 50);
        assertThat(render(overview, 140, 30)).contains("Enter counts the terms and finds the top 50.");

        overview.setScan("body", Optional.of(new FieldScan.Running(2048)));
        assertThat(render(overview, 140, 30)).contains("Scanning… 2048 terms so far.");

        overview.setScan("body", Optional.of(new FieldScan.Failed("Cannot read")));
        assertThat(render(overview, 140, 30)).contains("The scan failed: Cannot read");

        overview.handleKey(key(KeyCode.DOWN));
        assertThat(render(overview, 140, 30)).contains("No postings: the field is not indexed.");
    }

    @Test
    void tabMovesTheFocusToTheTopTerms() {
        Overview overview = overview(List.of(indexed("body", 0), indexed("id", 1)), 50);
        overview.setScan(
                "body",
                Optional.of(FieldScan.done(
                        terms("body", 3, List.of(top("a", false, 3), top("b", false, 2), top("c", false, 1))), 50)));
        assertThat(overview.selectedTopTerm()).isEmpty();

        assertThat(overview.handleKey(key(KeyCode.TAB))).isTrue();
        overview.handleKey(key(KeyCode.DOWN));
        overview.handleKey(key(KeyCode.DOWN));
        overview.handleKey(key(KeyCode.DOWN));

        assertThat(overview.selectedTopTerm()).hasValue(new Overview.FieldTerm("body", new BytesValue("c", false)));
        assertThat(selected(overview)).hasValue("body");

        overview.handleKey(key(KeyCode.TAB));
        overview.handleKey(key(KeyCode.DOWN));
        assertThat(selected(overview)).hasValue("id");
        assertThat(overview.selectedTopTerm()).isEmpty();
        overview.handleKey(key(KeyCode.UP));
        overview.handleKey(key(KeyCode.TAB));
        assertThat(overview.selectedTopTerm()).hasValue(new Overview.FieldTerm("body", new BytesValue("c", false)));
    }

    @Test
    void listsTheFieldsToScan() {
        Overview overview = overview(
                List.of(indexed("a", 0), field("b", 1), indexed("c", 2), indexed("d", 3), indexed("e", 4)), 50);
        done(overview, "c", 0);
        overview.setScan("d", Optional.of(new FieldScan.Failed("boom")));
        overview.setScan("e", Optional.of(new FieldScan.Queued()));

        assertThat(overview.fieldsToScan()).containsExactly("a", "d");
    }

    @Test
    void sortsByNumberNameAndTermCount() {
        Overview overview = overview(
                List.of(indexed("c", 0), indexed("a", 1), field("d", 2), indexed("b", 3), indexed("e", 4)), 50);
        done(overview, "c", 5);
        done(overview, "a", 9);
        done(overview, "e", 5);
        assertThat(names(overview)).containsExactly("c", "a", "d", "b", "e");
        assertThat(render(overview, 140, 30)).contains("# ▲");

        overview.handleKey(key('S'));
        assertThat(names(overview)).containsExactly("e", "b", "d", "a", "c");

        overview.handleKey(key('s'));
        assertThat(names(overview)).containsExactly("a", "b", "c", "d", "e");
        assertThat(render(overview, 140, 30)).contains("Name ▲");

        // The largest count first; equal counts by number; fields without a count last.
        overview.handleKey(key('s'));
        assertThat(names(overview)).containsExactly("a", "c", "e", "d", "b");
        assertThat(render(overview, 140, 30)).contains("Terms ▼");
        overview.handleKey(key('S'));
        assertThat(names(overview)).containsExactly("c", "e", "a", "d", "b");

        // A new count moves its field.
        done(overview, "b", 1);
        assertThat(names(overview)).containsExactly("b", "c", "e", "a", "d");

        overview.handleKey(key('s'));
        assertThat(names(overview)).containsExactly("c", "a", "d", "b", "e");
    }

    @Test
    void theSelectionStaysOnItsFieldWhenTheRowsMove() {
        Overview overview = overview(List.of(indexed("b", 0), indexed("a", 1)), 50);
        overview.handleKey(key(KeyCode.DOWN));
        assertThat(selected(overview)).hasValue("a");

        overview.handleKey(key('s'));

        assertThat(selected(overview)).hasValue("a");
        // Now in the first row.
        overview.handleKey(key(KeyCode.UP));
        assertThat(selected(overview)).hasValue("a");
    }

    @Test
    void reopeningKeepsTheSortAndTheSelectedFieldButNotTheScans() {
        Overview overview = overview(List.of(indexed("b", 0), indexed("a", 1), indexed("c", 2)), 50);
        done(overview, "a", 3);
        // Sorted by name, the selection stays on b, in the second row.
        overview.handleKey(key('s'));
        assertThat(selected(overview)).hasValue("b");

        Overview reopened = overview.reopened(
                stats(List.of(indexed("c", 0), indexed("b", 1), indexed("a", 2), indexed("d", 3))), 20);

        assertThat(selected(reopened)).hasValue("b");
        assertThat(reopened.scan("a")).isEmpty();
        assertThat(names(reopened)).containsExactly("a", "b", "c", "d");

        // A field that is gone leaves the selection on the first row.
        reopened.handleKey(key(KeyCode.DOWN));
        Overview again = reopened.reopened(stats(List.of(indexed("x", 0), indexed("a", 1))), 20);
        assertThat(selected(again)).hasValue("a");
    }

    @Test
    void showsTheShareOfAllTermsOnceEveryFieldIsCounted() {
        Overview overview = overview(List.of(indexed("body", 0), indexed("id", 1), field("price", 2)), 50);
        done(overview, "body", 3);
        assertThat(row(render(overview, 140, 30), "body")).contains("  3  ?  ");

        done(overview, "id", 1);
        String screen = render(overview, 140, 30);
        assertThat(row(screen, "body")).contains("  3  75.00  ");
        assertThat(row(screen, "id")).contains("  1  25.00  ");
        assertThat(row(screen, "price")).contains("  -      -  ");
    }

    @Test
    void stepsTheNumberOfTopTerms() {
        assertThat(Overview.stepTopTerms(50, true)).isEqualTo(100);
        assertThat(Overview.stepTopTerms(50, false)).isEqualTo(20);
        assertThat(Overview.stepTopTerms(10, false)).isEqualTo(10);
        assertThat(Overview.stepTopTerms(1000, true)).isEqualTo(1000);
    }

    @Test
    void showsAtMostTheTopNAndRescansForMore() {
        List<TopTerm> three = List.of(top("a", false, 3), top("b", false, 2), top("c", false, 1));
        Overview overview = overview(List.of(indexed("body", 0), indexed("id", 1)), 3);
        overview.setScan("body", Optional.of(FieldScan.done(terms("body", 10, three), 3)));
        // Every term of the field is in.
        overview.setScan("id", Optional.of(FieldScan.done(terms("id", 3, three), 3)));
        assertThat(overview.fieldsToScan()).isEmpty();

        overview.setTopN(2);
        String screen = render(overview, 140, 30);
        assertThat(screen).contains("body: 10 terms, top 2 ").doesNotContain("3     4  c");
        assertThat(overview.fieldsToScan()).isEmpty();

        overview.setTopN(5);
        assertThat(render(overview, 140, 30)).contains("body: 10 terms, top 3 (⏎ top 5)");
        assertThat(overview.fieldsToScan()).containsExactly("body");
    }

    @Test
    void wrapsWhyAnIndexWasSkipped() {
        String longMessage = "Corrupt index, missing file: /data/products/broken/_0.si "
                + "(resource=MMapIndexInput(path=\"/data/products/broken/_0.si\")) "
                + "and the rest of a message that is far too long to be shown whole";
        Overview overview = overview(List.of(field("id", 0)), 50)
                .withSkipped(List.of(
                        new SkippedIndex("/data/products/broken", longMessage),
                        new SkippedIndex("/data/products/old", "Unsupported\nformat")));

        List<String> lines = render(overview, 72, 30).lines().toList();
        int at = indexOf(lines, "broken  skipped: Corrupt index, missing file:");
        // Under the start of the message, cut after three lines.
        assertThat(lines.get(at + 1)).startsWith("│                   /data/products/broken/_0.si");
        assertThat(lines.get(at + 2).stripTrailing()).endsWith("…│");
        // Line breaks are escaped.
        assertThat(lines.get(at + 3)).contains("old     skipped: Unsupported\\nformat");

        // Too narrow to wrap under the message: under the name instead.
        lines = render(overview, 44, 30).lines().toList();
        at = indexOf(lines, "broken  skipped: Corrupt");
        assertThat(lines.get(at + 1)).startsWith("│    ").doesNotStartWith("│     ");
    }

    private static int indexOf(List<String> lines, String text) {
        return IntStream.range(0, lines.size())
                .filter(i -> lines.get(i).contains(text))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no line with " + text + " in\n" + String.join("\n", lines)));
    }
}
