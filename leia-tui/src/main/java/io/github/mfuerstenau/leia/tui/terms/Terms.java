package io.github.mfuerstenau.leia.tui.terms;

import static io.github.mfuerstenau.leia.tui.widgets.Panels.highlight;
import static io.github.mfuerstenau.leia.tui.widgets.Panels.panel;
import static io.github.mfuerstenau.leia.tui.widgets.Panels.right;
import static io.github.mfuerstenau.leia.tui.widgets.Panels.tailRow;

import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Layout;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.Overflow;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Text;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.paragraph.Paragraph;
import dev.tamboui.widgets.table.Cell;
import dev.tamboui.widgets.table.Row;
import dev.tamboui.widgets.table.Table;
import dev.tamboui.widgets.table.TableState;
import io.github.mfuerstenau.leia.protocol.document.BytesValue;
import io.github.mfuerstenau.leia.protocol.index.IndexField;
import io.github.mfuerstenau.leia.protocol.index.IndexOptions;
import io.github.mfuerstenau.leia.protocol.terms.GetPostingsResult;
import io.github.mfuerstenau.leia.protocol.terms.GetTermsResult;
import io.github.mfuerstenau.leia.protocol.terms.Posting;
import io.github.mfuerstenau.leia.protocol.terms.PostingsFilter;
import io.github.mfuerstenau.leia.protocol.terms.TermEntry;
import io.github.mfuerstenau.leia.tui.text.TextFormat;
import io.github.mfuerstenau.leia.tui.widgets.Selection;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;

/**
 * The terms screen: the fields with postings, the terms of the selected field and the postings of
 * the selected term, side by side (ADR 0007). Terms and postings come in pages, and the next page is
 * fetched once the selection reaches the last row. Enter on a posting shows its document on the
 * documents screen. With a filter on the documents screen, the postings in it are marked, or {@code
 * b} shows only those (ADR 0032).
 *
 * <p>The Rust TUI got each reply before the next key; here a reply can come after the user moved
 * on. So a fetch that is under way is not asked for again, and a reply that no longer fits what the
 * screen shows is dropped: a first page of terms that isn't the last one asked for, postings of a
 * term that isn't selected any more, and a next page of a list that was replaced. The fetch for what
 * the screen shows now has always been asked for, and the backend answers in order, so its reply
 * comes last.
 */
public final class Terms {

    /** How many terms one {@code getTerms} call fetches. */
    public static final int TERMS_PAGE = 100;

    /** How many postings one {@code getPostings} call fetches. */
    public static final int POSTINGS_PAGE = 100;

    /** The fields column is at most this wide, borders included. */
    private static final int FIELDS_MAX_WIDTH = 32;

    /** Wide enough for the title of the postings in a filter (ADR 0032). */
    private static final int POSTINGS_WIDTH = 34;

    private static final int COLUMN_SPACING = 2;

    /** What the caller has to fetch or show for the terms screen. */
    public sealed interface Request {

        /**
         * A page of the terms of {@code field} from {@code from} on, reported back with {@link
         * #termsLoaded} or {@link #termsFailed}. With {@code more}, it continues the shown terms;
         * otherwise it replaces them.
         */
        record LoadTerms(String field, Optional<BytesValue> from, boolean more) implements Request {}

        /**
         * A page of the postings of a term from {@code fromDoc} on, reported back with {@link
         * #postingsLoaded} or {@link #postingsFailed}. With {@code more}, it continues the shown
         * postings; otherwise it replaces them. {@code filter} is the filter to read them through.
         */
        record LoadPostings(String field, BytesValue term, int fromDoc, boolean more, Optional<PostingsFilter> filter)
                implements Request {}

        /** Show this document on the documents screen. */
        record Document(TermHit hit) implements Request {}

        /** Ask where to export the terms of this field to (ADR 0022). */
        record Export(String field) implements Request {}
    }

    /**
     * A document that contains a term: a posting, as the documents screen shows it.
     *
     * @param index the posting's place among the term's postings, from 0
     * @param freq how often the term occurs in the document; empty if frequencies are not indexed
     */
    public record TermHit(String field, BytesValue term, int docId, int index, int docFreq, OptionalInt freq) {}

    /** The columns, from left to right. */
    private enum Column {
        FIELDS,
        TERMS,
        POSTINGS
    }

    /** The terms of the selected field, as far as they are fetched. */
    private static final class TermList {

        final String field;

        /** The text the list starts from, if it was sought. */
        final Optional<String> from;

        final List<TermEntry> entries;

        /** Where the next page starts; empty after the last term. */
        Optional<BytesValue> next;

        /** Why the last page could not be fetched; no more pages are fetched then. */
        Optional<String> error = Optional.empty();

        int selected;
        final TableState table = new TableState();

        TermList(String field, Optional<BytesValue> from, List<TermEntry> entries, Optional<BytesValue> next) {
            this.field = field;
            this.from = from.map(term -> TextFormat.bytesText(term.value(), term.binary()));
            this.entries = new ArrayList<>(entries);
            this.next = next;
        }

        Optional<BytesValue> selectedTerm() {
            if (selected >= entries.size()) {
                return Optional.empty();
            }
            TermEntry entry = entries.get(selected);
            return Optional.of(new BytesValue(entry.term(), entry.binary()));
        }
    }

    /** The postings of the selected term, as far as they are fetched. */
    private static final class PostingList {

        final String field;
        final BytesValue term;

        /** The filter the postings were read through. */
        final Optional<PostingsFilter> filter;

        int docFreq;
        final List<Posting> postings;

        /** Where the next page starts; empty after the last posting. */
        OptionalInt nextDoc;

        /** Why the last page could not be fetched; no more pages are fetched then. */
        Optional<String> error = Optional.empty();

        int selected;
        final TableState table = new TableState();

        /** Whether the posting after the last one is to be shown once the next page is in. */
        boolean stepPending;

        PostingList(
                String field,
                BytesValue term,
                Optional<PostingsFilter> filter,
                int docFreq,
                List<Posting> postings,
                OptionalInt nextDoc) {
            this.field = field;
            this.term = term;
            this.filter = filter;
            this.docFreq = docFreq;
            this.postings = new ArrayList<>(postings);
            this.nextDoc = nextDoc;
        }

        boolean isOf(String field, BytesValue term, Optional<PostingsFilter> filter) {
            return this.field.equals(field) && this.term.equals(term) && this.filter.equals(filter);
        }

        Optional<TermHit> hit() {
            if (selected >= postings.size()) {
                return Optional.empty();
            }
            Posting posting = postings.get(selected);
            return Optional.of(new TermHit(field, term, posting.docId(), selected, docFreq, posting.freq()));
        }
    }

    /** The fields with postings, in field number order. */
    private final List<IndexField> fields;

    private int fieldRow;
    private final TableState fieldTable = new TableState();

    /** The terms shown; null before the first page. */
    private TermList terms;

    /** The postings shown; null before the first page. */
    private PostingList postings;

    private Column focus = Column.FIELDS;

    /** The text typed into the seek prompt while it is open; null otherwise. */
    private StringBuilder seek;

    /** The first page of terms asked for last; a reply for another first page is dropped. Null before. */
    private Request.LoadTerms wantedFirst;

    /** The filter of the documents screen, once it is built (ADR 0032). */
    private OptionalLong filter = OptionalLong.empty();

    /** Whether only the postings in the filter are shown, rather than all of them marked. */
    private boolean onlyFiltered;

    /** The fetches that are under way. */
    private final Set<Request> pending = new HashSet<>();

    public Terms(List<IndexField> fields) {
        this.fields = fields.stream()
                .filter(field -> field.indexOptions() != IndexOptions.NONE)
                .toList();
    }

    /**
     * The screen for the reopened index (ADR 0019): the same field, if it still has postings, and the
     * same column. Its terms are fetched again from the first one.
     */
    public Terms reopened(List<IndexField> fields) {
        Terms reopened = new Terms(fields);
        selectedField().ifPresent(field -> {
            for (int i = 0; i < reopened.fields.size(); i++) {
                if (reopened.fields.get(i).name().equals(field.name())) {
                    reopened.fieldRow = i;
                    break;
                }
            }
        });
        reopened.focus = focus;
        reopened.onlyFiltered = onlyFiltered;
        return reopened;
    }

    /**
     * Takes the filter of the documents screen: the one built, or empty. The postings are fetched
     * again through a new filter; without one, all of them are shown.
     */
    public void setFilter(OptionalLong filter) {
        if (!filter.equals(this.filter)) {
            this.filter = filter;
            if (filter.isEmpty()) {
                onlyFiltered = false;
            }
        }
    }

    /** Whether {@code b} has a filter to show only the postings of. */
    public boolean filtered() {
        return filter.isPresent();
    }

    /** Whether only the postings in the filter are shown. */
    public boolean onlyFiltered() {
        return onlyFiltered;
    }

    /** What the postings are read through. */
    private Optional<PostingsFilter> postingsFilter() {
        return filter.isPresent()
                ? Optional.of(new PostingsFilter(filter.getAsLong(), onlyFiltered))
                : Optional.empty();
    }

    /** What to fetch when the screen is shown. */
    public Optional<Request> initialRequest() {
        return nextRequest();
    }

    /** Whether a fetch is under way. */
    public boolean loading() {
        return !pending.isEmpty();
    }

    private Optional<IndexField> selectedField() {
        return fieldRow < fields.size() ? Optional.of(fields.get(fieldRow)) : Optional.empty();
    }

    /** {@code request}, unless it is under way already. */
    private Optional<Request> send(Request request) {
        return pending.add(request) ? Optional.of(request) : Optional.empty();
    }

    /**
     * What the screen still lacks: the first page of the selected field's terms, the next page once
     * the selection is on the last term, the postings of the selected term, and the next page of
     * those once the selection is on the last posting. A page that failed is not fetched again by
     * itself.
     */
    private Optional<Request> nextRequest() {
        Optional<IndexField> selected = selectedField();
        if (selected.isEmpty()) {
            return Optional.empty();
        }
        String field = selected.get().name();
        if (terms == null || !terms.field.equals(field)) {
            postings = null;
            // A first page of the field that was sought or browsed to is still the one wanted.
            if (wantedFirst == null || !wantedFirst.field().equals(field)) {
                wantedFirst = new Request.LoadTerms(field, Optional.empty(), false);
            }
            return send(wantedFirst);
        }
        if (terms.error.isEmpty() && terms.selected + 1 >= terms.entries.size() && terms.next.isPresent()) {
            return send(new Request.LoadTerms(field, terms.next, true));
        }
        Optional<BytesValue> term = terms.selectedTerm();
        if (term.isEmpty()) {
            return Optional.empty();
        }
        Optional<PostingsFilter> postingsFilter = postingsFilter();
        if (postings == null || !postings.isOf(field, term.get(), postingsFilter)) {
            return send(new Request.LoadPostings(field, term.get(), 0, false, postingsFilter));
        }
        if (postings.error.isEmpty()
                && postings.selected + 1 >= postings.postings.size()
                && postings.nextDoc.isPresent()) {
            return send(new Request.LoadPostings(field, term.get(), postings.nextDoc.getAsInt(), true, postingsFilter));
        }
        return Optional.empty();
    }

    /** Whether {@code request} is the first page that is wanted, of the selected field. */
    private boolean wanted(Request.LoadTerms request) {
        return request.equals(wantedFirst)
                && selectedField()
                        .map(IndexField::name)
                        .filter(request.field()::equals)
                        .isPresent();
    }

    /** Whether {@code request} continues the shown terms. */
    private boolean continuesTerms(Request.LoadTerms request) {
        return terms != null && terms.field.equals(request.field()) && terms.next.equals(request.from());
    }

    /** Whether {@code request} has the postings that the selected term needs. */
    private boolean wanted(Request.LoadPostings request) {
        return selectedField()
                        .map(IndexField::name)
                        .filter(request.field()::equals)
                        .isPresent()
                && terms != null
                && terms.field.equals(request.field())
                && terms.selectedTerm().filter(request.term()::equals).isPresent()
                && postingsFilter().equals(request.filter());
    }

    /** Whether {@code request} continues the shown postings. */
    private boolean continuesPostings(Request.LoadPostings request) {
        return postings != null
                && postings.isOf(request.field(), request.term(), request.filter())
                && postings.nextDoc.equals(OptionalInt.of(request.fromDoc()));
    }

    /** Shows a page of terms. Returns what the screen needs next. */
    public Optional<Request> termsLoaded(Request.LoadTerms request, GetTermsResult result) {
        pending.remove(request);
        if (request.more()) {
            if (!continuesTerms(request)) {
                return Optional.empty();
            }
            terms.entries.addAll(result.terms());
            terms.next = result.next();
        } else {
            if (!wanted(request)) {
                return Optional.empty();
            }
            terms = new TermList(request.field(), request.from(), result.terms(), result.next());
        }
        return nextRequest();
    }

    /** Shows why a page of terms could not be fetched. Returns what the screen needs next. */
    public Optional<Request> termsFailed(Request.LoadTerms request, String error) {
        pending.remove(request);
        if (request.more()) {
            if (!continuesTerms(request)) {
                return Optional.empty();
            }
            // The selection may have moved on to a term whose postings are still missing.
            terms.error = Optional.of(error);
            return nextRequest();
        }
        if (wanted(request)) {
            terms = new TermList(request.field(), request.from(), List.of(), Optional.empty());
            terms.error = Optional.of(error);
            postings = null;
        }
        return Optional.empty();
    }

    /**
     * Shows a page of postings. Returns what the screen needs next, or the document of the next
     * posting if a step waited for the page.
     */
    public Optional<Request> postingsLoaded(Request.LoadPostings request, GetPostingsResult result) {
        pending.remove(request);
        if (request.more()) {
            if (!continuesPostings(request)) {
                return Optional.empty();
            }
            postings.postings.addAll(result.postings());
            postings.nextDoc = result.nextDoc();
            postings.docFreq = result.docFreq();
            if (postings.stepPending) {
                postings.stepPending = false;
                if (postings.selected + 1 < postings.postings.size()) {
                    postings.selected++;
                    return postings.hit().map(Request.Document::new);
                }
            }
        } else {
            if (!wanted(request)) {
                return Optional.empty();
            }
            postings = new PostingList(
                    request.field(),
                    request.term(),
                    request.filter(),
                    result.docFreq(),
                    result.postings(),
                    result.nextDoc());
        }
        return nextRequest();
    }

    /** Shows why a page of postings could not be fetched. */
    public void postingsFailed(Request.LoadPostings request, String error) {
        pending.remove(request);
        if (request.more()) {
            if (continuesPostings(request)) {
                postings.error = Optional.of(error);
                postings.stepPending = false;
            }
        } else if (wanted(request)) {
            postings = new PostingList(
                    request.field(), request.term(), request.filter(), 0, List.of(), OptionalInt.empty());
            postings.error = Optional.of(error);
        }
    }

    /**
     * Moves to the previous ({@code forward} false) or next posting of the selected term, and shows
     * its document; fetches the next page first if the next posting is not in yet.
     */
    public Optional<Request> step(boolean forward) {
        if (postings == null) {
            return Optional.empty();
        }
        if (!forward) {
            if (postings.selected == 0) {
                return Optional.empty();
            }
            postings.selected--;
            return postings.hit().map(Request.Document::new);
        }
        if (postings.selected + 1 < postings.postings.size()) {
            postings.selected++;
            return postings.hit().map(Request.Document::new);
        }
        if (postings.error.isPresent() || postings.nextDoc.isEmpty()) {
            return Optional.empty();
        }
        postings.stepPending = true;
        return send(new Request.LoadPostings(
                postings.field, postings.term, postings.nextDoc.getAsInt(), true, postings.filter));
    }

    /** Shows the terms of {@code field} from {@code term} on, with the focus on the term's postings. */
    public Optional<Request> browse(String field, BytesValue term) {
        int row = -1;
        for (int i = 0; i < fields.size(); i++) {
            if (fields.get(i).name().equals(field)) {
                row = i;
                break;
            }
        }
        if (row < 0) {
            return Optional.empty();
        }
        fieldRow = row;
        seek = null;
        focus = Column.POSTINGS;
        wantedFirst = new Request.LoadTerms(field, Optional.of(term), false);
        return send(wantedFirst);
    }

    /** Whether the seek prompt takes the keys, Esc included. */
    public boolean prompting() {
        return seek != null;
    }

    /**
     * Moves the selection in the focused column, switches columns with ←/→ and Tab, seeks with
     * {@code /}, with Enter goes one column to the right or shows the selected posting's document,
     * with {@code e} exports the selected field's terms, and with {@code b} switches between all
     * postings and those in the filter, if there is one.
     */
    public Optional<Request> handleKey(KeyEvent key) {
        if (seek != null) {
            return seekKey(key);
        }
        if (key.isChar('e')) {
            return selectedField().map(field -> new Request.Export(field.name()));
        }
        if (key.isChar('b') && filter.isPresent()) {
            onlyFiltered = !onlyFiltered;
            return nextRequest();
        }
        if (key.isChar('/') && selectedField().isPresent()) {
            seek = new StringBuilder();
            focus = Column.TERMS;
            return Optional.empty();
        }
        KeyCode code = key.code();
        boolean tab = code == KeyCode.TAB && !key.hasShift();
        boolean backTab = code == KeyCode.TAB && key.hasShift();
        if (code == KeyCode.ENTER && focus == Column.POSTINGS) {
            return postings == null ? Optional.empty() : postings.hit().map(Request.Document::new);
        }
        if (code == KeyCode.ENTER || code == KeyCode.RIGHT || key.isChar('l') || tab) {
            focus = switch (focus) {
                case FIELDS -> Column.TERMS;
                case TERMS -> Column.POSTINGS;
                case POSTINGS -> tab ? Column.FIELDS : Column.POSTINGS;
            };
            return Optional.empty();
        }
        if (code == KeyCode.LEFT || key.isChar('h') || backTab) {
            focus = switch (focus) {
                case FIELDS -> backTab ? Column.POSTINGS : Column.FIELDS;
                case TERMS -> Column.FIELDS;
                case POSTINGS -> Column.TERMS;
            };
            return Optional.empty();
        }
        return moveSelection(key);
    }

    private Optional<Request> seekKey(KeyEvent key) {
        switch (key.code()) {
            case ENTER, ESCAPE -> {
                seek = null;
                return Optional.empty();
            }
            case CHAR -> seek.appendCodePoint(key.codePoint());
            case BACKSPACE -> {
                if (!seek.isEmpty()) {
                    seek.setLength(seek.offsetByCodePoints(seek.length(), -1));
                }
            }
            default -> {
                return Optional.empty();
            }
        }
        Optional<IndexField> field = selectedField();
        if (field.isEmpty()) {
            return Optional.empty();
        }
        Optional<BytesValue> from =
                seek.isEmpty() ? Optional.empty() : Optional.of(new BytesValue(seek.toString(), false));
        wantedFirst = new Request.LoadTerms(field.get().name(), from, false);
        return send(wantedFirst);
    }

    private Optional<Request> moveSelection(KeyEvent key) {
        switch (focus) {
            case FIELDS -> {
                OptionalInt row = Selection.moved(fieldRow, fields.size(), key);
                if (row.isEmpty() || row.getAsInt() == fieldRow) {
                    return Optional.empty();
                }
                fieldRow = row.getAsInt();
            }
            case TERMS -> {
                if (terms == null) {
                    return Optional.empty();
                }
                OptionalInt row = Selection.moved(terms.selected, terms.entries.size(), key);
                if (row.isEmpty()) {
                    return Optional.empty();
                }
                terms.selected = row.getAsInt();
            }
            case POSTINGS -> {
                if (postings == null) {
                    return Optional.empty();
                }
                OptionalInt row = Selection.moved(postings.selected, postings.postings.size(), key);
                if (row.isEmpty()) {
                    return Optional.empty();
                }
                postings.selected = row.getAsInt();
            }
        }
        return nextRequest();
    }

    public void render(Frame frame, Rect area) {
        if (fields.isEmpty()) {
            message(frame, area, " Fields ", true, "No field has postings.");
            return;
        }
        int nameWidth = fields.stream()
                .mapToInt(field -> field.name().codePointCount(0, field.name().length()))
                .max()
                .orElse(0);
        List<Rect> columns = Layout.horizontal()
                .constraints(
                        Constraint.length(Math.clamp(nameWidth + 4, 12, FIELDS_MAX_WIDTH)),
                        Constraint.fill(),
                        Constraint.length(POSTINGS_WIDTH))
                .split(area);
        renderFields(frame, columns.get(0));
        renderTerms(frame, columns.get(1));
        renderPostings(frame, columns.get(2));
    }

    private void renderFields(Frame frame, Rect area) {
        boolean focused = focus == Column.FIELDS;
        List<Row> rows = fields.stream()
                .map(field -> Row.from(Cell.from(TextFormat.escapeControl(field.name()))))
                .toList();
        fieldTable.select(fieldRow);
        Table table = table(rows, focused)
                .widths(Constraint.fill())
                .block(panel(" Fields ", focused))
                .build();
        frame.renderStatefulWidget(table, area, fieldTable);
    }

    private void renderTerms(Frame frame, Rect area) {
        boolean focused = focus == Column.TERMS;
        if (terms == null) {
            frame.renderWidget(panel(" Terms ", focused), area);
            return;
        }
        String field = TextFormat.firstChars(TextFormat.escapeControl(terms.field), 30);
        String title;
        if (seek != null) {
            title = " " + field + " from " + TextFormat.escapeControl(seek.toString()) + "▏ ";
        } else if (terms.from.isPresent()) {
            title = " " + field + " from " + terms.from.get() + " (/ seeks) ";
        } else {
            title = " " + field + " (/ seeks) ";
        }
        if (terms.entries.isEmpty()) {
            String text;
            if (terms.error.isPresent()) {
                text = "Cannot read the terms: " + terms.error.get();
            } else if (terms.from.isPresent()) {
                text = "No term from there on.";
            } else {
                text = "The field has no terms.";
            }
            message(frame, area, title, focused, text);
            return;
        }
        int docsWidth = Selection.numberWidth(terms.entries.stream().mapToLong(TermEntry::docFreq));
        int freqWidth = Selection.numberWidth(terms.entries.stream().mapToLong(TermEntry::totalTermFreq));
        Row header = Row.from(Cell.from("Term"), right("Docs", docsWidth), right("Freq", freqWidth))
                .style(Style.EMPTY.bold());
        List<Row> rows = new ArrayList<>();
        for (TermEntry entry : terms.entries) {
            rows.add(Row.from(
                    Cell.from(TextFormat.bytesText(entry.term(), entry.binary())),
                    right(Integer.toString(entry.docFreq()), docsWidth),
                    right(Long.toString(entry.totalTermFreq()), freqWidth)));
        }
        tailRow(terms.next.isPresent(), terms.error).ifPresent(rows::add);
        terms.table.select(terms.selected);
        Table table = table(rows, focused)
                .widths(Constraint.fill(), Constraint.length(docsWidth), Constraint.length(freqWidth))
                .header(header)
                .block(panel(title, focused))
                .build();
        frame.renderStatefulWidget(table, area, terms.table);
    }

    private void renderPostings(Frame frame, Rect area) {
        boolean focused = focus == Column.POSTINGS;
        if (postings == null) {
            frame.renderWidget(panel(" Postings ", focused), area);
            return;
        }
        boolean onlyMatches = postings.filter.map(PostingsFilter::onlyMatches).orElse(false);
        String title;
        if (postings.filter.isEmpty()) {
            title = " Postings (" + postings.docFreq + ") ";
        } else if (onlyMatches) {
            title = " Postings (" + postings.docFreq + ") · filter only ";
        } else {
            title = " Postings (" + postings.docFreq + ") · • filter ";
        }
        if (postings.postings.isEmpty()) {
            String text;
            if (postings.error.isPresent()) {
                text = "Cannot read the postings: " + postings.error.get();
            } else if (onlyMatches) {
                text = "No postings in the filter.";
            } else {
                text = "No postings.";
            }
            message(frame, area, title, focused, text);
            return;
        }
        boolean marked = postings.filter.isPresent() && !onlyMatches;
        int docWidth = Selection.numberWidth(postings.postings.stream().mapToLong(Posting::docId)) + (marked ? 2 : 0);
        Row header = Row.from(right("Doc", docWidth), right("Freq", 4), Cell.from(""))
                .style(Style.EMPTY.bold());
        List<Row> rows = new ArrayList<>();
        for (Posting posting : postings.postings) {
            // Marked: in the filter, or dimmed outside it.
            boolean in = posting.inFilter().orElse(false);
            boolean out = !posting.inFilter().orElse(true);
            String mark = marked && in ? "• " : "";
            Row row = Row.from(
                    right(mark + posting.docId(), docWidth),
                    right(
                            posting.freq().isPresent()
                                    ? Integer.toString(posting.freq().getAsInt())
                                    : "-",
                            4),
                    posting.deleted() ? Cell.from("deleted").style(Style.EMPTY.red()) : Cell.from(""));
            rows.add(marked && out ? row.style(Style.EMPTY.fg(Color.DARK_GRAY)) : row);
        }
        tailRow(postings.nextDoc.isPresent(), postings.error).ifPresent(rows::add);
        postings.table.select(postings.selected);
        Table table = table(rows, focused)
                .widths(Constraint.length(docWidth), Constraint.length(4), Constraint.fill())
                .header(header)
                .block(panel(title, focused))
                .build();
        frame.renderStatefulWidget(table, area, postings.table);
    }

    /** A table of {@code rows}, with the selected row highlighted as in the other screens. */
    private static Table.Builder table(List<Row> rows, boolean focused) {
        return Table.builder()
                .rows(rows)
                .columnSpacing(COLUMN_SPACING)
                .highlightStyle(highlight(focused))
                .highlightSymbol("")
                .highlightSpacing(Table.HighlightSpacing.NEVER);
    }

    private static void message(Frame frame, Rect area, String title, boolean focused, String text) {
        frame.renderWidget(
                Paragraph.builder()
                        .text(Text.from(text))
                        .overflow(Overflow.WRAP_WORD)
                        .block(panel(title, focused))
                        .build(),
                area);
    }
}
