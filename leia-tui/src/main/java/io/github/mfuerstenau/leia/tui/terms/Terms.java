package io.github.mfuerstenau.leia.tui.terms;

import static io.github.mfuerstenau.leia.tui.widgets.Panels.highlight;
import static io.github.mfuerstenau.leia.tui.widgets.Panels.panel;
import static io.github.mfuerstenau.leia.tui.widgets.Panels.right;
import static io.github.mfuerstenau.leia.tui.widgets.Panels.tailRow;

import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Layout;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.CharWidth;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.table.Cell;
import dev.tamboui.widgets.table.Row;
import dev.tamboui.widgets.table.Table;
import dev.tamboui.widgets.table.TableState;
import io.github.mfuerstenau.leia.protocol.document.BytesValue;
import io.github.mfuerstenau.leia.protocol.index.Feature;
import io.github.mfuerstenau.leia.protocol.index.IndexField;
import io.github.mfuerstenau.leia.protocol.index.IndexOptions;
import io.github.mfuerstenau.leia.protocol.points.GetPointCellResult;
import io.github.mfuerstenau.leia.protocol.points.GetPointTreeResult;
import io.github.mfuerstenau.leia.protocol.points.LeafPoint;
import io.github.mfuerstenau.leia.protocol.terms.GetPostingsResult;
import io.github.mfuerstenau.leia.protocol.terms.GetTermsResult;
import io.github.mfuerstenau.leia.protocol.terms.Posting;
import io.github.mfuerstenau.leia.protocol.terms.PostingsFilter;
import io.github.mfuerstenau.leia.protocol.terms.TermEntry;
import io.github.mfuerstenau.leia.tui.points.PointFormat;
import io.github.mfuerstenau.leia.tui.points.PointFormats;
import io.github.mfuerstenau.leia.tui.text.TextFormat;
import io.github.mfuerstenau.leia.tui.widgets.Panels;
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
 * b} shows only those (ADR 0032). A field with points has a row of its own, which shows its BKD
 * tree instead: a row per segment and, below an expanded one, its cells, fetched a level at a time,
 * with the points of a leaf on the right (ADR 0041).
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

        /**
         * The BKD tree of a point field, reported back with {@link #pointTreeLoaded} or {@link
         * #pointTreeFailed}; {@code serial} tells the replies of one call from another's.
         */
        record LoadPointTree(String field, long serial) implements Request {}

        /**
         * A cell of the BKD tree {@code serial} of a point field, reported back with {@link
         * #pointCellLoaded} or {@link #pointCellFailed}.
         */
        record LoadPointCell(String field, long serial, int segment, List<Integer> path) implements Request {}

        /** Show this document, which has a point of {@code field}, on the documents screen. */
        record PointDocument(String field, int docId) implements Request {}

        /** Search the documents with this term on the search screen (ADR 0052). */
        record Search(String field, BytesValue term) implements Request {}

        /** A key that the index lacks a feature for (ADR 0044); {@code what} names it. Nothing changed. */
        record Unsupported(String what) implements Request {}
    }

    /**
     * A row of the fields column: a field with postings, or with {@code points} the same or another
     * field's BKD tree.
     */
    private record FieldRow(IndexField field, boolean points) {}

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

    /**
     * The fields with postings, and those with points, in field number order; a field with both has
     * a row for each, its postings first.
     */
    private final List<FieldRow> fields;

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

    /** The tree of the selected point field; null before it is in, or for a field with postings. */
    private PointTree pointTree;

    /** The tree asked for last; a reply for another is dropped. Null before. */
    private Request.LoadPointTree wantedTree;

    /** The serial of the next tree asked for. */
    private long nextTreeSerial = 1;

    /** The format each point field is shown in, shared with the documents screen. */
    private PointFormats pointFormats = new PointFormats();

    /** What a key that doesn't work on the shown field said; until the next key. */
    private String note;

    /** What the index can do (ADR 0044): the keys it lacks a feature for are refused and left out of the legend. */
    private Set<Feature> features = Feature.all();

    public Terms(List<IndexField> fields) {
        List<FieldRow> rows = new ArrayList<>();
        for (IndexField field : fields) {
            if (field.indexOptions() != IndexOptions.NONE) {
                rows.add(new FieldRow(field, false));
            }
            if (field.pointDimensionCount() > 0) {
                rows.add(new FieldRow(field, true));
            }
        }
        this.fields = List.copyOf(rows);
    }

    /** Shows points in {@code formats}, which the documents screen shares. */
    public Terms withPointFormats(PointFormats formats) {
        this.pointFormats = formats;
        return this;
    }

    /** Offers only the keys that {@code features} allow (ADR 0044). */
    public Terms withFeatures(Set<Feature> features) {
        this.features = Set.copyOf(features);
        return this;
    }

    /**
     * The screen for the reopened index (ADR 0019): the same field, if it still has postings, and the
     * same column. Its terms are fetched again from the first one.
     */
    public Terms reopened(List<IndexField> fields) {
        Terms reopened = new Terms(fields);
        selectedRow().ifPresent(row -> {
            for (int i = 0; i < reopened.fields.size(); i++) {
                FieldRow other = reopened.fields.get(i);
                if (other.field().name().equals(row.field().name()) && other.points() == row.points()) {
                    reopened.fieldRow = i;
                    break;
                }
            }
        });
        reopened.focus = focus;
        reopened.onlyFiltered = onlyFiltered;
        reopened.pointFormats = pointFormats;
        reopened.features = features;
        // The segments' positions change with a reopen: the tree is fetched again, collapsed.
        reopened.nextTreeSerial = nextTreeSerial;
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

    private Optional<FieldRow> selectedRow() {
        return fieldRow < fields.size() ? Optional.of(fields.get(fieldRow)) : Optional.empty();
    }

    /** The selected field, if its postings are shown. */
    private Optional<IndexField> selectedField() {
        return selectedRow().filter(row -> !row.points()).map(FieldRow::field);
    }

    /** The selected field, if its BKD tree is shown. */
    private Optional<IndexField> selectedPointField() {
        return selectedRow().filter(FieldRow::points).map(FieldRow::field);
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
        Optional<IndexField> pointField = selectedPointField();
        if (pointField.isPresent()) {
            String field = pointField.get().name();
            if (pointTree != null && pointTree.field.equals(field)) {
                return Optional.empty();
            }
            pointTree = null;
            if (wantedTree == null || !wantedTree.field().equals(field)) {
                wantedTree = new Request.LoadPointTree(field, nextTreeSerial++);
            }
            return send(wantedTree);
        }
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

    /** Shows the BKD tree of a point field, if it is still the one wanted. */
    public Optional<Request> pointTreeLoaded(Request.LoadPointTree request, GetPointTreeResult result) {
        pending.remove(request);
        if (wantedTree(request)) {
            pointTree = new PointTree(request.serial(), request.field(), result);
        }
        return Optional.empty();
    }

    /** Shows why the BKD tree of a point field could not be fetched, if it is still the one wanted. */
    public void pointTreeFailed(Request.LoadPointTree request, String error) {
        pending.remove(request);
        if (wantedTree(request)) {
            pointTree = new PointTree(request.serial(), request.field(), error);
        }
    }

    private boolean wantedTree(Request.LoadPointTree request) {
        return request.equals(wantedTree)
                && selectedPointField()
                        .map(IndexField::name)
                        .filter(request.field()::equals)
                        .isPresent();
    }

    /**
     * Shows the children of a cell, or the points of a leaf, if its tree is still shown. A leaf that
     * is selected shows its points, and the focus moves to them.
     */
    public void pointCellLoaded(Request.LoadPointCell request, GetPointCellResult result) {
        pending.remove(request);
        cellOf(request).ifPresent(node -> {
            node.loaded(result);
            if (node.points != null
                    && pointTree.selectedNode().filter(node::equals).isPresent()) {
                pointTree.showLeaf(node);
                focus = Column.POSTINGS;
            }
        });
    }

    /** Shows why a cell could not be fetched, if its tree is still shown. */
    public void pointCellFailed(Request.LoadPointCell request, String error) {
        pending.remove(request);
        cellOf(request).ifPresent(node -> {
            node.loading = false;
            node.error = Optional.of(error);
        });
    }

    private Optional<PointTree.Node> cellOf(Request.LoadPointCell request) {
        if (pointTree == null || pointTree.serial != request.serial()) {
            return Optional.empty();
        }
        return pointTree.find(request.segment(), request.path());
    }

    /**
     * Enter on a cell: expands or collapses it, or shows the points of a leaf; fetches what that
     * needs first.
     */
    private Optional<Request> openCell() {
        Optional<PointTree.Node> selected = pointTree.selectedNode();
        if (selected.isEmpty()) {
            return Optional.empty();
        }
        PointTree.Node node = selected.get();
        if (node.points != null) {
            pointTree.showLeaf(node);
            focus = Column.POSTINGS;
            return Optional.empty();
        }
        if (node.children != null) {
            node.expanded = !node.expanded;
            return Optional.empty();
        }
        if (node.loading) {
            return Optional.empty();
        }
        node.loading = true;
        return send(new Request.LoadPointCell(pointTree.field, pointTree.serial, node.segment, node.path));
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
        return show(field, Optional.of(term), false);
    }

    /**
     * Shows {@code field}, as another screen asks for (ADR 0052): with {@code points} its BKD tree,
     * with a term its terms from there on with the focus on the term's postings, and otherwise its
     * terms with the focus on them. Nothing changes if the screen has no such row.
     */
    public Optional<Request> show(String field, Optional<BytesValue> term, boolean points) {
        int row = -1;
        for (int i = 0; i < fields.size(); i++) {
            if (fields.get(i).points() == points && fields.get(i).field().name().equals(field)) {
                row = i;
                break;
            }
        }
        if (row < 0) {
            return Optional.empty();
        }
        fieldRow = row;
        seek = null;
        note = null;
        if (points || term.isEmpty()) {
            focus = Column.TERMS;
            return nextRequest();
        }
        focus = Column.POSTINGS;
        wantedFirst = new Request.LoadTerms(field, term, false);
        return send(wantedFirst);
    }

    /**
     * What a jump leaves the screen at, for Backspace to show again (ADR 0053): the field, the focus,
     * and the terms, postings and tree it had loaded. They are the screen's own objects, so moves made
     * in them afterwards, such as a document's {@code ]}, still show.
     */
    public static final class Mark {
        private final int fieldRow;
        private final Column focus;
        private final TermList terms;
        private final PostingList postings;
        private final PointTree pointTree;
        private final Request.LoadTerms wantedFirst;
        private final Request.LoadPointTree wantedTree;

        private Mark(Terms of) {
            this.fieldRow = of.fieldRow;
            this.focus = of.focus;
            this.terms = of.terms;
            this.postings = of.postings;
            this.pointTree = of.pointTree;
            this.wantedFirst = of.wantedFirst;
            this.wantedTree = of.wantedTree;
        }
    }

    /** Where the screen is, for {@link #restore} to come back to (ADR 0053). */
    public Mark mark() {
        return new Mark(this);
    }

    /**
     * Shows the screen as {@code mark} left it (ADR 0053). It sends nothing: what is still missing,
     * such as a page that was loading when the screen was left, comes from {@link #initialRequest}.
     */
    public void restore(Mark mark) {
        fieldRow = mark.fieldRow;
        focus = mark.focus;
        terms = mark.terms;
        postings = mark.postings;
        pointTree = mark.pointTree;
        wantedFirst = mark.wantedFirst;
        wantedTree = mark.wantedTree;
        seek = null;
        note = null;
    }

    /** The screen is left: its note ends with it. */
    public void leave() {
        note = null;
    }

    /** Whether the seek prompt takes the keys, Esc included. */
    public boolean prompting() {
        return seek != null;
    }

    /**
     * Moves the selection in the focused column, switches columns with ←/→ and Tab, seeks with
     * {@code /}, with Enter goes one column to the right or shows the selected posting's document,
     * with {@code e} exports the selected field's terms, with {@code s} searches for the selected
     * term or the postings' term (ADR 0052), and with {@code b} switches between all postings and
     * those in the filter, if there is one. On a point field's tree, Enter expands or
     * collapses a cell, or shows a leaf's points, and on a point shows its document; {@code t}
     * cycles the format the points are shown in.
     */
    public Optional<Request> handleKey(KeyEvent key) {
        if (seek != null) {
            return seekKey(key);
        }
        note = null;
        Optional<String> unsupported = unsupported(key);
        if (unsupported.isPresent()) {
            return unsupported.map(Request.Unsupported::new);
        }
        Optional<IndexField> pointField = selectedPointField();
        if (pointField.isPresent()) {
            if (key.isChar('t')) {
                pointFormats.cycle(pointField.get());
                return Optional.empty();
            }
            if (key.isChar('/') || key.isChar('e') || key.isChar('s')) {
                note = (key.isChar('/') ? "/ seeks" : key.isChar('e') ? "e exports" : "s searches") + " terms only";
                return Optional.empty();
            }
            if (key.code() == KeyCode.ENTER && pointTree != null) {
                if (focus == Column.TERMS) {
                    return openCell();
                }
                if (focus == Column.POSTINGS) {
                    return pointTree
                            .selectedPoint()
                            .map(point -> new Request.PointDocument(pointTree.field, point.docId()));
                }
            }
        }
        if (key.isChar('e')) {
            return selectedField().map(field -> new Request.Export(field.name()));
        }
        if (key.isChar('s')) {
            return searchedTerm();
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

    /** What {@code key} is called if the index lacks a feature it needs (ADR 0044); empty otherwise. */
    private Optional<String> unsupported(KeyEvent key) {
        boolean points = showsPoints();
        Feature needed;
        String what;
        if (key.code() == KeyCode.ENTER && focus == Column.POSTINGS) {
            needed = Feature.DOCUMENTS;
            what = "Open doc";
        } else if (key.isChar('t') && points) {
            needed = Feature.POINTS;
            what = "Points";
        } else if (key.isChar('e') && !points) {
            needed = Feature.EXPORT_TERMS;
            what = "Export";
        } else if (key.isChar('s') && !points) {
            // A runtime has term queries without the rest of search (ADR 0058).
            return searchesTerms() ? Optional.empty() : Optional.of("Search term");
        } else if (key.isChar('b') && filter.isPresent()) {
            needed = Feature.FILTER;
            what = "Filter";
        } else {
            return Optional.empty();
        }
        return features.contains(needed) ? Optional.empty() : Optional.of(what);
    }

    /** Whether the index can search a term: with all of search, or term queries alone (ADR 0058). */
    private boolean searchesTerms() {
        return features.contains(Feature.SEARCH) || features.contains(Feature.TERM_SEARCH);
    }

    /**
     * The screen's keys for the footer, after the app's, while the seek prompt is closed: those the
     * index has the features for (ADR 0044).
     */
    public String keys() {
        if (showsPoints()) {
            return "⏎ expand / points / open doc   " + (features.contains(Feature.POINTS) ? "t format   " : "")
                    + "←/→ column   ↑↓";
        }
        String open = features.contains(Feature.DOCUMENTS) ? "⏎ open doc   " : "";
        String only = !filtered() ? "" : onlyFiltered ? "b all postings   " : "b filter only   ";
        String export = features.contains(Feature.EXPORT_TERMS) ? "e export   " : "";
        String search = searchesTerms() ? "s search   " : "";
        return "/ seek   " + open + search + only + export + "←/→ column   ↑↓";
    }

    /**
     * {@code s}: the term to search for (ADR 0052), the postings' on the postings column, otherwise
     * the selected one.
     */
    private Optional<Request> searchedTerm() {
        if (focus == Column.POSTINGS && postings != null) {
            return Optional.of(new Request.Search(postings.field, postings.term));
        }
        if (terms == null
                || selectedField()
                        .filter(field -> field.name().equals(terms.field))
                        .isEmpty()) {
            return Optional.empty();
        }
        return terms.selectedTerm().map(term -> new Request.Search(terms.field, term));
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
                if (selectedPointField().isPresent()) {
                    if (pointTree != null) {
                        Selection.moved(pointTree.selected, pointTree.visible().size(), key)
                                .ifPresent(row -> pointTree.selected = row);
                    }
                    return Optional.empty();
                }
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
                if (selectedPointField().isPresent()) {
                    if (pointTree != null && pointTree.leaf != null) {
                        Selection.moved(pointTree.pointSelected, pointTree.leaf.points.size(), key)
                                .ifPresent(row -> pointTree.pointSelected = row);
                    }
                    return Optional.empty();
                }
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
            message(frame, area, " Fields ", true, "No field has postings or points.");
            return;
        }
        int nameWidth = fields.stream()
                .map(Terms::fieldLabel)
                .mapToInt(label -> label.codePointCount(0, label.length()))
                .max()
                .orElse(0);
        List<Rect> columns = Layout.horizontal()
                .constraints(
                        Constraint.length(Math.clamp(nameWidth + 4, 12, FIELDS_MAX_WIDTH)),
                        Constraint.fill(),
                        Constraint.length(POSTINGS_WIDTH))
                .split(area);
        renderFields(frame, columns.get(0));
        if (selectedPointField().isPresent()) {
            renderPointTree(frame, columns.get(1));
            renderLeaf(frame, columns.get(2));
            return;
        }
        renderTerms(frame, columns.get(1));
        renderPostings(frame, columns.get(2));
    }

    /** A field's name, and for its points row their dimensions and bytes, such as {@code pt 1×8}. */
    private static String fieldLabel(FieldRow row) {
        String name = TextFormat.escapeControl(row.field().name());
        return row.points()
                ? name + " pt " + row.field().pointDimensionCount() + "×"
                        + row.field().pointNumBytes()
                : name;
    }

    private void renderFields(Frame frame, Rect area) {
        boolean focused = focus == Column.FIELDS;
        List<Row> rows = fields.stream()
                .map(row -> {
                    if (!row.points()) {
                        return Row.from(Cell.from(fieldLabel(row)));
                    }
                    String name = TextFormat.escapeControl(row.field().name());
                    return Row.from(Cell.from(Line.from(
                            Span.raw(name), Span.styled(fieldLabel(row).substring(name.length()), Style.EMPTY.dim()))));
                })
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

    private void renderPointTree(Frame frame, Rect area) {
        boolean focused = focus == Column.TERMS;
        IndexField field = selectedPointField().orElseThrow();
        String name = TextFormat.firstChars(TextFormat.escapeControl(field.name()), 30);
        int width = area.width() - 2;
        if (pointTree == null) {
            frame.renderWidget(panel(fitTitle(name, "", List.of(), width), focused), area);
            return;
        }
        PointFormat format = pointFormats.of(field);
        if (pointTree.tree == null) {
            message(
                    frame,
                    area,
                    fitTitle(name, "", List.of(), width),
                    focused,
                    "Cannot read the points: " + pointTree.error.orElse(""));
            return;
        }
        GetPointTreeResult tree = pointTree.tree;
        // A note goes first, where a narrow title doesn't cut it.
        String points = TextFormat.count(tree.size(), "point");
        String as = " · as " + format.label();
        String title = fitTitle(
                name,
                note != null ? " · " + note : "",
                List.of(
                        " · " + TextFormat.count(tree.segments().size(), "segment") + " · " + points + " in "
                                + TextFormat.count(tree.docCount(), "doc") + as,
                        " · " + points + " in " + TextFormat.count(tree.docCount(), "doc") + as,
                        as),
                width);
        if (pointTree.segments.isEmpty()) {
            message(frame, area, title, focused, "The field has no points.");
            return;
        }
        List<PointTree.Node> visible = pointTree.visible();
        pointTree.selected = Math.min(pointTree.selected, visible.size() - 1);
        List<Row> rows = new ArrayList<>();
        Style dim = Style.EMPTY.dim();
        for (PointTree.Node node : visible) {
            String marker;
            if (node.leaf.orElse(false)) {
                marker = "•";
            } else if (node.expanded) {
                marker = "▾";
            } else {
                marker = "▸";
            }
            List<Span> spans = new ArrayList<>();
            spans.add(Span.raw("  ".repeat(node.depth()) + marker + " "));
            if (node.name.isPresent() || node.depth() == 0) {
                spans.add(Span.styled(node.name.orElse("#" + node.segment) + "  ", Style.EMPTY.bold()));
            }
            spans.add(Span.raw(node.size + " pts  "));
            spans.add(Span.styled(format.format(node.min) + " … " + format.format(node.max), dim));
            if (node.loading) {
                spans.add(Span.styled("  …", dim));
            }
            node.error.ifPresent(error -> spans.add(Span.styled("  cannot read it: " + error, Style.EMPTY.red())));
            rows.add(Row.from(Cell.from(Line.from(spans))));
        }
        pointTree.table.select(pointTree.selected);
        Table table = table(rows, focused)
                .widths(Constraint.fill())
                .block(panel(title, focused))
                .build();
        frame.renderStatefulWidget(table, area, pointTree.table);
    }

    /**
     * The title {@code name}, {@code note} and the first of {@code tails}, fullest first, that fits
     * {@code width} cells; with none of them if none fits, and the name cut if even that doesn't.
     */
    private static String fitTitle(String name, String note, List<String> tails, int width) {
        for (String tail : tails) {
            String title = " " + name + note + tail + " ";
            if (CharWidth.of(title) <= width) {
                return title;
            }
        }
        String bare = " " + name + note + " ";
        int chars = name.codePointCount(0, name.length());
        while (CharWidth.of(bare) > width && chars > 1) {
            chars--;
            bare = " " + TextFormat.firstChars(name, chars) + note + " ";
        }
        // A panel narrower than the note cuts it too.
        chars = bare.codePointCount(0, bare.length());
        String cut = bare;
        while (CharWidth.of(cut) > width && chars > 1) {
            chars--;
            cut = TextFormat.firstChars(bare, chars);
        }
        return cut;
    }

    private void renderLeaf(Frame frame, Rect area) {
        boolean focused = focus == Column.POSTINGS;
        if (pointTree == null || pointTree.leaf == null) {
            message(frame, area, " Points ", focused, "⏎ on a leaf (•) shows its points.");
            return;
        }
        PointTree.Node leaf = pointTree.leaf;
        PointFormat format = pointFormats.of(selectedPointField().orElseThrow());
        String title = leaf.pointsCut
                ? " Points (" + leaf.points.size() + " of " + leaf.size + ") "
                : " Points (" + leaf.points.size() + ") ";
        int docWidth = Selection.numberWidth(leaf.points.stream().mapToLong(LeafPoint::docId));
        Row header = Row.from(right("Doc", docWidth), Cell.from("Value")).style(Style.EMPTY.bold());
        List<Row> rows = new ArrayList<>();
        for (LeafPoint point : leaf.points) {
            Row row =
                    Row.from(right(Integer.toString(point.docId()), docWidth), Cell.from(format.format(point.value())));
            rows.add(point.deleted() ? row.style(Style.EMPTY.red()) : row);
        }
        pointTree.pointTable.select(pointTree.pointSelected);
        Table table = table(rows, focused)
                .widths(Constraint.length(docWidth), Constraint.fill())
                .header(header)
                .block(panel(title, focused))
                .build();
        frame.renderStatefulWidget(table, area, pointTree.pointTable);
    }

    /** Whether the selected field's BKD tree is shown rather than its terms. */
    public boolean showsPoints() {
        return selectedPointField().isPresent();
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
        frame.renderWidget(Panels.wrapped(text, panel(title, focused), area), area);
    }
}
