package io.github.mfuerstenau.leia.tui.commits;

import static io.github.mfuerstenau.leia.tui.TestScreens.key;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;
import io.github.mfuerstenau.leia.protocol.commits.CodecFormats;
import io.github.mfuerstenau.leia.protocol.commits.Commit;
import io.github.mfuerstenau.leia.protocol.commits.CommitFile;
import io.github.mfuerstenau.leia.protocol.commits.FieldFormats;
import io.github.mfuerstenau.leia.protocol.commits.GetCommitResult;
import io.github.mfuerstenau.leia.protocol.commits.GetCommitsResult;
import io.github.mfuerstenau.leia.protocol.commits.Segment;
import io.github.mfuerstenau.leia.protocol.commits.UnreadableCommit;
import io.github.mfuerstenau.leia.tui.TestScreens;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/** The tests of {@code commits.rs}, ported, and what the asynchronous calls add. */
class CommitsTest {

    private static Commit commit(long generation, boolean current) {
        return new Commit(
                generation,
                "segments_" + generation,
                current,
                generation * 3,
                2,
                500,
                current ? 13 : 0,
                "10.5.2",
                current ? Map.of("writer", "demo") : Map.of());
    }

    private static Segment segment(String name, int docBase, int maxDoc) {
        return segment(name, docBase, maxDoc, 2048);
    }

    private static Segment segment(String name, int docBase, int maxDoc, long size) {
        return segment(
                name,
                docBase,
                maxDoc,
                size,
                List.of(
                        new FieldFormats("body", Optional.of("Lucene104"), Optional.empty(), Optional.empty()),
                        new FieldFormats(
                                "category", Optional.of("Lucene104"), Optional.of("Lucene90"), Optional.empty()),
                        new FieldFormats(
                                "embedding",
                                Optional.empty(),
                                Optional.empty(),
                                Optional.of("Lucene99HnswVectorsFormat")),
                        new FieldFormats("id", Optional.of("FST50"), Optional.empty(), Optional.empty()),
                        new FieldFormats("stored", Optional.empty(), Optional.empty(), Optional.empty())),
                Optional.empty());
    }

    private static Segment segment(
            String name,
            int docBase,
            int maxDoc,
            long size,
            List<FieldFormats> fieldFormats,
            Optional<String> fieldInfosError) {
        return segment(name, docBase, maxDoc, size, fieldFormats, fieldInfosError, "10.5.2");
    }

    private static Segment segment(
            String name,
            int docBase,
            int maxDoc,
            long size,
            List<FieldFormats> fieldFormats,
            Optional<String> fieldInfosError,
            String luceneVersion) {
        return new Segment(
                name,
                Optional.of("ab52725f4ed21286179cc2af33ded2b1"),
                maxDoc,
                docBase,
                3,
                0,
                OptionalLong.of(1),
                OptionalLong.empty(),
                OptionalLong.empty(),
                luceneVersion,
                Optional.of(luceneVersion),
                Optional.of("Lucene104"),
                Optional.of(new CodecFormats(
                        "PerField40",
                        "PerFieldDV40",
                        "Lucene90StoredFieldsFormat",
                        "Lucene90TermVectorsFormat",
                        "Lucene90NormsFormat",
                        Optional.of("Lucene90PointsFormat"),
                        Optional.of("PerFieldVectors90"),
                        "Lucene94FieldInfosFormat",
                        "Lucene99SegmentInfoFormat",
                        "Lucene90LiveDocsFormat",
                        "Lucene90CompoundFormat")),
                fieldFormats,
                fieldInfosError,
                name.equals("_0"),
                false,
                Optional.empty(),
                OptionalLong.of(size),
                Map.of("source", "flush", "timestamp", "1000000000000"),
                Map.of());
    }

    private static CommitFile file(String name, String segment, OptionalLong size) {
        return new CommitFile(name, Optional.ofNullable(segment), size);
    }

    private static GetCommitResult details(long generation, boolean current) {
        return new GetCommitResult(
                commit(generation, current),
                List.of(segment("_0", 0, 400), segment("_1", 400, 100)),
                List.of(
                        file("_0.cfs", "_0", OptionalLong.of(1024)),
                        file("_0.si", "_0", OptionalLong.of(1024)),
                        file("_1.fdt", "_1", OptionalLong.empty()),
                        file("segments_" + generation, null, OptionalLong.of(300))));
    }

    private static GetCommitsResult list(Commit... commits) {
        return new GetCommitsResult(List.of(commits), List.of());
    }

    private static Optional<Commits.Request> loadList(int index) {
        return Optional.of(new Commits.Request.LoadList(index));
    }

    private static Optional<Commits.Request> loadCommit(int index, long generation) {
        return Optional.of(new Commits.Request.LoadCommit(index, generation));
    }

    private static final KeyEvent BACK_TAB = KeyEvent.ofKey(KeyCode.TAB, KeyModifiers.SHIFT);

    /** The commits 4 (opened) and 3, as the server lists them. */
    private static Commits loaded() {
        Commits commits = new Commits(List.of("index"), true);
        assertThat(commits.initialRequest()).isEqualTo(loadList(0));
        assertThat(commits.commitsLoaded(0, list(commit(4, true), commit(3, false))))
                .isEqualTo(loadCommit(0, 4));
        commits.commitLoaded(0, 4, details(4, true));
        return commits;
    }

    private static String render(Commits commits, int width, int height) {
        return TestScreens.render(frame -> commits.render(frame, frame.area()), width, height);
    }

    @Test
    void fetchesTheCommitsThenTheSelectedOne() {
        Commits commits = new Commits(List.of("index"), true);
        assertThat(commits.initialRequest()).isEqualTo(loadList(0));
        assertThat(commits.commitsLoaded(0, list(commit(4, true), commit(3, false))))
                .isEqualTo(loadCommit(0, 4));
        commits.commitLoaded(0, 4, details(4, true));
        assertThat(commits.initialRequest()).isEmpty();

        assertThat(commits.handleKey(key(KeyCode.DOWN))).isEqualTo(loadCommit(0, 3));
        commits.commitFailed(0, 3, "gone");
        // A failed commit is not fetched again by moving back to it.
        assertThat(commits.handleKey(key(KeyCode.UP))).isEmpty();
        assertThat(commits.handleKey(key(KeyCode.DOWN))).isEmpty();
    }

    @Test
    void aCommitIsNotFetchedAgainWhileItsFetchIsUnderWay() {
        Commits commits = loaded();
        assertThat(commits.handleKey(key(KeyCode.DOWN))).isEqualTo(loadCommit(0, 3));
        // Back and forth before the reply is in.
        assertThat(commits.handleKey(key(KeyCode.UP))).isEmpty();
        assertThat(commits.handleKey(key(KeyCode.DOWN))).isEmpty();
        assertThat(render(commits, 120, 24)).contains("Reading the commit…");

        commits.commitLoaded(0, 3, details(3, false));
        assertThat(render(commits, 120, 24)).contains("Segments of segments_3 (2)");
    }

    @Test
    void selectsTheOpenedCommitFirst() {
        Commits commits = new Commits(List.of("index"), true);
        commits.initialRequest();
        assertThat(commits.commitsLoaded(0, list(commit(5, false), commit(4, true))))
                .isEqualTo(loadCommit(0, 4));
    }

    @Test
    void readsTheCommitsAgainAndKeepsTheSelection() {
        Commits commits = loaded();
        commits.handleKey(key(KeyCode.DOWN));
        commits.commitLoaded(0, 3, details(3, false));

        assertThat(commits.handleKey(key('r'))).isEqualTo(loadList(0));
        // A newer commit came in; the selection stays on commit 3 and fetches it again.
        assertThat(commits.commitsLoaded(0, list(commit(5, false), commit(4, true), commit(3, false))))
                .isEqualTo(loadCommit(0, 3));
    }

    @Test
    void showsTheCommitsAndTheSegments() {
        String screen = render(loaded(), 120, 24);

        assertThat(screen)
                .contains("Commits (2; ● opened, r reads again)")
                .contains("●    4  segments_4")
                .contains("writer=demo")
                .contains("Segments of segments_4 (2)")
                .contains("_1     100   400")
                .contains("Lucene104");
    }

    @Test
    void theDetailsShowTheCommitOrTheSegment() {
        Commits commits = loaded();
        String screen = render(commits, 140, 30);
        assertThat(screen)
                .contains("Generation 4 · version 12")
                .contains("The opened commit.")
                .contains("Files (4, size unknown)")
                .contains("_1.fdt         missing  _1");

        commits.handleKey(key(KeyCode.ENTER));
        commits.handleKey(key(KeyCode.DOWN));
        screen = render(commits, 140, 50);
        assertThat(screen)
                .contains("Segment _1")
                .contains("Documents 400–499 · 3 deleted")
                .contains("timestamp  1000000000000 (2001-09-09 01:46:40 UTC)")
                .contains("postings       PerField40")
                .contains("KNN vectors  Lucene99HnswVectorsFormat")
                // Only the segment's own files.
                .contains("Files (1, size unknown)");

        // Tab to the details keeps the segment.
        commits.handleKey(key(KeyCode.TAB));
        assertThat(render(commits, 140, 40)).contains("Segment _1");
    }

    /** The first lines of the details of a segment that Lucene {@code version} wrote. */
    private static List<String> segmentHead(String version) {
        Segment segment = segment("_0", 0, 400, 2048, List.of(), Optional.empty(), version);
        GetCommitResult details = new GetCommitResult(commit(4, true), List.of(segment), List.of());
        return Commits.segmentLines(segment, details, 80).stream()
                .map(Line::rawContent)
                .limit(5)
                .toList();
    }

    @Test
    void theSegmentDetailsLeaveOutWhatItsLuceneDidNotHave() {
        // Soft deletes came with 7.4, index sorting with 6.2, and blocks with 9.10.
        assertThat(segmentHead("4.10.4"))
                .containsExactly(
                        "Documents 0–399 · 3 deleted",
                        "Lucene 4.10.4 · codec Lucene104",
                        "Compound file yes · size 2.0 KiB",
                        "Generations: deletes 1 · field infos – · doc values –",
                        "Id ab52725f4ed21286179cc2af33ded2b1");
        assertThat(segmentHead("6.6.6"))
                .contains("Documents 0–399 · 3 deleted", "Sorted by document id (no index sort)")
                .doesNotContain("Compound file yes · blocks no · size 2.0 KiB");
        assertThat(segmentHead("7.7.3"))
                .contains("Documents 0–399 · 3 deleted · 0 soft-deleted", "Compound file yes · size 2.0 KiB");
        assertThat(segmentHead("9.9.2")).contains("Compound file yes · size 2.0 KiB");
        assertThat(segmentHead("10.5.2"))
                .contains(
                        "Documents 0–399 · 3 deleted · 0 soft-deleted",
                        "Compound file yes · blocks no · size 2.0 KiB",
                        "Sorted by document id (no index sort)");
    }

    @Test
    void comparesLuceneVersions() {
        assertThat(Commits.atLeast("9.10.0", 9, 10)).isTrue();
        assertThat(Commits.atLeast("9.9.2", 9, 10)).isFalse();
        assertThat(Commits.atLeast("10.0.0", 9, 10)).isTrue();
        assertThat(Commits.atLeast("2.x", 6, 2)).isFalse();
        assertThat(Commits.atLeast("unknown", 6, 2)).isTrue();
    }

    @Test
    void withoutAReaderNoCommitIsMarkedOpened() {
        Commits commits = new Commits(List.of("index"), false);
        commits.commitsLoaded(0, list(commit(4, false), commit(3, false)));
        assertThat(render(commits, 120, 24))
                .contains("Commits (2; r reads again)")
                .doesNotContain("● opened");
    }

    @Test
    void withoutAReaderACommitIsNotSaidToBeOpenedOrNot() {
        assertThat(Commits.commitLines(details(4, true), false))
                .extracting(Line::rawContent)
                .noneMatch(line -> line.contains("opened commit"));
        assertThat(Commits.commitLines(details(3, false), false))
                .extracting(Line::rawContent)
                .noneMatch(line -> line.contains("opened commit"));
        assertThat(Commits.commitLines(details(3, false), true))
                .extracting(Line::rawContent)
                .contains("Not the opened commit: its documents are not shown on the other screens.");
    }

    @Test
    void theSegmentDetailsGroupTheFieldsByFormat() {
        GetCommitResult details = details(4, true);
        assertThat(Commits.segmentLines(details.segments().getFirst(), details, 80))
                .extracting(Line::rawContent)
                .containsSubsequence(
                        "Field formats",
                        "postings     FST50",
                        "  id",
                        "postings     Lucene104",
                        "  body, category",
                        "doc values   Lucene90",
                        "  category",
                        "KNN vectors  Lucene99HnswVectorsFormat",
                        "  embedding",
                        "",
                        "Files (2, 2.0 KiB)");
    }

    @Test
    void theFieldsOfAFormatWrapUnderTheirIndent() {
        List<FieldFormats> fields = List.of("author", "body", "category", "published", "tags", "title", "url").stream()
                .map(name -> new FieldFormats(name, Optional.of("Lucene104"), Optional.empty(), Optional.empty()))
                .toList();
        Segment segment = segment("_0", 0, 1, 2048, fields, Optional.empty());
        GetCommitResult details = new GetCommitResult(commit(4, true), List.of(segment), List.of());
        List<String> lines = Commits.segmentLines(segment, details, 24).stream()
                .map(Line::rawContent)
                .toList();
        int header = lines.indexOf("postings  Lucene104");
        List<String> fieldLines = lines.subList(header + 1, lines.indexOf("Files (0, 0 B)") - 1);
        assertThat(fieldLines)
                .containsExactly("  author, body,", "  category, published,", "  tags, title, url")
                .allSatisfy(line -> assertThat(line).hasSizeLessThanOrEqualTo(24));
    }

    @Test
    void theKindOfAFormatIsBoldAndTheFormatCyan() {
        GetCommitResult details = details(4, true);
        List<Line> lines = Commits.segmentLines(details.segments().getFirst(), details, 80);
        int header = lines.stream().map(Line::rawContent).toList().indexOf("KNN vectors  Lucene99HnswVectorsFormat");
        assertThat(lines.get(header).spans())
                .extracting(Span::content, Span::style)
                .containsExactly(
                        tuple("KNN vectors", Style.EMPTY.bold()),
                        tuple("  ", Style.EMPTY),
                        tuple("Lucene99HnswVectorsFormat", Style.EMPTY.cyan()));
        assertThat(lines.get(header + 1).spans()).extracting(Span::style).containsOnly(Style.EMPTY);
    }

    @Test
    void theSegmentDetailsSayWhenNoFieldHasAFormat() {
        Segment segment = segment(
                "_0",
                0,
                1,
                2048,
                List.of(new FieldFormats("stored", Optional.empty(), Optional.empty(), Optional.empty())),
                Optional.empty());
        GetCommitResult details = new GetCommitResult(commit(4, true), List.of(segment), List.of());
        assertThat(Commits.segmentLines(segment, details, 80))
                .extracting(Line::rawContent)
                .containsSubsequence("Field formats", "none", "", "Files (0, 0 B)");
    }

    @Test
    void theSegmentDetailsSayWhyTheFieldInfosCouldNotBeRead() {
        Segment segment = segment("_0", 0, 1, 2048, List.of(), Optional.of("Corrupt index, missing file: _0.fnm"));
        GetCommitResult details = new GetCommitResult(commit(4, true), List.of(segment), List.of());
        List<Line> lines = Commits.segmentLines(segment, details, 80);
        Line error = lines.get(lines.indexOf(Line.styled("Field formats", Style.EMPTY.bold())) + 1);
        assertThat(error.rawContent()).isEqualTo("Could not be read: Corrupt index, missing file: _0.fnm");
        assertThat(error.spans()).extracting(Span::style).containsOnly(Style.EMPTY.red());
    }

    @Test
    void showsWhyACommitCouldNotBeRead() {
        Commits commits = loaded();
        commits.handleKey(key(KeyCode.DOWN));
        commits.commitFailed(0, 3, "Corrupt commit");

        assertThat(render(commits, 120, 20)).contains("Cannot read the commit: Corrupt commit");
    }

    @Test
    void showsTheCommitsThatCouldNotBeRead() {
        Commits commits = new Commits(List.of("index"), true);
        commits.initialRequest();
        GetCommitsResult result = new GetCommitsResult(
                List.of(), List.of(new UnreadableCommit("segments_2", "Corrupt index, missing file: _0.si")));
        assertThat(commits.commitsLoaded(0, result)).isEmpty();

        String screen = render(commits, 120, 24);
        assertThat(screen)
                .contains("Commits (0, 1 unreadable; ● opened, r reads again)")
                .contains("segments_2")
                .contains("unreadable: Corrupt index, missing file: _0.si")
                // The selection is on it, and the details say why, whole and with its line breaks.
                .contains("segments_2 could not be read.")
                .contains("Could not be read:");
        assertThat(commits.handleKey(key(KeyCode.DOWN))).isEmpty();
    }

    @Test
    void theDetailsShowAnUnreadableCommitWhole() {
        Commits commits = loaded();
        String message = "Corrupt index, missing file: /data/products/broken/_0.si "
                + "(resource=MMapIndexInput(path=\"/data/products/broken/_0.si\"))\n"
                + "Suppressed: checksum failed";
        List<UnreadableCommit> unreadable = List.of(new UnreadableCommit("segments_5", message));
        commits.commitsLoaded(0, new GetCommitsResult(List.of(commit(4, true), commit(3, false)), unreadable));
        // Past the two commits to the unreadable one, which needs nothing fetched.
        assertThat(commits.handleKey(key(KeyCode.DOWN))).isEqualTo(loadCommit(0, 3));
        assertThat(commits.handleKey(key(KeyCode.DOWN))).isEmpty();
        assertThat(commits.handleKey(key(KeyCode.DOWN))).isEmpty();

        String screen = render(commits, 100, 24);
        assertThat(screen)
                .contains("┌ segments_5 ")
                // Wrapped in the details, not cut at their width, and the line break kept.
                // The long word breaks at the width, as ratatui broke it.
                .contains("││roducts/broken/_0.si\"))")
                .contains("││Suppressed: checksum failed");

        // Reading the commits again keeps the selection on it.
        assertThat(commits.handleKey(key('r'))).isEqualTo(loadList(0));
        commits.commitsLoaded(0, new GetCommitsResult(List.of(commit(6, false), commit(4, true)), unreadable));
        assertThat(render(commits, 100, 24)).contains("┌ segments_5 ");
        // Back up to the commits.
        assertThat(commits.handleKey(key(KeyCode.UP))).isEqualTo(loadCommit(0, 4));
    }

    @Test
    void showsTheCommitsOfTheSelectedIndex() {
        Commits commits = new Commits(List.of("a", "b"), true);
        assertThat(commits.initialRequest()).isEqualTo(loadList(0));
        commits.commitsLoaded(0, list(commit(4, true)));
        commits.handleKey(BACK_TAB);
        assertThat(commits.handleKey(key(KeyCode.DOWN))).isEqualTo(loadList(1));
        assertThat(commits.commitsLoaded(1, list(commit(7, false), commit(6, true))))
                .isEqualTo(loadCommit(1, 6));
        commits.commitLoaded(1, 6, details(6, true));
        String screen = render(commits, 120, 30);
        assertThat(screen).contains("Indexes (2)").contains("Segments of segments_6");

        // Back to the first, whose commit is still being fetched (in Rust: was not fetched yet, so
        // it was asked for here).
        assertThat(commits.handleKey(key(KeyCode.UP))).isEmpty();
        assertThat(render(commits, 120, 30)).contains("Reading the commit…");
        commits.commitLoaded(0, 4, details(4, true));
        // Tab goes round the four panels, back to the indexes; b has all it needs.
        for (int i = 0; i < 4; i++) {
            commits.handleKey(key(KeyCode.TAB));
        }
        assertThat(commits.handleKey(key(KeyCode.DOWN))).isEmpty();
        assertThat(render(commits, 120, 30)).contains("Segments of segments_6");
    }

    @Test
    void theDetailsScrollNoFurtherThanTheirLastLine() {
        Commits commits = loaded();
        // From the commits back to the details, which keep showing the commit; its 12 lines need 2
        // scrolled away in a panel 10 lines high.
        commits.handleKey(BACK_TAB);
        commits.handleKey(key('G'));
        String screen = render(commits, 140, 17);
        assertThat(screen)
                .doesNotContain("2 segments · 500 docs")
                .contains("The opened commit.")
                .contains("segments_4       300 B");
        // The next key moves at once.
        commits.handleKey(key(KeyCode.UP));
        assertThat(render(commits, 140, 17))
                .contains("2 segments · 500 docs")
                .doesNotContain("Generation 4 · version 12");
        commits.handleKey(key('g'));
        assertThat(render(commits, 140, 17)).contains("Generation 4 · version 12");
    }

    /** The commit 4 with one segment of {@code size} bytes, read. */
    private static Commits withSegmentOfSize(long size) {
        Commits commits = new Commits(List.of("index"), true);
        commits.commitsLoaded(0, list(commit(4, true)));
        commits.commitLoaded(
                0, 4, new GetCommitResult(commit(4, true), List.of(segment("_0", 0, 400, size)), List.of()));
        return commits;
    }

    @Test
    void leavesOutTheColumnsOnTheRightInsteadOfCuttingTheSize() {
        // 508.2 KiB, as in the demo index.
        Commits commits = withSegmentOfSize(520_397);

        assertThat(render(commits, 140, 30))
                .contains("508.2 KiB")
                .contains("Lucene")
                .contains("Codec")
                .contains("CFS");
        assertThat(render(commits, 100, 30))
                .contains("Size")
                .contains("508.2 KiB")
                .contains("Codec")
                .doesNotContain("CFS");
        // Too narrow for the size: it is left out whole.
        assertThat(render(commits, 60, 30))
                .contains("Deleted")
                .doesNotContain("Size")
                .doesNotContain("508")
                .doesNotContain("KiB");
    }

    @Test
    void showsAGenerationOfFourDigitsWhole() {
        Commits commits = new Commits(List.of("index"), true);
        commits.commitsLoaded(0, list(commit(1234, true)));

        assertThat(render(commits, 120, 24)).contains("●  1234  segments_1234");
    }
}
