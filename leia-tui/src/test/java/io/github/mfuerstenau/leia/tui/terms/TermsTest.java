package io.github.mfuerstenau.leia.tui.terms;

import static io.github.mfuerstenau.leia.tui.TestScreens.key;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tamboui.tui.event.KeyCode;
import io.github.mfuerstenau.leia.protocol.document.BytesValue;
import io.github.mfuerstenau.leia.protocol.index.DocValuesType;
import io.github.mfuerstenau.leia.protocol.index.Feature;
import io.github.mfuerstenau.leia.protocol.index.IndexField;
import io.github.mfuerstenau.leia.protocol.index.IndexOptions;
import io.github.mfuerstenau.leia.protocol.index.TermStats;
import io.github.mfuerstenau.leia.protocol.index.VectorEncoding;
import io.github.mfuerstenau.leia.protocol.index.VectorSimilarity;
import io.github.mfuerstenau.leia.protocol.knn.GetKnnGraphNodeResult;
import io.github.mfuerstenau.leia.protocol.knn.GetKnnGraphNodesResult;
import io.github.mfuerstenau.leia.protocol.knn.GetKnnGraphResult;
import io.github.mfuerstenau.leia.protocol.knn.KnnGraphLevel;
import io.github.mfuerstenau.leia.protocol.knn.KnnGraphNeighbour;
import io.github.mfuerstenau.leia.protocol.knn.KnnGraphNode;
import io.github.mfuerstenau.leia.protocol.knn.KnnGraphSegment;
import io.github.mfuerstenau.leia.protocol.knn.KnnGraphShape;
import io.github.mfuerstenau.leia.protocol.points.GetPointCellResult;
import io.github.mfuerstenau.leia.protocol.points.GetPointTreeResult;
import io.github.mfuerstenau.leia.protocol.points.LeafPoint;
import io.github.mfuerstenau.leia.protocol.points.PointCell;
import io.github.mfuerstenau.leia.protocol.points.PointSegment;
import io.github.mfuerstenau.leia.protocol.points.PointValue;
import io.github.mfuerstenau.leia.protocol.terms.GetPostingsResult;
import io.github.mfuerstenau.leia.protocol.terms.GetTermsResult;
import io.github.mfuerstenau.leia.protocol.terms.Posting;
import io.github.mfuerstenau.leia.protocol.terms.PostingsFilter;
import io.github.mfuerstenau.leia.protocol.terms.TermEntry;
import io.github.mfuerstenau.leia.tui.TestScreens;
import io.github.mfuerstenau.leia.tui.points.PointFormat;
import io.github.mfuerstenau.leia.tui.points.PointFormats;
import io.github.mfuerstenau.leia.tui.terms.Terms.Request;
import io.github.mfuerstenau.leia.tui.terms.Terms.TermHit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The tests of {@code terms.rs}, ported, and what the asynchronous calls add. */
class TermsTest {

    private static IndexField field(String name, int number, IndexOptions indexOptions) {
        return new IndexField(
                name,
                number,
                indexOptions,
                false,
                false,
                false,
                DocValuesType.NONE,
                0,
                0,
                0,
                Optional.empty(),
                Optional.empty(),
                Optional.of(new TermStats(1, 1, 1)));
    }

    /** {@code id} and {@code body} have postings, {@code rank} has none. */
    private static Terms terms() {
        return new Terms(List.of(
                field("id", 0, IndexOptions.DOCS),
                field("rank", 1, IndexOptions.NONE),
                field("body", 2, IndexOptions.DOCS_AND_FREQS_AND_POSITIONS)));
    }

    private static BytesValue text(String value) {
        return new BytesValue(value, false);
    }

    private static TermEntry entry(String term, int docFreq) {
        return new TermEntry(term, false, docFreq, docFreq * 2L);
    }

    private static GetTermsResult page(List<String> terms, String next) {
        return new GetTermsResult(
                terms.stream().map(term -> entry(term, 3)).toList(),
                Optional.ofNullable(next).map(TermsTest::text));
    }

    private static Posting posting(int docId) {
        return new Posting(docId, false, OptionalInt.of(2), Optional.empty());
    }

    private static GetPostingsResult postings(List<Integer> docIds, Integer nextDoc) {
        return new GetPostingsResult(
                5,
                10,
                docIds.stream().map(TermsTest::posting).toList(),
                nextDoc == null ? OptionalInt.empty() : OptionalInt.of(nextDoc));
    }

    private static Request.LoadTerms termsRequest(String field, String from, boolean more) {
        return new Request.LoadTerms(field, Optional.ofNullable(from).map(TermsTest::text), more);
    }

    private static Request.LoadPostings postingsRequest(String field, String term, int fromDoc, boolean more) {
        return new Request.LoadPostings(field, text(term), fromDoc, more, Optional.empty());
    }

    private static Optional<Request> hit(String term, int docId, int index) {
        return Optional.of(new Request.Document(new TermHit("id", text(term), docId, index, 5, OptionalInt.of(2))));
    }

    /** The terms of {@code id} and the postings of its first term, {@code a}, are in. */
    private static Terms loaded() {
        Terms terms = terms();
        assertThat(terms.initialRequest()).hasValue(termsRequest("id", null, false));
        assertThat(terms.termsLoaded(termsRequest("id", null, false), page(List.of("a", "b", "c"), "d")))
                .hasValue(postingsRequest("id", "a", 0, false));
        assertThat(terms.postingsLoaded(postingsRequest("id", "a", 0, false), postings(List.of(1, 4), 7)))
                .isEmpty();
        assertThat(terms.loading()).isFalse();
        return terms;
    }

    @Test
    void refusesAndLeavesOutTheKeysTheIndexLacks() {
        Terms terms = terms().withFeatures(Set.of(Feature.TERMS, Feature.POSTINGS));
        terms.initialRequest();
        terms.termsLoaded(termsRequest("id", null, false), page(List.of("a", "b", "c"), "d"));
        terms.postingsLoaded(postingsRequest("id", "a", 0, false), postings(List.of(1, 4), 7));

        assertThat(terms.keys()).contains("/ seek").doesNotContain("e export", "⏎ open doc");
        assertThat(terms.handleKey(key('e'))).hasValue(new Request.Unsupported("Export"));
        terms.handleKey(key(KeyCode.ENTER));
        terms.handleKey(key(KeyCode.ENTER));
        assertThat(terms.handleKey(key(KeyCode.ENTER))).hasValue(new Request.Unsupported("Open doc"));
        assertThat(loaded().keys()).contains("e export", "⏎ open doc");
    }

    private static String render(Terms terms) {
        return TestScreens.render(frame -> terms.render(frame, frame.area()), 100, 12);
    }

    @Test
    void listsTheFieldsWithPostingsTheTermsAndThePostings() {
        Terms terms = loaded();

        String screen = render(terms);
        assertThat(screen).contains("┃id", "┃body", "id (/ seeks)", "│a ", "3     6│", "Postings (5)", "1     2");
        assertThat(screen).doesNotContain("rank");
        // More terms and postings follow.
        assertThat(screen.chars().filter(c -> c == '…').count()).isEqualTo(2);
    }

    @Test
    void anotherFieldFetchesItsTerms() {
        Terms terms = loaded();

        assertThat(terms.handleKey(key(KeyCode.DOWN))).hasValue(termsRequest("body", null, false));
        assertThat(terms.handleKey(key(KeyCode.DOWN))).isEmpty();
    }

    @Test
    void fetchesTheNextPageAtTheLastRow() {
        Terms terms = loaded();
        terms.handleKey(key(KeyCode.RIGHT));

        assertThat(terms.handleKey(key(KeyCode.DOWN))).hasValue(postingsRequest("id", "b", 0, false));
        terms.postingsLoaded(postingsRequest("id", "b", 0, false), postings(List.of(2), null));
        assertThat(terms.handleKey(key(KeyCode.END))).hasValue(termsRequest("id", "d", true));
        // The page continues the list; the selection stays on "c", whose postings follow.
        assertThat(terms.termsLoaded(termsRequest("id", "d", true), page(List.of("d", "e"), null)))
                .hasValue(postingsRequest("id", "c", 0, false));
        terms.postingsLoaded(postingsRequest("id", "c", 0, false), postings(List.of(3), null));
        assertThat(terms.handleKey(key(KeyCode.END))).hasValue(postingsRequest("id", "e", 0, false));
        assertThat(render(terms)).doesNotContain("…");
    }

    @Test
    void fetchesMorePostingsAtTheLastRow() {
        Terms terms = loaded();
        terms.handleKey(key(KeyCode.TAB));
        terms.handleKey(key(KeyCode.TAB));

        assertThat(terms.handleKey(key(KeyCode.DOWN))).hasValue(postingsRequest("id", "a", 7, true));
        assertThat(terms.postingsLoaded(postingsRequest("id", "a", 7, true), postings(List.of(7), null)))
                .isEmpty();
        assertThat(terms.handleKey(key(KeyCode.END))).isEmpty();
        assertThat(terms.handleKey(key(KeyCode.ENTER))).isEqualTo(hit("a", 7, 2));
    }

    @Test
    void enterShowsTheSelectedDocument() {
        Terms terms = loaded();
        assertThat(terms.handleKey(key(KeyCode.ENTER))).isEmpty();
        assertThat(terms.handleKey(key(KeyCode.ENTER))).isEmpty();
        terms.handleKey(key(KeyCode.DOWN));

        assertThat(terms.handleKey(key(KeyCode.ENTER))).isEqualTo(hit("a", 4, 1));
    }

    @Test
    void stepsThroughThePostingsAcrossPages() {
        Terms terms = loaded();

        assertThat(terms.step(false)).isEmpty();
        assertThat(terms.step(true)).isEqualTo(hit("a", 4, 1));
        assertThat(terms.step(true)).hasValue(postingsRequest("id", "a", 7, true));
        assertThat(terms.postingsLoaded(postingsRequest("id", "a", 7, true), postings(List.of(7), null)))
                .isEqualTo(hit("a", 7, 2));
        assertThat(terms.step(true)).isEmpty();
        assertThat(terms.step(false)).isEqualTo(hit("a", 4, 1));
    }

    @Test
    void seeksWhileTyping() {
        Terms terms = loaded();

        assertThat(terms.handleKey(key('/'))).isEmpty();
        assertThat(terms.prompting()).isTrue();
        assertThat(terms.handleKey(key('q'))).hasValue(termsRequest("id", "q", false));
        assertThat(terms.termsLoaded(termsRequest("id", "q", false), page(List.of("quick"), null)))
                .hasValue(postingsRequest("id", "quick", 0, false));
        assertThat(render(terms)).contains("id from q▏");

        assertThat(terms.handleKey(key(KeyCode.ENTER))).isEmpty();
        assertThat(terms.prompting()).isFalse();
        assertThat(render(terms)).contains("id from q (/ seeks)");

        // An empty text starts from the first term again.
        terms.handleKey(key('/'));
        assertThat(terms.handleKey(key(KeyCode.BACKSPACE))).hasValue(termsRequest("id", null, false));
        terms.handleKey(key(KeyCode.ESCAPE));
        terms.termsLoaded(termsRequest("id", null, false), page(List.of(), null));
        assertThat(render(terms)).contains("The field has no terms.");
    }

    @Test
    void showsWhyAPageFailedAndDoesNotFetchItAgain() {
        Terms terms = loaded();
        terms.handleKey(key(KeyCode.RIGHT));
        terms.handleKey(key(KeyCode.DOWN));
        terms.postingsLoaded(postingsRequest("id", "b", 0, false), postings(List.of(2), null));
        assertThat(terms.handleKey(key(KeyCode.END))).hasValue(termsRequest("id", "d", true));

        assertThat(terms.termsFailed(termsRequest("id", "d", true), "broken"))
                .hasValue(postingsRequest("id", "c", 0, false));
        terms.postingsFailed(postingsRequest("id", "c", 0, false), "gone");

        String screen = render(terms);
        assertThat(screen).contains("Cannot read more: broken", "Cannot read the postings: gone");
        assertThat(terms.handleKey(key(KeyCode.END))).isEmpty();
    }

    @Test
    void anIndexWithoutPostingsHasNothingToFetch() {
        Terms terms = new Terms(List.of(field("rank", 0, IndexOptions.NONE)));

        assertThat(terms.initialRequest()).isEmpty();
        assertThat(terms.handleKey(key('/'))).isEmpty();
        assertThat(terms.prompting()).isFalse();
        assertThat(render(terms)).contains("No field has postings, points or vectors.");
    }

    @Test
    void browsesATermOfAnotherField() {
        Terms terms = loaded();

        assertThat(terms.browse("body", text("quick"))).hasValue(termsRequest("body", "quick", false));
        assertThat(terms.termsLoaded(termsRequest("body", "quick", false), page(List.of("quick", "red"), null)))
                .hasValue(new Request.LoadPostings("body", text("quick"), 0, false, Optional.empty()));
        terms.postingsLoaded(
                new Request.LoadPostings("body", text("quick"), 0, false, Optional.empty()),
                postings(List.of(9), null));
        assertThat(render(terms)).contains("body from quick (/ seeks)");
        // The postings have the focus.
        assertThat(terms.handleKey(key(KeyCode.ENTER)))
                .hasValue(new Request.Document(new TermHit("body", text("quick"), 9, 0, 5, OptionalInt.of(2))));
        assertThat(terms.browse("rank", text("1"))).isEmpty();
    }

    @Test
    void marksThePostingsInTheFilterAndBShowsOnlyThose() {
        Terms terms = loaded();
        assertThat(terms.filtered()).isFalse();
        assertThat(terms.handleKey(key('b'))).isEmpty();

        terms.setFilter(OptionalLong.of(9));
        PostingsFilter marked = new PostingsFilter(9, false);
        Request.LoadPostings markedRequest = new Request.LoadPostings("id", text("a"), 0, false, Optional.of(marked));
        assertThat(terms.initialRequest()).hasValue(markedRequest);
        List<Posting> inFilter = new ArrayList<>(Arrays.asList(
                new Posting(1, false, OptionalInt.of(2), Optional.of(false)),
                new Posting(4, false, OptionalInt.of(2), Optional.of(true))));
        assertThat(terms.postingsLoaded(markedRequest, new GetPostingsResult(5, 10, inFilter, OptionalInt.empty())))
                .isEmpty();
        String screen = render(terms);
        assertThat(screen).contains("Postings (5) · • filter", "• 4");

        PostingsFilter only = new PostingsFilter(9, true);
        Request.LoadPostings onlyRequest = new Request.LoadPostings("id", text("a"), 0, false, Optional.of(only));
        assertThat(terms.handleKey(key('b'))).hasValue(onlyRequest);
        assertThat(terms.onlyFiltered()).isTrue();
        terms.postingsLoaded(onlyRequest, postings(List.of(), null));
        screen = render(terms);
        assertThat(screen).contains("Postings (5) · filter only", "No postings in the filter.");

        // Without the filter, all postings are shown again, unmarked.
        terms.setFilter(OptionalLong.empty());
        assertThat(terms.onlyFiltered()).isFalse();
        assertThat(terms.initialRequest()).hasValue(postingsRequest("id", "a", 0, false));
    }

    @Test
    void eExportsTheSelectedField() {
        Terms terms = loaded();

        assertThat(terms.handleKey(key('e'))).hasValue(new Request.Export("id"));
    }

    // What the asynchronous calls add.

    @Test
    void aSoughtPageThatIsNoLongerWantedIsDropped() {
        Terms terms = loaded();
        terms.handleKey(key('/'));
        assertThat(terms.handleKey(key('q'))).hasValue(termsRequest("id", "q", false));
        assertThat(terms.handleKey(key('u'))).hasValue(termsRequest("id", "qu", false));

        assertThat(terms.termsLoaded(termsRequest("id", "q", false), page(List.of("quack"), null)))
                .isEmpty();
        assertThat(render(terms)).contains("┃a ");
        assertThat(terms.termsLoaded(termsRequest("id", "qu", false), page(List.of("quick"), null)))
                .hasValue(postingsRequest("id", "quick", 0, false));
        terms.handleKey(key(KeyCode.ENTER));
        assertThat(render(terms)).contains("id from qu (/ seeks)", "┃quick").doesNotContain("quack");
    }

    @Test
    void postingsOfATermThatIsNoLongerSelectedAreDropped() {
        Terms terms = loaded();
        terms.handleKey(key(KeyCode.RIGHT));
        assertThat(terms.handleKey(key(KeyCode.DOWN))).hasValue(postingsRequest("id", "b", 0, false));
        // Back on "a", whose postings are shown.
        assertThat(terms.handleKey(key(KeyCode.UP))).isEmpty();

        assertThat(terms.postingsLoaded(postingsRequest("id", "b", 0, false), postings(List.of(2), null)))
                .isEmpty();
        assertThat(render(terms)).contains("1     2", "4     2").doesNotContain("2     2");
        assertThat(terms.loading()).isFalse();
    }

    @Test
    void aFetchUnderWayIsNotAskedForAgain() {
        Terms terms = loaded();
        terms.handleKey(key(KeyCode.RIGHT));

        assertThat(terms.handleKey(key(KeyCode.END))).hasValue(termsRequest("id", "d", true));
        assertThat(terms.loading()).isTrue();
        assertThat(terms.handleKey(key(KeyCode.END))).isEmpty();
        assertThat(terms.termsFailed(termsRequest("id", "d", true), "broken"))
                .hasValue(postingsRequest("id", "c", 0, false));
        // Back and forth while the postings are under way.
        assertThat(terms.handleKey(key(KeyCode.UP))).hasValue(postingsRequest("id", "b", 0, false));
        assertThat(terms.handleKey(key(KeyCode.DOWN))).isEmpty();
    }

    @Test
    void aNextPageOfAReplacedListIsDropped() {
        Terms terms = loaded();
        terms.handleKey(key('/'));
        assertThat(terms.handleKey(key('x'))).hasValue(termsRequest("id", "x", false));
        terms.handleKey(key(KeyCode.ENTER));
        // The shown list is still the old one, so End asks for its next page.
        assertThat(terms.handleKey(key(KeyCode.END))).hasValue(termsRequest("id", "d", true));

        assertThat(terms.termsLoaded(termsRequest("id", "x", false), page(List.of("x", "y"), null)))
                .hasValue(postingsRequest("id", "x", 0, false));
        assertThat(terms.termsLoaded(termsRequest("id", "d", true), page(List.of("d", "e"), null)))
                .isEmpty();
        assertThat(render(terms)).contains("┃x ", "┃y ").doesNotContain("┃d ", "┃e ");
    }

    @Test
    void aBrowsedPageStaysWantedWhileItIsUnderWay() {
        Terms terms = loaded();
        assertThat(terms.browse("body", text("quick"))).hasValue(termsRequest("body", "quick", false));

        // A key before the reply doesn't ask for the field from its first term.
        terms.handleKey(key(KeyCode.LEFT));
        assertThat(terms.handleKey(key(KeyCode.DOWN))).isEmpty();
        assertThat(terms.termsLoaded(termsRequest("body", "quick", false), page(List.of("quick"), null)))
                .hasValue(new Request.LoadPostings("body", text("quick"), 0, false, Optional.empty()));
    }

    private static IndexField pointField(String name, int number, IndexOptions indexOptions, int bytes) {
        return new IndexField(
                name,
                number,
                indexOptions,
                false,
                false,
                false,
                DocValuesType.NONE,
                1,
                bytes,
                0,
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static PointValue intPoint(int value) {
        return new PointValue(List.of(String.format("%08x", value ^ Integer.MIN_VALUE)));
    }

    private static PointCell cell(int min, int max, long size, boolean leaf) {
        return new PointCell(intPoint(min), intPoint(max), size, leaf);
    }

    /** {@code id} has postings; {@code code} postings and int points; {@code price} only double points. */
    private static Terms pointTerms() {
        return new Terms(List.of(
                field("id", 0, IndexOptions.DOCS),
                pointField("code", 1, IndexOptions.DOCS, 4),
                pointField("price", 2, IndexOptions.NONE, 8)));
    }

    /** {@code code}'s points row, with its tree in: one segment of 3 points, a root with two leaves. */
    private static Terms codeTree() {
        Terms terms = pointTerms();
        terms.initialRequest();
        terms.handleKey(key(KeyCode.DOWN));
        assertThat(terms.handleKey(key(KeyCode.DOWN))).hasValue(new Request.LoadPointTree("code", 1));
        terms.pointTreeLoaded(
                new Request.LoadPointTree("code", 1),
                new GetPointTreeResult(
                        "code",
                        1,
                        1,
                        4,
                        3,
                        3,
                        Optional.of(intPoint(-5)),
                        Optional.of(intPoint(40)),
                        List.of(new PointSegment(0, 0, Optional.of("_0"), 0, 3, 3, 3, intPoint(-5), intPoint(40)))));
        return terms;
    }

    @Test
    void listsPointFieldsOnARowOfTheirOwn() {
        Terms terms = pointTerms();

        String screen = render(terms);
        assertThat(screen).contains("id", "code", "code pt 1×4", "price pt 1×8");
        assertThat(screen.indexOf("code pt 1×4")).isGreaterThan(screen.indexOf("code "));
    }

    @Test
    void expandsASegmentDownToALeafAndShowsItsPoints() {
        Terms terms = codeTree();
        assertThat(render(terms)).contains("code · 1 segment · 3 points in 3 docs · as int", "▸ _0  3 pts  -5 … 40");

        terms.handleKey(key(KeyCode.RIGHT));
        Request.LoadPointCell root = new Request.LoadPointCell("code", 1, 0, List.of());
        assertThat(terms.handleKey(key(KeyCode.ENTER))).hasValue(root);
        // Asked for already.
        assertThat(terms.handleKey(key(KeyCode.ENTER))).isEmpty();
        terms.pointCellLoaded(
                root,
                new GetPointCellResult(
                        cell(-5, 40, 3, false),
                        List.of(cell(-5, 7, 2, true), cell(40, 40, 1, true)),
                        List.of(),
                        false));
        assertThat(render(terms)).contains("▾ _0  3 pts", "  • 2 pts  -5 … 7", "  • 1 pts  40 … 40");

        terms.handleKey(key(KeyCode.DOWN));
        Request.LoadPointCell leaf = new Request.LoadPointCell("code", 1, 0, List.of(0));
        assertThat(terms.handleKey(key(KeyCode.ENTER))).hasValue(leaf);
        terms.pointCellLoaded(
                leaf,
                new GetPointCellResult(
                        cell(-5, 7, 2, true),
                        List.of(),
                        List.of(new LeafPoint(3, false, intPoint(7)), new LeafPoint(8, true, intPoint(-5))),
                        false));
        String screen = render(terms);
        assertThat(screen).contains("Points (2)", "3  7", "8  -5");

        // The focus is on the points now: Enter opens the selected one's document.
        terms.handleKey(key(KeyCode.DOWN));
        assertThat(terms.handleKey(key(KeyCode.ENTER))).hasValue(new Request.PointDocument("code", 8));

        // Collapsing the segment hides its cells.
        terms.handleKey(key(KeyCode.LEFT));
        terms.handleKey(key(KeyCode.UP));
        assertThat(terms.handleKey(key(KeyCode.ENTER))).isEmpty();
        assertThat(render(terms)).contains("▸ _0").doesNotContain("• 2 pts");
    }

    @Test
    void tCyclesThePointFormatSharedWithTheDocumentsScreen() {
        PointFormats formats = new PointFormats();
        Terms terms = codeTree().withPointFormats(formats);

        terms.handleKey(key('t'));

        assertThat(render(terms)).contains("as float");
        assertThat(formats.of(pointField("code", 1, IndexOptions.DOCS, 4))).isEqualTo(PointFormat.FLOAT);
    }

    @Test
    void seekAndExportSayTheyWorkOnTermsOnly() {
        Terms terms = codeTree();

        assertThat(terms.handleKey(key('/'))).isEmpty();
        assertThat(terms.prompting()).isFalse();
        assertThat(render(terms)).contains("/ seeks terms only");
        assertThat(terms.handleKey(key('e'))).isEmpty();
    }

    @Test
    void sSearchesTheSelectedTermOrThePostingsTerm() {
        Terms terms = loaded();
        assertThat(terms.keys()).contains("s search");
        // On the fields column, the selected term.
        assertThat(terms.handleKey(key('s'))).hasValue(new Request.Search("id", text("a")));

        terms.handleKey(key(KeyCode.RIGHT));
        terms.handleKey(key(KeyCode.DOWN));
        assertThat(terms.handleKey(key('s'))).hasValue(new Request.Search("id", text("b")));
        // On the postings column, the postings' term, which is a's until b's are in.
        terms.handleKey(key(KeyCode.RIGHT));
        assertThat(terms.handleKey(key('s'))).hasValue(new Request.Search("id", text("a")));
    }

    @Test
    void sNeedsSearchAndWorksOnTermsOnly() {
        Terms terms = terms().withFeatures(Set.of(Feature.TERMS, Feature.POSTINGS));
        assertThat(terms.keys()).doesNotContain("s search");
        assertThat(terms.handleKey(key('s'))).hasValue(new Request.Unsupported("Search term"));

        // A runtime searches a term with term queries alone (ADR 0058).
        Terms runtime = loaded().withFeatures(Set.of(Feature.TERMS, Feature.POSTINGS, Feature.TERM_SEARCH));
        assertThat(runtime.keys()).contains("s search");
        assertThat(runtime.handleKey(key('s'))).hasValue(new Request.Search("id", text("a")));

        Terms points = codeTree();
        assertThat(points.handleKey(key('s'))).isEmpty();
        assertThat(render(points)).contains("s searches terms only");
    }

    @Test
    void leavingTheScreenEndsTheNote() {
        Terms terms = codeTree();
        terms.handleKey(key('s'));
        assertThat(render(terms)).contains("s searches terms only");

        terms.leave();
        assertThat(render(terms)).doesNotContain("terms only");
    }

    @Test
    void thePointTreeTitleFitsItsPanel() {
        Terms terms = codeTree();
        terms.handleKey(key('s'));

        // Too narrow for the whole title: it leaves out the segments, inside the corners.
        String wide = TestScreens.render(frame -> terms.render(frame, frame.area()), 120, 12);
        assertThat(wide.lines().findFirst().orElseThrow())
                .contains("┌ code · s searches terms only · 3 points in 3 docs · as int ─")
                .doesNotContain("segment");
        // Narrower than the note: the title is cut and the corner stays.
        String narrow = TestScreens.render(frame -> terms.render(frame, frame.area()), 60, 12);
        assertThat(narrow.lines().findFirst().orElseThrow()).contains("┓┌ c", "┐┌ Points");
    }

    @Test
    void restoreShowsWhatTheMarkLeftWithoutAFetch() {
        Terms terms = loaded();
        terms.handleKey(key(KeyCode.RIGHT));
        terms.handleKey(key(KeyCode.DOWN));
        terms.postingsLoaded(postingsRequest("id", "b", 0, false), postings(List.of(2, 9), null));
        Terms.Mark mark = terms.mark();

        assertThat(terms.show("body", Optional.empty(), false)).hasValue(termsRequest("body", null, false));
        terms.termsLoaded(termsRequest("body", null, false), page(List.of("x", "y"), null));
        terms.postingsLoaded(postingsRequest("body", "x", 0, false), postings(List.of(3), null));
        assertThat(render(terms)).contains("body (/ seeks)");

        terms.restore(mark);
        assertThat(terms.initialRequest()).isEmpty();
        assertThat(render(terms)).contains("id (/ seeks)", "9").doesNotContain("body (/ seeks)");
        // The selection is on b, with the focus on the terms.
        assertThat(terms.handleKey(key(KeyCode.UP))).hasValue(postingsRequest("id", "a", 0, false));
    }

    @Test
    void showOpensAFieldsTermsOrItsPoints() {
        Terms terms = pointTerms();
        terms.initialRequest();

        // Points: the field's points row, which fetches its tree.
        assertThat(terms.show("code", Optional.empty(), true)).hasValue(new Request.LoadPointTree("code", 1));
        // Terms without a term: the field's terms from the first one, with the focus on them.
        assertThat(terms.show("code", Optional.empty(), false)).hasValue(termsRequest("code", null, false));
        terms.termsLoaded(termsRequest("code", null, false), page(List.of("x", "y"), null));
        terms.handleKey(key(KeyCode.DOWN));
        assertThat(terms.handleKey(key('s'))).hasValue(new Request.Search("code", text("y")));
        // A row the screen doesn't have.
        assertThat(terms.show("price", Optional.empty(), false)).isEmpty();
    }

    @Test
    void repliesOfAnotherTreeAreDropped() {
        Terms terms = codeTree();
        terms.handleKey(key(KeyCode.RIGHT));
        terms.handleKey(key(KeyCode.ENTER));

        // The tree of the reopened index is serial 2: a reply for serial 1 doesn't fit it.
        Terms reopened = terms.reopened(List.of(
                field("id", 0, IndexOptions.DOCS),
                pointField("code", 1, IndexOptions.DOCS, 4),
                pointField("price", 2, IndexOptions.NONE, 8)));
        assertThat(reopened.initialRequest()).hasValue(new Request.LoadPointTree("code", 2));
        reopened.pointCellLoaded(
                new Request.LoadPointCell("code", 1, 0, List.of()),
                new GetPointCellResult(cell(-5, 40, 3, true), List.of(), List.of(), false));
        assertThat(render(reopened)).doesNotContain("Points (");
    }

    private static IndexField vectorField(String name, int number) {
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
                8,
                Optional.of(VectorEncoding.FLOAT32),
                Optional.of(VectorSimilarity.COSINE),
                Optional.empty());
    }

    private static KnnGraphNode node(int ord, int docId, int topLevel) {
        return new KnnGraphNode(ord, docId, false, topLevel);
    }

    private static final KnnGraphNode ENTRY = node(17, 18, 1);

    /**
     * {@code id} has postings, {@code embedding} vectors, with its graphs in: {@code _0} with 20
     * vectors on two levels, entry ord 17, and {@code _1} without a graph.
     */
    private static Terms graphTerms() {
        Terms terms = new Terms(List.of(field("id", 0, IndexOptions.DOCS), vectorField("embedding", 1)));
        terms.initialRequest();
        assertThat(terms.handleKey(key(KeyCode.DOWN))).hasValue(new Request.LoadKnnGraph("embedding", 1));
        assertThat(terms.knnGraphLoaded(
                        new Request.LoadKnnGraph("embedding", 1),
                        new GetKnnGraphResult(
                                "embedding",
                                8,
                                VectorEncoding.FLOAT32,
                                VectorSimilarity.COSINE,
                                List.of(
                                        new KnnGraphSegment(
                                                0,
                                                0,
                                                Optional.of("_0"),
                                                0,
                                                21,
                                                20,
                                                Optional.of(
                                                        new KnnGraphShape(List.of(20, 2), ENTRY, OptionalInt.of(16)))),
                                        new KnnGraphSegment(1, 0, Optional.of("_1"), 21, 5, 5, Optional.empty())))))
                .isEmpty();
        return terms;
    }

    private static GetKnnGraphNodeResult neighbours(KnnGraphNode node, List<KnnGraphLevel> levels) {
        return new GetKnnGraphNodeResult(0, node, levels);
    }

    @Test
    void listsVectorFieldsOnARowOfTheirOwnWithTheirSegments() {
        Terms terms = graphTerms();

        assertThat(terms.showsGraph()).isTrue();
        assertThat(TestScreens.render(frame -> terms.render(frame, frame.area()), 140, 12))
                .contains(
                        "embedding hnsw 8d",
                        "embedding · 2 segments · 25 vectors · cosine",
                        "▸ _0  20 vectors · 2 levels · M 16",
                        "_1  5 vectors · no graph",
                        "⏎ on a node shows its",
                        "neighbours.");
        assertThat(terms.keys()).contains("< back", "d open doc");
    }

    @Test
    void expandsASegmentAndALevelAndWalksFromNodeToNode() {
        Terms terms = graphTerms();
        terms.handleKey(key(KeyCode.RIGHT));
        assertThat(terms.handleKey(key(KeyCode.ENTER))).isEmpty();
        assertThat(render(terms)).contains("▾ _0", "entry  ord 17 · doc 18", "▸ L1  2 nodes", "▸ L0  20 nodes");

        // L0 fetches its first page.
        terms.handleKey(key(KeyCode.DOWN));
        terms.handleKey(key(KeyCode.DOWN));
        terms.handleKey(key(KeyCode.DOWN));
        Request.LoadKnnNodes page = new Request.LoadKnnNodes("embedding", 1, 0, 0, 0);
        assertThat(terms.handleKey(key(KeyCode.ENTER))).hasValue(page);
        assertThat(terms.handleKey(key(KeyCode.ENTER))).isEmpty();
        terms.knnNodesLoaded(page, new GetKnnGraphNodesResult(20, List.of(node(0, 0, 0), node(1, 2, 1))));
        assertThat(render(terms))
                .contains("▾ L0  20 nodes", "ord 0 · doc 0", "ord 1 · doc 2 · top L1", "⏎ more (2 of 20)");

        // A node's neighbours, by level; the focus moves to them.
        terms.handleKey(key(KeyCode.DOWN));
        terms.handleKey(key(KeyCode.DOWN));
        Request.LoadKnnNode open = Request.LoadKnnNode.ofOrd("embedding", 1, 0, 1, false);
        assertThat(terms.handleKey(key(KeyCode.ENTER))).hasValue(open);
        terms.knnNodeLoaded(
                open,
                neighbours(
                        node(1, 2, 1),
                        List.of(
                                new KnnGraphLevel(1, List.of(new KnnGraphNeighbour(ENTRY, 0.75f))),
                                new KnnGraphLevel(
                                        0,
                                        List.of(
                                                new KnnGraphNeighbour(node(0, 0, 0), 0.9312f),
                                                new KnnGraphNeighbour(new KnnGraphNode(5, 6, true, 0), 0.5f))))));
        assertThat(render(terms))
                .contains(
                        " ord 1 ",
                        "Lvl    Ord   Doc  Top   Score",
                        "node     1     2  L1",
                        "L1      17    18  L1   0.7500",
                        "L0       0     0  L0   0.9312",
                        "         5     6  L0   0.5000");

        // Enter walks on to the selected neighbour; d opens its document.
        terms.handleKey(key(KeyCode.DOWN));
        assertThat(terms.handleKey(key('d'))).hasValue(new Request.VectorDocument("embedding", 0));
        Request.LoadKnnNode walk = Request.LoadKnnNode.ofOrd("embedding", 1, 0, 0, true);
        assertThat(terms.handleKey(key(KeyCode.ENTER))).hasValue(walk);
        terms.knnNodeLoaded(
                walk,
                neighbours(
                        node(0, 0, 0),
                        List.of(new KnnGraphLevel(0, List.of(new KnnGraphNeighbour(node(1, 2, 1), 0.9312f))))));
        assertThat(render(terms))
                .contains(" ord 1 › 0 ", "node     0     0  L0")
                .doesNotContain("L1      17");

        // < goes back, without a fetch, to where the walk came from.
        assertThat(terms.handleKey(key('<'))).isEmpty();
        assertThat(render(terms)).contains(" ord 1 ", "L1      17    18").doesNotContain("› 0");
        assertThat(terms.handleKey(key('<'))).isEmpty();
        assertThat(render(terms)).contains("the walk's first node");
    }

    @Test
    void theNeighboursColumnIsNoWiderThanTheGraphsNeed() {
        Terms terms = graphTerms();

        // Ordinals and documents of four digits fit the postings' width, which leaves the rest to the
        // middle column.
        String top = TestScreens.render(frame -> terms.render(frame, frame.area()), 80, 12)
                .lines()
                .findFirst()
                .orElseThrow();
        assertThat(top.substring(top.indexOf("┌ Neighbours"))).hasSize(34);
    }

    @Test
    void aSegmentWithoutAGraphSaysSo() {
        Terms terms = graphTerms();
        terms.handleKey(key(KeyCode.RIGHT));
        terms.handleKey(key(KeyCode.DOWN));

        assertThat(terms.handleKey(key(KeyCode.ENTER))).isEmpty();
        assertThat(render(terms)).contains("no graph in this segment");
        assertThat(terms.handleKey(key('/'))).isEmpty();
        assertThat(render(terms)).contains("/ seeks terms only");
    }

    @Test
    void showNodeOpensADocumentsNodeOnceTheGraphsAreIn() {
        Terms terms = new Terms(List.of(field("id", 0, IndexOptions.DOCS), vectorField("embedding", 1)));
        terms.initialRequest();

        assertThat(terms.showNode("embedding", 42)).hasValue(new Request.LoadKnnGraph("embedding", 1));
        Optional<Request> next = terms.knnGraphLoaded(
                new Request.LoadKnnGraph("embedding", 1),
                new GetKnnGraphResult("embedding", 8, VectorEncoding.FLOAT32, VectorSimilarity.COSINE, List.of()));
        Request.LoadKnnNode byDoc = Request.LoadKnnNode.ofDoc("embedding", 1, 42);
        assertThat(next).hasValue(byDoc);
        terms.knnNodeFailed(byDoc, "Invalid params: document 42 has no vector");
        assertThat(render(terms)).contains("Cannot read the node: Invalid");

        // With the graphs in, the node is asked for at once.
        assertThat(terms.showNode("embedding", 7)).hasValue(Request.LoadKnnNode.ofDoc("embedding", 1, 7));
        assertThat(terms.showNode("id", 7)).isEmpty();
    }

    @Test
    void theGraphNeedsItsFeature() {
        Terms terms = new Terms(List.of(field("id", 0, IndexOptions.DOCS), vectorField("embedding", 1)))
                .withFeatures(Set.of(Feature.TERMS, Feature.POSTINGS, Feature.KNN_VECTORS));
        terms.initialRequest();

        assertThat(terms.handleKey(key(KeyCode.DOWN))).isEmpty();
        assertThat(render(terms)).contains("doesn't", "read HNSW graphs.");
        terms.handleKey(key(KeyCode.RIGHT));
        assertThat(terms.handleKey(key(KeyCode.ENTER))).hasValue(new Request.Unsupported("HNSW graph"));
        assertThat(terms.keys()).doesNotContain("< back");
    }

    @Test
    void aLongWalkLeavesOutItsStart() {
        assertThat(Terms.walkTitle(List.of("1", "2", "3"), 30)).isEqualTo(" ord 1 › 2 › 3 ");
        assertThat(Terms.walkTitle(List.of("100", "200", "300", "400"), 19)).isEqualTo(" ord … › 300 › 400 ");
    }
}
