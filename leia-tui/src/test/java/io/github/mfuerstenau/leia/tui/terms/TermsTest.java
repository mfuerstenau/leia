package io.github.mfuerstenau.leia.tui.terms;

import static io.github.mfuerstenau.leia.tui.TestScreens.key;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tamboui.tui.event.KeyCode;
import io.github.mfuerstenau.leia.protocol.document.BytesValue;
import io.github.mfuerstenau.leia.protocol.index.DocValuesType;
import io.github.mfuerstenau.leia.protocol.index.IndexField;
import io.github.mfuerstenau.leia.protocol.index.IndexOptions;
import io.github.mfuerstenau.leia.protocol.index.TermStats;
import io.github.mfuerstenau.leia.protocol.terms.GetPostingsResult;
import io.github.mfuerstenau.leia.protocol.terms.GetTermsResult;
import io.github.mfuerstenau.leia.protocol.terms.Posting;
import io.github.mfuerstenau.leia.protocol.terms.PostingsFilter;
import io.github.mfuerstenau.leia.protocol.terms.TermEntry;
import io.github.mfuerstenau.leia.tui.TestScreens;
import io.github.mfuerstenau.leia.tui.terms.Terms.Request;
import io.github.mfuerstenau.leia.tui.terms.Terms.TermHit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
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
        assertThat(render(terms)).contains("No field has postings.");
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
}
