package io.github.mfuerstenau.leia.tui.check;

import static io.github.mfuerstenau.leia.tui.TestScreens.key;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tamboui.style.Style;
import dev.tamboui.tui.event.KeyCode;
import io.github.mfuerstenau.leia.protocol.check.CheckIndexResult;
import io.github.mfuerstenau.leia.protocol.check.GetCheckIndexResult;
import io.github.mfuerstenau.leia.protocol.check.IndexCheck;
import io.github.mfuerstenau.leia.protocol.check.SegmentCheck;
import io.github.mfuerstenau.leia.tui.TestScreens;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** The tests of {@code check.rs}, ported, and what the ids add. */
class CheckTest {

    private static IndexCheck index(String path, boolean clean) {
        return new IndexCheck(
                path,
                false,
                clean,
                Optional.empty(),
                Optional.of("segments_2"),
                false,
                false,
                2,
                0,
                0,
                true,
                List.of());
    }

    private static IndexCheck broken(String path) {
        return new IndexCheck(
                path,
                false,
                false,
                Optional.empty(),
                Optional.of("segments_2"),
                false,
                false,
                2,
                1,
                3,
                true,
                List.of(new SegmentCheck(
                        "_1",
                        3,
                        Optional.of("Lucene103"),
                        true,
                        4,
                        2048,
                        false,
                        true,
                        Optional.of("checksum failed"))));
    }

    private static IndexCheck unreadable(String path) {
        return new IndexCheck(
                path,
                true,
                false,
                Optional.of("no segments* file found"),
                Optional.empty(),
                false,
                false,
                2,
                0,
                0,
                true,
                List.of());
    }

    private static List<String> lines(int from, int to) {
        return IntStream.range(from, to).mapToObj(i -> "line " + i).toList();
    }

    private static GetCheckIndexResult progress(List<String> lines, CheckIndexResult result) {
        return new GetCheckIndexResult(0, lines, Optional.ofNullable(result));
    }

    private static String render(Check check, int width, int height) {
        return TestScreens.render(frame -> check.render(frame, frame.area()), width, height);
    }

    /** Enter on {@code check}: the id of the check it starts. */
    private static long start(Check check) {
        return ((Check.Request.Start) check.handleKey(key(KeyCode.ENTER)).orElseThrow()).checkId();
    }

    /** Lucene 3.6 has no levels (ADR 0045): no l, and the check starts without one. */
    @Test
    void aCheckWithoutLevelsHasNoL() {
        Check check = new Check("/data", false);
        assertThat(check.keys()).isEqualTo("⏎ check   Tab panel   ↑↓←→");
        assertThat(check.handleKey(key('l'))).contains(new Check.Request.Unsupported("Check level"));
        assertThat(check.handleKey(key(KeyCode.ENTER))).contains(new Check.Request.Start(1, OptionalInt.empty()));
    }

    @Test
    void enterStartsACheckAtTheChosenLevel() {
        Check check = new Check("/data", true);
        assertThat(check.handleKey(key('l'))).isEmpty();
        assertThat(check.handleKey(key(KeyCode.ENTER))).contains(new Check.Request.Start(1, OptionalInt.of(2)));
        assertThat(check.running()).isTrue();
        assertThat(check.job()).isEmpty();
        // No second check while one runs, and the level stays.
        assertThat(check.handleKey(key(KeyCode.ENTER))).isEmpty();
        check.handleKey(key('l'));
        assertThat(check.keys()).startsWith("Esc cancel");

        assertThat(check.started(1, 7)).isEmpty();
        assertThat(check.job()).hasValueSatisfying(job -> assertThat(job).containsExactly(1, 7));
        assertThat(check.keys()).isEqualTo("Esc cancel check   Tab panel   ↑↓←→");
    }

    @Test
    void theLevelCycles() {
        Check check = new Check("/data", true);
        for (int level : new int[] {2, 3, 1}) {
            check.handleKey(key('l'));
            assertThat(check.keys()).contains("l level: " + level + " " + Check.levelName(level));
        }
    }

    @Test
    void collectsTheLinesUntilTheOutcome() {
        Check check = new Check("/data", true);
        long id = start(check);
        check.started(id, 1);
        check.progress(id, progress(lines(0, 3), null));
        assertThat(check.fromLine()).isEqualTo(3);
        check.progress(id, progress(lines(3, 5), new CheckIndexResult(List.of(index("/data", true)), 1200)));

        assertThat(check.fromLine()).isEqualTo(5);
        assertThat(check.running()).isFalse();
        assertThat(check.job()).isEmpty();
        String screen = render(check, 60, 12);
        assertThat(screen).contains("OK: no problems found in 1 index (took 1.2 s)", "line 4");
    }

    @Test
    void showsWhatIsBrokenPerIndex() {
        Check check = new Check("/data", true);
        long id = start(check);
        check.started(id, 1);
        check.progress(
                id,
                progress(
                        List.of(),
                        new CheckIndexResult(
                                List.of(index("/data/a", true), broken("/data/b"), unreadable("/data/c")), 50)));

        assertThat(render(check, 100, 20))
                .contains(
                        "BAD: problems in 2 of 3 indexes (took 0.1 s)",
                        "✓ a: segments_2, 2 segments, no problems",
                        "✗ b: segments_2, 1 of 2 segments broken, 3 documents would be lost",
                        "    _1 (3 docs, 2.0 KiB): checksum failed",
                        "✗ c (skipped when opened): could not be checked",
                        "    no segments* file found");
    }

    @Test
    void theOutputFollowsItsEndUntilScrolledUp() {
        Check check = new Check("/data", true);
        long id = start(check);
        check.started(id, 1);
        check.progress(id, progress(lines(0, 50), null));
        // 20 rows: the summary takes 3, the output 15 inside its borders.
        String screen = render(check, 60, 20);
        assertThat(screen).contains("line 49", "lines 36–50 of 50");

        check.handleKey(key(KeyCode.PAGE_UP));
        check.progress(id, progress(lines(50, 60), null));
        assertThat(render(check, 60, 20)).contains("lines 21–35 of 60");

        check.handleKey(key(KeyCode.END));
        check.progress(id, progress(lines(60, 61), null));
        assertThat(render(check, 60, 20)).contains("line 60");
    }

    @Test
    void leftAndRightScrollWideOutput() {
        Check check = new Check("/data", true);
        long id = start(check);
        check.progress(id, progress(List.of("x".repeat(60) + "END"), null));
        assertThat(render(check, 40, 12)).doesNotContain("END");

        for (int i = 0; i < 5; i++) {
            check.handleKey(key(KeyCode.RIGHT));
        }
        // No further than the end of the widest line.
        assertThat(render(check, 40, 12)).contains("xEND┃");
        check.handleKey(key(KeyCode.LEFT));
        assertThat(render(check, 40, 12)).doesNotContain("END");
    }

    @Test
    void tabMovesTheScrollingToTheSummary() {
        Check check = new Check("/data", true);
        long id = start(check);
        check.progress(id, progress(lines(0, 50), null));
        render(check, 60, 20);
        check.handleKey(key(KeyCode.TAB));
        check.handleKey(key(KeyCode.PAGE_UP));
        // The output still shows its end.
        assertThat(render(check, 60, 20)).contains("lines 36–50 of 50");
    }

    @Test
    void cancellingOrReopeningStopsARunningCheck() {
        Check check = new Check("/data", true);
        long id = start(check);
        check.started(id, 3);
        assertThat(check.cancel()).hasValue(3);
        assertThat(check.running()).isFalse();
        assertThat(render(check, 60, 10)).contains("Cancelled.");

        start(check);
        check.stoppedByReopen();
        assertThat(render(check, 60, 10)).contains("Stopped: the index was reopened.");
        // A check that is done stays as it is.
        check.stoppedByReopen();
        assertThat(render(check, 60, 10)).contains("Stopped: the index was reopened.");
        assertThat(check.cancel()).isEmpty();
    }

    @Test
    void escBeforeTheJobStartsStopsTheCheckAndCancelsTheJobWhenItComes() {
        Check check = new Check("/data", true);
        long id = start(check);
        assertThat(check.cancel()).isEmpty();
        assertThat(check.running()).isFalse();

        assertThat(check.started(id, 4)).isEqualTo(OptionalLong.of(4));
        assertThat(check.job()).isEmpty();
        assertThat(render(check, 60, 10)).contains("Cancelled.");
    }

    @Test
    void dropsTheRepliesOfAnEarlierCheck() {
        Check check = new Check("/data", true);
        long first = start(check);
        check.cancel();
        long second = start(check);
        assertThat(second).isNotEqualTo(first);
        assertThat(check.started(first, 1)).hasValue(1);
        assertThat(check.started(second, 2)).isEmpty();

        check.progress(first, progress(lines(0, 3), new CheckIndexResult(List.of(), 1)));
        check.failed(first, "gone");
        assertThat(check.running()).isTrue();
        assertThat(check.fromLine()).isZero();
    }

    @Test
    void showsWhyTheCheckFailed() {
        Check check = new Check("/data", true);
        long id = start(check);
        check.failed(id, "No index is open (NO_INDEX_OPEN -32004)");

        assertThat(check.running()).isFalse();
        assertThat(render(check, 80, 10)).contains("The check failed: No index is open");
    }

    @Test
    void outputLinesShowTabsAsSpacesAndMarkErrors() {
        assertThat(Check.outputText("\tat x\u0007")).isEqualTo("    at x");
        assertThat(Check.outputStyle("ERROR: x")).isEqualTo(Style.EMPTY.red());
        assertThat(Check.outputStyle("    test: open reader.........FAILED")).isEqualTo(Style.EMPTY.red());
        assertThat(Check.outputStyle("WARNING: y")).isEqualTo(Style.EMPTY.yellow());
        assertThat(Check.outputStyle("OK")).isEqualTo(Style.EMPTY);
    }
}
