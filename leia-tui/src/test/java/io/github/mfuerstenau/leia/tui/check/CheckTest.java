package io.github.mfuerstenau.leia.tui.check;

import static io.github.mfuerstenau.leia.tui.TestScreens.key;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tamboui.style.Style;
import dev.tamboui.tui.event.KeyCode;
import io.github.mfuerstenau.leia.protocol.check.CheckIndexResult;
import io.github.mfuerstenau.leia.protocol.check.CheckTarget;
import io.github.mfuerstenau.leia.protocol.check.CheckTargetIndex;
import io.github.mfuerstenau.leia.protocol.check.CheckTargetSegment;
import io.github.mfuerstenau.leia.protocol.check.GetCheckIndexResult;
import io.github.mfuerstenau.leia.protocol.check.GetCheckTargetsResult;
import io.github.mfuerstenau.leia.protocol.check.IndexCheck;
import io.github.mfuerstenau.leia.protocol.check.SegmentCheck;
import io.github.mfuerstenau.leia.tui.TestScreens;
import io.github.mfuerstenau.leia.tui.text.TextFormat;
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
                List.of(),
                Optional.empty(),
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
                        "_1", 3, Optional.of("Lucene103"), true, 4, 2048, false, true, Optional.of("checksum failed"))),
                Optional.empty(),
                List.of());
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
                List.of(),
                Optional.empty(),
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
        Check check = new Check("/data", false, false);
        assertThat(check.keys()).isEqualTo("⏎ check all   Space mark   a all   Tab panel");
        assertThat(check.handleKey(key('l'))).contains(new Check.Request.Unsupported("Check level"));
        assertThat(check.handleKey(key(KeyCode.ENTER)))
                .contains(new Check.Request.Start(1, OptionalInt.empty(), OptionalInt.empty(), Optional.empty()));
    }

    @Test
    void enterStartsACheckAtTheChosenLevel() {
        Check check = new Check("/data", true, false);
        assertThat(check.handleKey(key('l'))).isEmpty();
        assertThat(check.handleKey(key(KeyCode.ENTER)))
                .contains(new Check.Request.Start(1, OptionalInt.of(2), OptionalInt.empty(), Optional.empty()));
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
        Check check = new Check("/data", true, false);
        assertThat(check.keys()).contains("   l level");
        for (int level : new int[] {2, 3, 1}) {
            check.handleKey(key('l'));
            assertThat(render(check, 80, 20)).contains("Check (level " + level + ", " + Check.levelName(level) + ")");
        }
    }

    @Test
    void collectsTheLinesUntilTheOutcome() {
        Check check = new Check("/data", true, false);
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
        Check check = new Check("/data", true, false);
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
        Check check = new Check("/data", true, false);
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
        Check check = new Check("/data", true, false);
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
        Check check = new Check("/data", true, false);
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
        Check check = new Check("/data", true, false);
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
        Check check = new Check("/data", true, false);
        long id = start(check);
        assertThat(check.cancel()).isEmpty();
        assertThat(check.running()).isFalse();

        assertThat(check.started(id, 4)).isEqualTo(OptionalLong.of(4));
        assertThat(check.job()).isEmpty();
        assertThat(render(check, 60, 10)).contains("Cancelled.");
    }

    @Test
    void dropsTheRepliesOfAnEarlierCheck() {
        Check check = new Check("/data", true, false);
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
        Check check = new Check("/data", true, false);
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

    private static CheckTargetIndex target(String path, boolean skipped, String... segments) {
        return new CheckTargetIndex(
                path,
                skipped,
                segments.length == 0 ? Optional.empty() : Optional.of("segments_3"),
                java.util.Arrays.stream(segments)
                        .map(name -> new CheckTargetSegment(name, 10, OptionalLong.of(2048)))
                        .toList(),
                segments.length == 0 ? Optional.of("no segments* file found") : Optional.empty());
    }

    /** A check of two indexes, {@code /data/a} with three segments and the skipped {@code /data/b} with one. */
    private static Check loaded() {
        Check check = new Check("/data", true, true, 6);
        assertThat(check.initialRequest()).contains(new Check.Request.LoadTargets());
        check.targetsLoaded(new GetCheckTargetsResult(
                List.of(target("/data/a", false, "_0", "_1", "_2"), target("/data/b", true, "_0"))));
        return check;
    }

    private static Optional<List<CheckTarget>> only(Check check) {
        return ((Check.Request.Start) check.handleKey(key(KeyCode.ENTER)).orElseThrow()).only();
    }

    @Test
    void theTargetsAreFetchedOnceAndOnRequest() {
        Check check = new Check("/data", true, true, 6);
        assertThat(check.initialRequest()).contains(new Check.Request.LoadTargets());
        assertThat(check.initialRequest()).isEmpty();
        assertThat(render(check, 80, 40)).contains("Listing the segments…");
        check.targetsFailed("boom");
        assertThat(render(check, 80, 40)).contains("Cannot list the segments: boom");

        assertThat(check.handleKey(key('r'))).contains(new Check.Request.LoadTargets());
        check.targetsLoaded(new GetCheckTargetsResult(List.of(target("/data/a", false, "_0"))));
        assertThat(render(check, 80, 40)).contains("[ ] a  segments_3, 1 segment", "[ ] _0  10 docs, 2.0 KiB");
        // A reopen fetches them again.
        check.stoppedByReopen();
        assertThat(check.initialRequest()).contains(new Check.Request.LoadTargets());
    }

    @Test
    void nothingMarkedChecksEverything() {
        Check check = loaded();
        assertThat(check.keys()).contains("⏎ check all");
        assertThat(only(check)).isEmpty();
    }

    @Test
    void spaceMarksAnIndexOrASegmentAndTheIndexShowsHowMuchIsMarked() {
        Check check = loaded();
        // On the first index row: all of it.
        check.handleKey(key(' '));
        assertThat(check.keys()).contains("⏎ check marked");
        assertThat(render(check, 80, 40)).contains("[x] a", "    [x] _0", "    [x] _2", "[ ] b");

        // On a segment: unmarking it leaves the others, so the index is partly marked.
        check.handleKey(key('j'));
        check.handleKey(key('j'));
        check.handleKey(key(' '));
        assertThat(render(check, 80, 40)).contains("[-] a", "    [x] _0", "    [ ] _1", "    [x] _2");
        assertThat(only(check)).contains(List.of(new CheckTarget("/data/a", Optional.of(List.of("_0", "_2")))));
    }

    @Test
    void anIndexWithEverySegmentMarkedIsMarkedWhole() {
        Check check = loaded();
        // Down to a's segments, and mark each.
        for (int i = 0; i < 3; i++) {
            check.handleKey(key('j'));
            check.handleKey(key(' '));
        }
        assertThat(render(check, 80, 40)).contains("[x] a");
        // b's only segment.
        check.handleKey(key('j'));
        check.handleKey(key('j'));
        check.handleKey(key(' '));
        assertThat(render(check, 80, 40)).contains("[x] b");
        assertThat(only(check))
                .contains(List.of(
                        new CheckTarget("/data/a", Optional.empty()), new CheckTarget("/data/b", Optional.empty())));
    }

    @Test
    void spaceOnAMarkedIndexClearsItAndAMarksOrClearsEverything() {
        Check check = loaded();
        check.handleKey(key(' '));
        assertThat(render(check, 80, 40)).contains("[x] a");
        check.handleKey(key(' '));
        assertThat(render(check, 80, 40)).contains("[ ] a").doesNotContain("[x]");

        check.handleKey(key('a'));
        assertThat(render(check, 80, 40)).contains("[x] a", "[x] b");
        check.handleKey(key('a'));
        assertThat(render(check, 80, 40)).contains("[ ] a", "[ ] b").doesNotContain("[x]");
    }

    @Test
    void anIndexWithoutSegmentsIsMarkedWhole() {
        Check check = new Check("/data", true, true, 6);
        check.initialRequest();
        check.targetsLoaded(new GetCheckTargetsResult(List.of(target("/data/c", true))));
        assertThat(render(check, 100, 40)).contains("[ ] c (skipped when opened): no segments* file found");
        check.handleKey(key(' '));
        assertThat(only(check)).contains(List.of(new CheckTarget("/data/c", Optional.empty())));
    }

    @Test
    void theMarksSurviveAReloadForWhatIsStillThere() {
        Check check = loaded();
        check.handleKey(key(' '));
        check.handleKey(key('r'));
        check.targetsLoaded(new GetCheckTargetsResult(
                List.of(target("/data/a", false, "_0", "_2"), target("/data/b", true, "_0"))));
        assertThat(only(check)).contains(List.of(new CheckTarget("/data/a", Optional.empty())));
    }

    @Test
    void marksCantChangeWhileACheckRuns() {
        Check check = loaded();
        start(check);
        check.handleKey(key(KeyCode.TAB));
        check.handleKey(key(' '));
        check.handleKey(key('a'));
        check.cancel();
        assertThat(only(check)).isEmpty();
    }

    @Test
    void theThreadsCycleUpToTheProcessorsAndStartWithAllOfThem() {
        Check check = loaded();
        assertThat(check.keys()).contains("   t threads");
        assertThat(render(check, 100, 40)).contains("· 6 threads)");
        for (int threads : new int[] {1, 2, 4, 6, 1}) {
            assertThat(check.handleKey(key('t'))).isEmpty();
            assertThat(render(check, 100, 40)).contains("· " + TextFormat.count(threads, "thread") + ")");
        }
        assertThat(render(check, 100, 40)).contains("Check (level 1, checksums · 1 thread)");
        assertThat(check.handleKey(key(KeyCode.ENTER)))
                .contains(new Check.Request.Start(1, OptionalInt.of(1), OptionalInt.of(1), Optional.empty()));
        // The check shown keeps its threads while the next is chosen.
        check.cancel();
        check.handleKey(key('t'));
        assertThat(render(check, 100, 40)).contains("· 1 thread)");
        assertThat(Check.nextThreads(1, 1)).isEqualTo(1);
        assertThat(Check.nextThreads(8, 8)).isEqualTo(1);
    }

    @Test
    void aCheckWithoutThreadsHasNoT() {
        Check check = new Check("/data", true, false, 6);
        assertThat(check.keys()).doesNotContain("t threads");
        assertThat(check.handleKey(key('t'))).contains(new Check.Request.Unsupported("Check threads"));
        assertThat(check.handleKey(key(KeyCode.ENTER)))
                .contains(new Check.Request.Start(1, OptionalInt.of(1), OptionalInt.empty(), Optional.empty()));
    }

    @Test
    void theOutcomeShowsPerIndexAndSegmentInTheList() {
        Check check = loaded();
        // _0 and _1 of a.
        check.handleKey(key('j'));
        check.handleKey(key(' '));
        check.handleKey(key('j'));
        check.handleKey(key(' '));
        long id = start(check);
        check.started(id, 1);
        IndexCheck a = new IndexCheck(
                "/data/a",
                false,
                false,
                Optional.empty(),
                Optional.of("segments_3"),
                false,
                false,
                3,
                1,
                10,
                true,
                List.of(
                        new SegmentCheck(
                                "_0", 10, Optional.of("Lucene103"), true, 4, 2048, false, true, Optional.empty()),
                        new SegmentCheck(
                                "_1",
                                10,
                                Optional.of("Lucene103"),
                                true,
                                4,
                                2048,
                                false,
                                true,
                                Optional.of("checksum failed"))),
                Optional.of(List.of("_0", "_1", "_9")),
                List.of("_9"));
        check.progress(id, progress(List.of(), new CheckIndexResult(List.of(a), 50)));

        String screen = render(check, 100, 40);
        assertThat(screen)
                .contains(
                        "BAD: problems in 1 of 1 index (took 0.1 s)",
                        "[-] ✗ a  segments_3, 3 segments",
                        "    Not in the latest commit: _9",
                        "    [x] ✓ _0  10 docs, 2.0 KiB",
                        "    [x] ✗ _1  10 docs, 2.0 KiB",
                        "        checksum failed",
                        "    [ ] _2  10 docs, 2.0 KiB",
                        "[ ] b (skipped when opened)");
        // The segment that wasn't checked has neither sign.
        assertThat(screen).doesNotContain("✓ _2", "✗ _2", "✓ b", "✗ b");
    }

    @Test
    void aCheckStartedBeforeTheTargetsLoadedStillRuns() {
        Check check = new Check("/data", true, true, 6);
        check.initialRequest();
        assertThat(check.handleKey(key(KeyCode.ENTER)))
                .contains(new Check.Request.Start(1, OptionalInt.of(1), OptionalInt.of(6), Optional.empty()));
        // The outcome shows as before, with no list to merge it into.
        check.progress(1, progress(List.of(), new CheckIndexResult(List.of(broken("/data/b")), 50)));
        assertThat(render(check, 100, 40))
                .contains("BAD: problems in 1 of 1 index", "    _1 (3 docs, 2.0 KiB): checksum failed");
    }

    @Test
    void theCursorStaysInViewInALongList() {
        Check check = new Check("/data", true, true, 6);
        check.initialRequest();
        String[] names = IntStream.range(0, 30).mapToObj(i -> "_" + i).toArray(String[]::new);
        check.targetsLoaded(new GetCheckTargetsResult(List.of(target("/data/a", false, names))));
        render(check, 80, 40);
        check.handleKey(key('G'));
        assertThat(render(check, 80, 40)).contains("[ ] _29");
        check.handleKey(key('g'));
        assertThat(render(check, 80, 40)).contains("[ ] a").doesNotContain("_29");
    }
}
