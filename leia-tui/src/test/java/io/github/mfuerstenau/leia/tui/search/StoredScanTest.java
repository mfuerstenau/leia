package io.github.mfuerstenau.leia.tui.search;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import io.github.mfuerstenau.leia.protocol.search.GetStoredScanResult;
import io.github.mfuerstenau.leia.protocol.search.MatchSpan;
import io.github.mfuerstenau.leia.protocol.search.Snippet;
import io.github.mfuerstenau.leia.protocol.search.StartStoredScanParams;
import io.github.mfuerstenau.leia.protocol.search.StoredScanHit;
import io.github.mfuerstenau.leia.protocol.search.StoredScanResult;
import io.github.mfuerstenau.leia.protocol.search.ValueRef;
import io.github.mfuerstenau.leia.tui.theme.Palette;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The tests of {@code stored_scan.rs}, ported. */
class StoredScanTest {

    private static StartStoredScanParams params() {
        return new StartStoredScanParams("red", false, Optional.empty(), false);
    }

    private static StoredScanHit hit(
            int docId, String field, String text, int snippetStart, int length, int[]... spans) {
        return new StoredScanHit(
                docId,
                false,
                field,
                0,
                length,
                spans.length,
                List.of(spans).stream()
                        .map(span -> new MatchSpan(span[0], span[1]))
                        .toList(),
                new Snippet(snippetStart, text));
    }

    private static StoredScanHit hit(int docId, String field) {
        return hit(docId, field, "red", 0, 3, new int[] {0, 3});
    }

    private static GetStoredScanResult poll(List<StoredScanHit> hits, StoredScanResult result) {
        return new GetStoredScanResult(50, 7, 0, hits, Optional.ofNullable(result));
    }

    private static StoredScanResult outcome() {
        return new StoredScanResult(100, 2, 3, false, 1, List.of(new ValueRef(7, "odd", 0)), 12);
    }

    @Test
    void appendsTheHitsOfEachPollUntilTheOutcome() {
        StoredScan scan = new StoredScan(params());
        scan.started(4);
        assertThat(scan.job()).hasValue(4);

        scan.progress(poll(List.of(hit(1, "a")), null));
        assertThat(scan.fromHit()).isEqualTo(1);
        assertThat(scan.selected().map(StoredScanHit::docId)).hasValue(1);
        scan.progress(poll(List.of(hit(2, "a")), outcome()));

        assertThat(scan.fromHit()).isEqualTo(2);
        assertThat(scan.job()).isEmpty();
        assertThat(scan.matchedValues()).isEqualTo(3);
        assertThat(scan.detailLines().stream().map(Line::rawContent)).contains("  doc 7 · odd[0]");
    }

    @Test
    void aStoppedScanKeepsItsHits() {
        StoredScan scan = new StoredScan(params());
        scan.started(4);
        scan.progress(poll(List.of(hit(1, "a")), null));

        scan.cancelled();

        assertThat(scan.job()).isEmpty();
        assertThat(scan.hits()).hasSize(1);
        assertThat(scan.title(100)).contains("so far · stopped");
        // A late poll changes nothing.
        scan.progress(poll(List.of(hit(2, "a")), null));
        assertThat(scan.hits()).hasSize(1);
    }

    @Test
    void stepsThroughTheDocumentsOfTheHits() {
        StoredScan scan = new StoredScan(params());
        scan.progress(poll(List.of(hit(1, "a"), hit(1, "b"), hit(4, "a"), hit(4, "b"), hit(9, "a")), null));

        assertThat(scan.step(true)).hasValue(4);
        assertThat(scan.selectedRow()).isEqualTo(2);
        assertThat(scan.step(true)).hasValue(9);
        assertThat(scan.step(true)).isEmpty();
        assertThat(scan.step(false)).hasValue(4);
        // The first hit of the document.
        assertThat(scan.selectedRow()).isEqualTo(2);
        assertThat(scan.step(false)).hasValue(1);
        assertThat(scan.selectedRow()).isEqualTo(0);
        assertThat(scan.step(false)).isEmpty();
    }

    @Test
    void highlightsTheMatchesInTheSnippet() {
        // The spans are in UTF-8 bytes: ä takes two.
        assertThat("ä ".getBytes(StandardCharsets.UTF_8)).hasSize(3);
        StoredScanHit hit = hit(1, "a", "ä red\tred", 0, 100, new int[] {3, 6}, new int[] {7, 10}, new int[] {40, 43});

        List<Map.Entry<String, Boolean>> parts = StoredScan.snippetLine(hit).spans().stream()
                .map((Span span) -> Map.entry(
                        span.content(), span.style().equals(Palette.current().match())))
                .toList();

        assertThat(parts)
                .containsExactly(
                        Map.entry("ä ", false),
                        Map.entry("red", true),
                        Map.entry("\\t", false),
                        Map.entry("red", true),
                        Map.entry("…", false));
    }

    @Test
    void aSnippetFromInsideTheValueStartsWithAnEllipsis() {
        StoredScanHit hit = hit(1, "a", "xx red", 10, 16, new int[] {13, 16});

        assertThat(StoredScan.snippetLine(hit).rawContent()).isEqualTo("…xx red");
    }
}
