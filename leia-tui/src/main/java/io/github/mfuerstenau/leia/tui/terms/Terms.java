package io.github.mfuerstenau.leia.tui.terms;

import static io.github.mfuerstenau.leia.tui.widgets.Keys.Group.DO;
import static io.github.mfuerstenau.leia.tui.widgets.Keys.Group.FIND;
import static io.github.mfuerstenau.leia.tui.widgets.Keys.Group.MOVE;
import static io.github.mfuerstenau.leia.tui.widgets.Keys.Group.SHOW;
import static io.github.mfuerstenau.leia.tui.widgets.Panels.highlight;
import static io.github.mfuerstenau.leia.tui.widgets.Panels.panel;
import static io.github.mfuerstenau.leia.tui.widgets.Panels.right;
import static io.github.mfuerstenau.leia.tui.widgets.Panels.tailRow;

import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Layout;
import dev.tamboui.layout.Rect;
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
import io.github.mfuerstenau.leia.client.values.PointFormat;
import io.github.mfuerstenau.leia.protocol.document.BytesValue;
import io.github.mfuerstenau.leia.protocol.index.Feature;
import io.github.mfuerstenau.leia.protocol.index.IndexField;
import io.github.mfuerstenau.leia.protocol.index.IndexOptions;
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
import io.github.mfuerstenau.leia.protocol.terms.GetPostingsResult;
import io.github.mfuerstenau.leia.protocol.terms.GetTermsResult;
import io.github.mfuerstenau.leia.protocol.terms.Posting;
import io.github.mfuerstenau.leia.protocol.terms.PostingsFilter;
import io.github.mfuerstenau.leia.protocol.terms.TermEntry;
import io.github.mfuerstenau.leia.tui.Optionals;
import io.github.mfuerstenau.leia.tui.points.PointFormats;
import io.github.mfuerstenau.leia.tui.text.TextFormat;
import io.github.mfuerstenau.leia.tui.theme.Palette;
import io.github.mfuerstenau.leia.tui.widgets.GoMenu;
import io.github.mfuerstenau.leia.tui.widgets.Jump;
import io.github.mfuerstenau.leia.tui.widgets.Keys;
import io.github.mfuerstenau.leia.tui.widgets.Panels;
import io.github.mfuerstenau.leia.tui.widgets.Selection;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.LongStream;
import org.jspecify.annotations.Nullable;

/**
 * The terms screen: the fields with postings, the terms of the selected field and the postings of
 * the selected term, side by side (ADR 0007). Terms and postings come in pages, and the next page is
 * fetched once the selection reaches the last row. Enter on a posting shows its document on the
 * documents screen. With a filter on the documents screen, the postings in it are marked, or {@code
 * b} shows only those (ADR 0032). A field with points has a row of its own, which shows its BKD
 * tree instead: a row per segment and, below an expanded one, its cells, fetched a level at a time,
 * with the points of a leaf on the right (ADR 0041). So has a field with KNN vectors, for its HNSW
 * graphs: a row per segment, its entry node and levels, each level's nodes, and on the right the
 * neighbours of a node, from which Enter walks on and {@code <} goes back (ADR 0085).
 *
 * <p>The Rust TUI got each reply before the next key; here a reply can come after the user moved
 * on. So a fetch that is under way is not asked for again, and a reply that no longer fits what the
 * screen shows is dropped: a first page of terms that isn't the last one asked for, postings of a
 * term that isn't selected any more, and a next page of a list that was replaced. The fetch for what
 * the screen shows now has always been asked for, and the backend answers in order, so its reply
 * comes last. Keys pressed past the fields column before a point tree or HNSW graphs are in are held
 * and replayed once they are, so that a quick ↓, → and Enter still opens the first segment.
 */
public final class Terms {

    private static final String POSTINGS_TITLE = " Postings (";

    /** How many terms one {@code getTerms} call fetches. */
    public static final int TERMS_PAGE = 100;

    /** How many postings one {@code getPostings} call fetches. */
    public static final int POSTINGS_PAGE = 100;

    /** The fields column is at most this wide, borders included. */
    private static final int FIELDS_MAX_WIDTH = 32;

    /** Wide enough for the title of the postings in a filter (ADR 0032). */
    private static final int POSTINGS_WIDTH = 34;

    /** Wide enough for a neighbour of a graph of any size (ADR 0085). */
    private static final int NEIGHBOURS_WIDTH = 42;

    /** The neighbours' columns but the ordinal and the document, with their spacing and the borders. */
    private static final int NEIGHBOURS_FIXED_WIDTH = 23;

    private static final int COLUMN_SPACING = 2;

    /** What the caller has to fetch or show for the terms screen. */
    public sealed interface Request {

        /**
         * A page of the terms of {@code field} from {@code from} on, reported back with {@link
         * #termsLoaded} or {@link #termsFailed}. With {@code more}, it continues the shown terms;
         * otherwise it replaces them.
         */
        record LoadTerms(String field, @Nullable BytesValue from, boolean more) implements Request {}

        /**
         * A page of the postings of a term from {@code fromDoc} on, reported back with {@link
         * #postingsLoaded} or {@link #postingsFailed}. With {@code more}, it continues the shown
         * postings; otherwise it replaces them. {@code filter} is the filter to read them through.
         */
        record LoadPostings(
                String field,
                BytesValue term,
                int fromDoc,
                boolean more,
                @Nullable PostingsFilter filter) implements Request {}

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

        /**
         * The HNSW graphs of a vector field (ADR 0085), reported back with {@link #knnGraphLoaded} or
         * {@link #knnGraphFailed}; {@code serial} tells the replies of one call from another's.
         */
        record LoadKnnGraph(String field, long serial) implements Request {}

        /**
         * A page of the nodes of a level of the graphs {@code serial}, from {@code offset} on, reported
         * back with {@link #knnNodesLoaded} or {@link #knnNodesFailed}.
         */
        record LoadKnnNodes(String field, long serial, int segment, int level, int offset) implements Request {}

        /**
         * A node of the graphs {@code serial} with its neighbours, reported back with {@link
         * #knnNodeLoaded} or {@link #knnNodeFailed}: node {@code ord} of segment {@code segment}, or
         * else the node of document {@code docId}. With {@code walk}, it continues the walk shown;
         * otherwise it starts one.
         */
        record LoadKnnNode(
                String field,
                long serial,
                @Nullable Integer segment,
                @Nullable Integer ord,
                @Nullable Integer docId,
                boolean walk)
                implements Request {

            static LoadKnnNode ofOrd(String field, long serial, int segment, int ord, boolean walk) {
                return new LoadKnnNode(field, serial, segment, ord, null, walk);
            }

            static LoadKnnNode ofDoc(String field, long serial, int docId) {
                return new LoadKnnNode(field, serial, null, null, docId, false);
            }
        }

        /** Show this document, which has a vector in {@code field}, on the documents screen. */
        record VectorDocument(String field, int docId) implements Request {}

        /** Search the documents with this term on the search screen (ADR 0052). */
        record Search(String field, BytesValue term) implements Request {}

        /** A key that the index lacks a feature for (ADR 0044); {@code what} names it. Nothing changed. */
        record Unsupported(String what) implements Request {}
    }

    /** What a row of the fields column shows of its field. */
    private enum RowKind {
        /** Its terms and postings. */
        TERMS,
        /** Its BKD tree (ADR 0041). */
        POINTS,
        /** Its HNSW graphs (ADR 0085). */
        GRAPH
    }

    /** A row of the fields column: a field's terms, BKD tree or HNSW graphs. */
    private record FieldRow(IndexField field, RowKind kind) {

        boolean points() {
            return kind == RowKind.POINTS;
        }
    }

    /**
     * A document that contains a term: a posting, as the documents screen shows it.
     *
     * @param index the posting's place among the term's postings, from 0
     * @param freq how often the term occurs in the document; empty if frequencies are not indexed
     */
    public record TermHit(
            String field,
            BytesValue term,
            int docId,
            int index,
            int docFreq,
            @Nullable Integer freq) {}

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
        final @Nullable String from;

        final List<TermEntry> entries;

        /** Where the next page starts; null after the last term. */
        @Nullable
        BytesValue next;

        /** Why the last page could not be fetched; no more pages are fetched then. Null if it could. */
        @Nullable
        String error;

        int selected;
        final TableState table = new TableState();

        TermList(String field, @Nullable BytesValue from, List<TermEntry> entries, @Nullable BytesValue next) {
            this.field = field;
            this.from = from == null ? null : TextFormat.bytesText(from.value(), from.binary());
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
        final @Nullable PostingsFilter filter;

        int docFreq;
        final List<Posting> postings;

        /** Where the next page starts; null after the last posting. */
        @Nullable
        Integer nextDoc;

        /** Why the last page could not be fetched; no more pages are fetched then. Null if it could. */
        @Nullable
        String error;

        int selected;
        final TableState table = new TableState();

        /** Whether the posting after the last one is to be shown once the next page is in. */
        boolean stepPending;

        PostingList(
                String field,
                BytesValue term,
                @Nullable PostingsFilter filter,
                int docFreq,
                List<Posting> postings,
                @Nullable Integer nextDoc) {
            this.field = field;
            this.term = term;
            this.filter = filter;
            this.docFreq = docFreq;
            this.postings = new ArrayList<>(postings);
            this.nextDoc = nextDoc;
        }

        boolean isOf(String field, BytesValue term, @Nullable PostingsFilter filter) {
            return this.field.equals(field) && this.term.equals(term) && Objects.equals(this.filter, filter);
        }

        Optional<TermHit> hit() {
            if (selected >= postings.size()) {
                return Optional.empty();
            }
            Posting posting = postings.get(selected);
            return Optional.of(
                    new TermHit(field, term, posting.docId(), selected, docFreq, Optionals.orNull(posting.freq())));
        }
    }

    /**
     * The fields with postings, those with points, and those with vectors, in field number order; a
     * field with more than one has a row for each, its postings first, then its points.
     */
    private final List<FieldRow> fields;

    private int fieldRow;
    private final TableState fieldTable = new TableState();

    /** The terms shown; null before the first page. */
    private @Nullable TermList terms;

    /** The postings shown; null before the first page. */
    private @Nullable PostingList postings;

    private Column focus = Column.FIELDS;

    /** The text typed into the seek prompt while it is open; null otherwise. */
    private @Nullable StringBuilder seek;

    /** The first page of terms asked for last; a reply for another first page is dropped. Null before. */
    private Request.@Nullable LoadTerms wantedFirst;

    /** The filter of the documents screen, once it is built (ADR 0032). */
    private @Nullable Long filter;

    /** Whether only the postings in the filter are shown, rather than all of them marked. */
    private boolean onlyFiltered;

    /** The fetches that are under way. */
    private final Set<Request> pending = new HashSet<>();

    /** The tree of the selected point field; null before it is in, or for a field with postings. */
    private @Nullable PointTree pointTree;

    /** The tree asked for last; a reply for another is dropped. Null before. */
    private Request.@Nullable LoadPointTree wantedTree;

    /** The serial of the next tree asked for. */
    private long nextTreeSerial = 1;

    /** The graphs of the selected vector field; null before they are in, or for another row. */
    private @Nullable KnnGraphTree knnTree;

    /** The graphs asked for last; a reply for others is dropped. Null before. */
    private Request.@Nullable LoadKnnGraph wantedGraph;

    /** The node asked for last; a reply for another is dropped. Null before. */
    private Request.@Nullable LoadKnnNode wantedNode;

    /** The document whose node is to be shown once the graphs are in (ADR 0085); null if none. */
    private @Nullable Integer wantedDocNode;

    /**
     * The keys pressed in the tree or neighbours/points column while the selected row's tree or graphs
     * were being fetched, replayed in order once they are in.
     */
    private final Deque<KeyEvent> heldKeys = new ArrayDeque<>();

    /** The format each point field is shown in, shared with the documents screen. */
    private PointFormats pointFormats = new PointFormats();

    /** What a key that doesn't work on the shown field said; until the next key. */
    private @Nullable String note;

    /** What the index can do (ADR 0044): the keys it lacks a feature for are refused and left out of the legend. */
    private Set<Feature> features = Feature.all();

    public Terms(List<IndexField> fields) {
        List<FieldRow> rows = new ArrayList<>();
        for (IndexField field : fields) {
            if (field.indexOptions() != IndexOptions.NONE) {
                rows.add(new FieldRow(field, RowKind.TERMS));
            }
            if (field.pointDimensionCount() > 0) {
                rows.add(new FieldRow(field, RowKind.POINTS));
            }
            if (field.vectorDimension() > 0) {
                rows.add(new FieldRow(field, RowKind.GRAPH));
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
                if (other.field().name().equals(row.field().name()) && other.kind() == row.kind()) {
                    reopened.fieldRow = i;
                    break;
                }
            }
        });
        reopened.focus = focus;
        reopened.onlyFiltered = onlyFiltered;
        reopened.pointFormats = pointFormats;
        reopened.features = features;
        // The segments' positions change with a reopen: the tree or graphs are fetched again, collapsed.
        reopened.nextTreeSerial = nextTreeSerial;
        return reopened;
    }

    /**
     * Takes the filter of the documents screen: the one built, or null. The postings are fetched
     * again through a new filter; without one, all of them are shown.
     */
    public void setFilter(@Nullable Long filter) {
        if (!Objects.equals(filter, this.filter)) {
            this.filter = filter;
            if (filter == null) {
                onlyFiltered = false;
            }
        }
    }

    /** Whether {@code b} has a filter to show only the postings of. */
    public boolean filtered() {
        return filter != null;
    }

    /** Whether only the postings in the filter are shown. */
    public boolean onlyFiltered() {
        return onlyFiltered;
    }

    /** What the postings are read through; null without a filter. */
    private @Nullable PostingsFilter postingsFilter() {
        return filter != null ? new PostingsFilter(filter, onlyFiltered) : null;
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
        return selectedRow().filter(row -> row.kind() == RowKind.TERMS).map(FieldRow::field);
    }

    /** The selected field, if its HNSW graphs are shown. */
    private Optional<IndexField> selectedVectorField() {
        return selectedRow().filter(row -> row.kind() == RowKind.GRAPH).map(FieldRow::field);
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
        Optional<IndexField> vectorField = selectedVectorField();
        if (vectorField.isPresent()) {
            return graphRequest(vectorField.get().name());
        }
        Optional<IndexField> pointField = selectedPointField();
        if (pointField.isPresent()) {
            return pointTreeRequest(pointField.get().name());
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
                wantedFirst = new Request.LoadTerms(field, null, false);
            }
            return send(wantedFirst);
        }
        if (terms.error == null && terms.selected + 1 >= terms.entries.size() && terms.next != null) {
            return send(new Request.LoadTerms(field, terms.next, true));
        }
        return postingsRequest(field);
    }

    /** The HNSW graph of vector field {@code field}, unless it is loaded or the index has none. */
    private Optional<Request> graphRequest(String field) {
        if (!features.contains(Feature.KNN_GRAPH)) {
            return Optional.empty();
        }
        if (knnTree != null && knnTree.field.equals(field)) {
            return Optional.empty();
        }
        knnTree = null;
        if (wantedGraph == null || !wantedGraph.field().equals(field)) {
            wantedGraph = new Request.LoadKnnGraph(field, nextTreeSerial++);
        }
        return send(wantedGraph);
    }

    /** The BKD tree of point field {@code field}, unless it is loaded. */
    private Optional<Request> pointTreeRequest(String field) {
        if (pointTree != null && pointTree.field.equals(field)) {
            return Optional.empty();
        }
        pointTree = null;
        if (wantedTree == null || !wantedTree.field().equals(field)) {
            wantedTree = new Request.LoadPointTree(field, nextTreeSerial++);
        }
        return send(wantedTree);
    }

    /** The postings of the selected term, or their next page once the last one is selected. */
    private Optional<Request> postingsRequest(String field) {
        Optional<BytesValue> term = Objects.requireNonNull(terms).selectedTerm();
        if (term.isEmpty()) {
            return Optional.empty();
        }
        PostingsFilter postingsFilter = postingsFilter();
        if (postings == null || !postings.isOf(field, term.get(), postingsFilter)) {
            return send(new Request.LoadPostings(field, term.get(), 0, false, postingsFilter));
        }
        if (postings.error == null && postings.selected + 1 >= postings.postings.size() && postings.nextDoc != null) {
            return send(new Request.LoadPostings(field, term.get(), postings.nextDoc, true, postingsFilter));
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
        return terms != null && terms.field.equals(request.field()) && Objects.equals(terms.next, request.from());
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
                && Objects.equals(postingsFilter(), request.filter());
    }

    /** Whether {@code request} continues the shown postings. */
    private boolean continuesPostings(Request.LoadPostings request) {
        return postings != null
                && postings.isOf(request.field(), request.term(), request.filter())
                && postings.nextDoc != null
                && postings.nextDoc == request.fromDoc();
    }

    /** Shows a page of terms. Returns what the screen needs next. */
    public Optional<Request> termsLoaded(Request.LoadTerms request, GetTermsResult result) {
        pending.remove(request);
        if (request.more()) {
            if (!continuesTerms(request)) {
                return Optional.empty();
            }
            Objects.requireNonNull(terms).entries.addAll(result.terms());
            terms.next = result.next().orElse(null);
        } else {
            if (!wanted(request)) {
                return Optional.empty();
            }
            terms = new TermList(
                    request.field(),
                    request.from(),
                    result.terms(),
                    result.next().orElse(null));
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
            Objects.requireNonNull(terms).error = error;
            return nextRequest();
        }
        if (wanted(request)) {
            terms = new TermList(request.field(), request.from(), List.of(), null);
            terms.error = error;
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
            Objects.requireNonNull(postings).postings.addAll(result.postings());
            postings.nextDoc = Optionals.orNull(result.nextDoc());
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
                    Optionals.orNull(result.nextDoc()));
        }
        return nextRequest();
    }

    /** Shows why a page of postings could not be fetched. */
    public void postingsFailed(Request.LoadPostings request, String error) {
        pending.remove(request);
        if (request.more()) {
            if (continuesPostings(request)) {
                Objects.requireNonNull(postings).error = error;
                postings.stepPending = false;
            }
        } else if (wanted(request)) {
            postings = new PostingList(request.field(), request.term(), request.filter(), 0, List.of(), null);
            postings.error = error;
        }
    }

    /** Shows the BKD tree of a point field, if it is still the one wanted. */
    public Optional<Request> pointTreeLoaded(Request.LoadPointTree request, GetPointTreeResult result) {
        pending.remove(request);
        if (!wantedTree(request)) {
            return Optional.empty();
        }
        pointTree = new PointTree(request.serial(), request.field(), result);
        return replayHeldKeys();
    }

    /** Shows why the BKD tree of a point field could not be fetched, if it is still the one wanted. */
    public Optional<Request> pointTreeFailed(Request.LoadPointTree request, String error) {
        pending.remove(request);
        if (!wantedTree(request)) {
            return Optional.empty();
        }
        pointTree = new PointTree(request.serial(), request.field(), error);
        return replayHeldKeys();
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
                    && Objects.requireNonNull(pointTree)
                            .selectedNode()
                            .filter(node::equals)
                            .isPresent()) {
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
            node.error = error;
        });
    }

    private Optional<PointTree.Node> cellOf(Request.LoadPointCell request) {
        if (pointTree == null || pointTree.serial != request.serial()) {
            return Optional.empty();
        }
        return pointTree.find(request.segment(), request.path());
    }

    /**
     * Shows the HNSW graphs of a vector field, if they are still the ones wanted; then the node of
     * the document asked for, if one was.
     */
    public Optional<Request> knnGraphLoaded(Request.LoadKnnGraph request, GetKnnGraphResult result) {
        pending.remove(request);
        if (!wantedGraph(request)) {
            return Optional.empty();
        }
        knnTree = new KnnGraphTree(request.serial(), request.field(), result);
        Optional<Request> docNode = docNodeRequest();
        return docNode.isPresent() ? docNode : replayHeldKeys();
    }

    /** Shows why the graphs of a vector field could not be fetched, if they are still the ones wanted. */
    public Optional<Request> knnGraphFailed(Request.LoadKnnGraph request, String error) {
        pending.remove(request);
        if (!wantedGraph(request)) {
            return Optional.empty();
        }
        knnTree = new KnnGraphTree(request.serial(), request.field(), error);
        wantedDocNode = null;
        return replayHeldKeys();
    }

    /**
     * Whether the keys past the fields column wait for the selected row's point tree or HNSW graphs,
     * which are being fetched.
     */
    private boolean awaitingTree() {
        if (focus == Column.FIELDS) {
            return false;
        }
        if (selectedVectorField().isPresent()) {
            return knnTree == null && wantedGraph != null && pending.contains(wantedGraph);
        }
        if (selectedPointField().isPresent()) {
            return pointTree == null && wantedTree != null && pending.contains(wantedTree);
        }
        return false;
    }

    /**
     * Handles the held keys in order, now that the tree they waited for is in. The first that fetches
     * something ends the replay, and the keys after it are dropped, as only one fetch can be returned.
     */
    private Optional<Request> replayHeldKeys() {
        while (!heldKeys.isEmpty()) {
            Optional<Request> request = handleKey(heldKeys.poll());
            if (request.isPresent()) {
                heldKeys.clear();
                return request;
            }
        }
        return Optional.empty();
    }

    private boolean wantedGraph(Request.LoadKnnGraph request) {
        return request.equals(wantedGraph)
                && selectedVectorField()
                        .map(IndexField::name)
                        .filter(request.field()::equals)
                        .isPresent();
    }

    /** The node of the document asked for, once the graphs it is in are shown. */
    private Optional<Request> docNodeRequest() {
        if (wantedDocNode == null || knnTree == null || knnTree.graph == null) {
            return Optional.empty();
        }
        int docId = wantedDocNode;
        wantedDocNode = null;
        wantedNode = Request.LoadKnnNode.ofDoc(knnTree.field, knnTree.serial, docId);
        return send(wantedNode);
    }

    /** Shows a page of a level's nodes, if its graphs are still shown. */
    public void knnNodesLoaded(Request.LoadKnnNodes request, GetKnnGraphNodesResult result) {
        pending.remove(request);
        levelOf(request)
                .filter(level -> level.nodes.size() == request.offset())
                .ifPresent(level -> level.loaded(result));
    }

    /** Shows why a page of a level's nodes could not be fetched, if its graphs are still shown. */
    public void knnNodesFailed(Request.LoadKnnNodes request, String error) {
        pending.remove(request);
        levelOf(request).ifPresent(level -> {
            level.loading = false;
            level.error = error;
        });
    }

    private Optional<KnnGraphTree.Level> levelOf(Request.LoadKnnNodes request) {
        if (knnTree == null || knnTree.serial != request.serial()) {
            return Optional.empty();
        }
        return knnTree.level(request.segment(), request.level());
    }

    /**
     * Shows a node's neighbours, if it is the node asked for last: on the walk shown, or as the start
     * of a new one. The focus moves to them.
     */
    public void knnNodeLoaded(Request.LoadKnnNode request, GetKnnGraphNodeResult result) {
        pending.remove(request);
        if (!wantedNode(request)) {
            return;
        }
        if (!request.walk()) {
            Objects.requireNonNull(knnTree).path.clear();
        }
        Objects.requireNonNull(knnTree).path.add(new KnnGraphTree.Shown(result));
        knnTree.nodeError = null;
        focus = Column.POSTINGS;
    }

    /** Shows why a node could not be fetched, if it is the node asked for last. */
    public void knnNodeFailed(Request.LoadKnnNode request, String error) {
        pending.remove(request);
        if (wantedNode(request)) {
            Objects.requireNonNull(knnTree).nodeError = error;
        }
    }

    private boolean wantedNode(Request.LoadKnnNode request) {
        return request.equals(wantedNode) && knnTree != null && knnTree.serial == request.serial();
    }

    /**
     * Enter on a row of the graphs: expands or collapses a segment or level, fetching a level's first
     * page, fetches the next page on the row after a level's nodes, and shows the neighbours of a node.
     */
    private Optional<Request> openGraphRow() {
        Optional<KnnGraphTree.Item> selected = Objects.requireNonNull(knnTree).selectedItem();
        if (selected.isEmpty()) {
            return Optional.empty();
        }
        return switch (selected.get()) {
            case KnnGraphTree.Item.SegmentItem item -> {
                if (item.segment().info.shape().isEmpty()) {
                    note = "no graph in this segment";
                } else {
                    item.segment().expanded = !item.segment().expanded;
                }
                yield Optional.empty();
            }
            case KnnGraphTree.Item.EntryItem item ->
                openNode(item.segment().info.segment(), item.node());
            case KnnGraphTree.Item.LevelItem item -> {
                KnnGraphTree.Level level = item.level();
                if (level.fetched()) {
                    level.expanded = !level.expanded;
                    yield Optional.empty();
                }
                yield nodesPage(level);
            }
            case KnnGraphTree.Item.NodeItem item -> openNode(item.level().segment, item.node());
            case KnnGraphTree.Item.MoreItem item -> nodesPage(item.level());
        };
    }

    /** The next page of a level's nodes, unless one is under way. */
    private Optional<Request> nodesPage(KnnGraphTree.Level level) {
        if (level.loading) {
            return Optional.empty();
        }
        level.loading = true;
        level.error = null;
        return send(new Request.LoadKnnNodes(
                Objects.requireNonNull(knnTree).field,
                knnTree.serial,
                level.segment,
                level.number,
                level.nodes.size()));
    }

    /** The neighbours of {@code node} of {@code segment}, as the start of a walk. */
    private Optional<Request> openNode(int segment, KnnGraphNode node) {
        wantedNode = Request.LoadKnnNode.ofOrd(
                Objects.requireNonNull(knnTree).field, knnTree.serial, segment, node.ord(), false);
        return send(wantedNode);
    }

    /** Enter on a neighbour: walks on to it. */
    private Optional<Request> walkOn() {
        Optional<KnnGraphTree.Shown> shown = Objects.requireNonNull(knnTree).shown();
        if (shown.isEmpty()) {
            return Optional.empty();
        }
        return shown.get().selectedNeighbour().flatMap(neighbour -> {
            wantedNode = Request.LoadKnnNode.ofOrd(
                    Objects.requireNonNull(knnTree).field,
                    knnTree.serial,
                    shown.get().node.segment(),
                    neighbour.node().ord(),
                    true);
            return send(wantedNode);
        });
    }

    /** {@code <}: back to the node the walk came from. */
    private void walkBack() {
        if (Objects.requireNonNull(knnTree).path.size() > 1) {
            knnTree.path.removeLast();
            // A node still on its way would land on the wrong step.
            wantedNode = null;
        } else {
            note = "the walk's first node";
        }
    }

    /** {@code g d}: the document of the selected node or neighbour. */
    private Optional<Request> graphDocument() {
        Optional<KnnGraphNode> node = Optional.empty();
        if (focus == Column.POSTINGS) {
            node = Objects.requireNonNull(knnTree)
                    .shown()
                    .flatMap(KnnGraphTree.Shown::selectedNeighbour)
                    .map(KnnGraphNeighbour::node);
        } else if (focus == Column.TERMS) {
            node = Objects.requireNonNull(knnTree).selectedItem().flatMap(item -> switch (item) {
                case KnnGraphTree.Item.EntryItem entry -> Optional.of(entry.node());
                case KnnGraphTree.Item.NodeItem nodeItem -> Optional.of(nodeItem.node());
                default -> Optional.empty();
            });
        }
        return node.map(n -> new Request.VectorDocument(Objects.requireNonNull(knnTree).field, n.docId()));
    }

    /**
     * Enter on a cell: expands or collapses it, or shows the points of a leaf; fetches what that
     * needs first.
     */
    private Optional<Request> openCell() {
        Optional<PointTree.Node> selected = Objects.requireNonNull(pointTree).selectedNode();
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
        if (postings.error != null || postings.nextDoc == null) {
            return Optional.empty();
        }
        postings.stepPending = true;
        return send(new Request.LoadPostings(postings.field, postings.term, postings.nextDoc, true, postings.filter));
    }

    /**
     * Shows the HNSW graphs of {@code field} with the neighbours of document {@code docId}'s node
     * (ADR 0085), as the documents screen asks for. Nothing changes if the screen has no such row.
     */
    public Optional<Request> showNode(String field, int docId) {
        int row = -1;
        for (int i = 0; i < fields.size(); i++) {
            if (fields.get(i).kind() == RowKind.GRAPH
                    && fields.get(i).field().name().equals(field)) {
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
        heldKeys.clear();
        focus = Column.POSTINGS;
        wantedDocNode = docId;
        if (knnTree != null && knnTree.field.equals(field)) {
            return docNodeRequest();
        }
        return nextRequest();
    }

    /** Shows the terms of {@code field} from {@code term} on, with the focus on the term's postings. */
    public Optional<Request> browse(String field, BytesValue term) {
        return show(field, term, false);
    }

    /**
     * Shows {@code field}, as another screen asks for (ADR 0052): with {@code points} its BKD tree,
     * with a term its terms from there on with the focus on the term's postings, and otherwise its
     * terms with the focus on them. Nothing changes if the screen has no such row.
     */
    public Optional<Request> show(String field, @Nullable BytesValue term, boolean points) {
        int row = -1;
        RowKind kind = points ? RowKind.POINTS : RowKind.TERMS;
        for (int i = 0; i < fields.size(); i++) {
            if (fields.get(i).kind() == kind && fields.get(i).field().name().equals(field)) {
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
        heldKeys.clear();
        if (points || term == null) {
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
        private final @Nullable TermList terms;
        private final @Nullable PostingList postings;
        private final @Nullable PointTree pointTree;
        private final @Nullable KnnGraphTree knnTree;
        private final Request.@Nullable LoadTerms wantedFirst;
        private final Request.@Nullable LoadPointTree wantedTree;
        private final Request.@Nullable LoadKnnGraph wantedGraph;

        private Mark(Terms of) {
            this.fieldRow = of.fieldRow;
            this.focus = of.focus;
            this.terms = of.terms;
            this.postings = of.postings;
            this.pointTree = of.pointTree;
            this.knnTree = of.knnTree;
            this.wantedFirst = of.wantedFirst;
            this.wantedTree = of.wantedTree;
            this.wantedGraph = of.wantedGraph;
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
        knnTree = mark.knnTree;
        wantedFirst = mark.wantedFirst;
        wantedTree = mark.wantedTree;
        wantedGraph = mark.wantedGraph;
        wantedNode = null;
        wantedDocNode = null;
        seek = null;
        note = null;
        heldKeys.clear();
    }

    /** The screen is left: its note and the keys it held end with it. */
    public void leave() {
        note = null;
        heldKeys.clear();
    }

    /** Whether the seek prompt takes the keys, Esc included. */
    public boolean prompting() {
        return seek != null;
    }

    /**
     * Moves the selection in the focused column, switches columns with ←/→ and Tab, seeks with
     * {@code /}, with Enter goes one column to the right or shows the selected posting's document,
     * with {@code E} exports the selected field's terms, and with {@code b} switches between all postings and
     * those in the filter, if there is one. On a point field's tree, Enter expands or
     * collapses a cell, or shows a leaf's points, and on a point shows its document; {@code t}
     * cycles the format the points are shown in. On a vector field's graphs, Enter expands or
     * collapses a segment or level, shows a node's neighbours, and on a neighbour walks on to it;
     * {@code <} walks back (ADR 0085). The jumps to other screens are {@link #jump}'s (ADR 0120). Past
     * the fields column, a key pressed before the selected row's tree or graphs are in waits for them.
     */
    public Optional<Request> handleKey(KeyEvent key) {
        if (seek != null) {
            return seekKey(key);
        }
        if (awaitingTree()) {
            heldKeys.add(key);
            return Optional.empty();
        }
        note = null;
        Optional<String> unsupported = unsupported(key);
        if (unsupported.isPresent()) {
            return unsupported.map(Request.Unsupported::new);
        }
        if (selectedVectorField().isPresent()) {
            Optional<Optional<Request>> handled = vectorKey(key);
            if (handled.isPresent()) {
                return handled.get();
            }
        }
        Optional<IndexField> pointField = selectedPointField();
        if (pointField.isPresent()) {
            Optional<Optional<Request>> handled = pointKey(key, pointField.get());
            if (handled.isPresent()) {
                return handled.get();
            }
        }
        return termsKey(key);
    }

    /** A key on a vector field; empty for one that works as on any field. */
    private Optional<Optional<Request>> vectorKey(KeyEvent key) {
        if (key.isChar('/') || key.isChar('E')) {
            note = termsOnly(key);
            return Optional.of(Optional.empty());
        }
        if (knnTree != null && knnTree.graph != null) {
            Optional<Optional<Request>> handled = graphKey(key);
            if (handled.isPresent()) {
                return handled;
            }
        }
        if (key.code() == KeyCode.ENTER && focus != Column.FIELDS) {
            return Optional.of(Optional.empty());
        }
        return Optional.empty();
    }

    /** A key on the HNSW graph; empty for one it doesn't take. */
    private Optional<Optional<Request>> graphKey(KeyEvent key) {
        if (key.code() == KeyCode.ENTER && focus == Column.TERMS) {
            return Optional.of(openGraphRow());
        }
        if (key.code() == KeyCode.ENTER && focus == Column.POSTINGS) {
            return Optional.of(walkOn());
        }
        if (key.isChar('<')) {
            walkBack();
            return Optional.of(Optional.empty());
        }
        return Optional.empty();
    }

    /** A key on a point field; empty for one that works as on any field. */
    private Optional<Optional<Request>> pointKey(KeyEvent key, IndexField field) {
        if (key.isChar('t')) {
            pointFormats.cycle(field);
            return Optional.of(Optional.empty());
        }
        if (key.isChar('/') || key.isChar('E')) {
            note = termsOnly(key);
            return Optional.of(Optional.empty());
        }
        if (key.code() != KeyCode.ENTER || pointTree == null) {
            return Optional.empty();
        }
        if (focus == Column.TERMS) {
            return Optional.of(openCell());
        }
        if (focus == Column.POSTINGS) {
            PointTree tree = Objects.requireNonNull(pointTree);
            return Optional.of(tree.selectedPoint().map(point -> new Request.PointDocument(tree.field, point.docId())));
        }
        return Optional.empty();
    }

    private Optional<Request> termsKey(KeyEvent key) {
        if (key.isChar('E')) {
            return selectedField().map(field -> new Request.Export(field.name()));
        }
        if (key.isChar('b') && filter != null) {
            onlyFiltered = !onlyFiltered;
            return nextRequest();
        }
        if (key.isChar('/') && selectedField().isPresent()) {
            seek = new StringBuilder();
            focus = Column.TERMS;
            return Optional.empty();
        }
        if (key.code() == KeyCode.ENTER && focus == Column.POSTINGS) {
            return postings == null ? Optional.empty() : postings.hit().map(Request.Document::new);
        }
        if (moveFocus(key)) {
            return Optional.empty();
        }
        return moveSelection(key);
    }

    /** Moves the focus to the column right or left of it, if {@code key} does; Tab wraps around. */
    private boolean moveFocus(KeyEvent key) {
        KeyCode code = key.code();
        boolean tab = code == KeyCode.TAB && !key.hasShift();
        boolean backTab = code == KeyCode.TAB && key.hasShift();
        if (code == KeyCode.ENTER || code == KeyCode.RIGHT || key.isChar('l') || tab) {
            focus = rightOf(focus, tab);
            return true;
        }
        if (code == KeyCode.LEFT || key.isChar('h') || backTab) {
            focus = leftOf(focus, backTab);
            return true;
        }
        return false;
    }

    private static Column rightOf(Column column, boolean wraps) {
        return switch (column) {
            case FIELDS -> Column.TERMS;
            case TERMS -> Column.POSTINGS;
            case POSTINGS -> wraps ? Column.FIELDS : Column.POSTINGS;
        };
    }

    private static Column leftOf(Column column, boolean wraps) {
        return switch (column) {
            case FIELDS -> wraps ? Column.POSTINGS : Column.FIELDS;
            case TERMS -> Column.FIELDS;
            case POSTINGS -> Column.TERMS;
        };
    }

    /** What {@code key} is called if the index lacks a feature it needs (ADR 0044); empty otherwise. */
    private Optional<String> unsupported(KeyEvent key) {
        boolean points = showsPoints();
        boolean graph = showsGraph();
        return need(key, points, graph)
                .filter(need -> !features.contains(need.feature()))
                .map(Need::what);
    }

    /** A feature that a key needs, and what the key is called. */
    private record Need(Feature feature, String what) {}

    private Optional<Need> need(KeyEvent key, boolean points, boolean graph) {
        if (graph && (key.code() == KeyCode.ENTER && focus != Column.FIELDS || key.isChar('<'))) {
            return Optional.of(new Need(Feature.KNN_GRAPH, "HNSW graph"));
        }
        if (key.code() == KeyCode.ENTER && focus == Column.POSTINGS) {
            return Optional.of(new Need(Feature.DOCUMENTS, "Open doc"));
        }
        if (key.isChar('t') && points) {
            return Optional.of(new Need(Feature.POINTS, "Points"));
        }
        if (key.isChar('E') && !points && !graph) {
            return Optional.of(new Need(Feature.EXPORT_TERMS, "Export"));
        }
        if (key.isChar('b') && filter != null) {
            return Optional.of(new Need(Feature.FILTER, "Filter"));
        }
        return Optional.empty();
    }

    /** Whether the index can search a term (ADR 0058). */
    private boolean searchesTerms() {
        return features.contains(Feature.TERM_SEARCH);
    }

    /** The screen's keys while the seek prompt is closed (ADR 0120): those the index has the features for (ADR 0044). */
    public Keys keys() {
        Keys.Builder keys = Keys.builder().key(MOVE, "←→", "column");
        if (showsGraph()) {
            if (features.contains(Feature.KNN_GRAPH)) {
                keys.key(SHOW, "⏎", "expand / neighbours / walk on").key(MOVE, "<", "walk back");
            }
            return keys.build();
        }
        if (showsPoints()) {
            return keys.key(SHOW, "⏎", "expand / points / open doc")
                    .keyIf(features.contains(Feature.POINTS), SHOW, "t", "point format")
                    .build();
        }
        return keys.key(FIND, "/", "seek")
                .keyIf(features.contains(Feature.DOCUMENTS), SHOW, "⏎", "open doc")
                .keyIf(filtered(), SHOW, "b", onlyFiltered ? "all postings" : "filter only")
                .keyIf(features.contains(Feature.EXPORT_TERMS), DO, "E", "export")
                .build();
    }

    /**
     * The jumps of the {@code g} menu that the index and the selection allow (ADR 0120): the term to
     * the search screen, and a posting's or a graph node's document to the documents screen.
     */
    public List<GoMenu.Target> jumps() {
        List<GoMenu.Target> jumps = new ArrayList<>();
        if (showsGraph()) {
            if (features.contains(Feature.DOCUMENTS)
                    && features.contains(Feature.KNN_GRAPH)
                    && knnTree != null
                    && knnTree.graph != null
                    && graphDocument().isPresent()) {
                jumps.add(new GoMenu.Target(Jump.DOCUMENT, "document of the node"));
            }
            return jumps;
        }
        if (showsPoints()) {
            return jumps;
        }
        if (searchesTerms()) {
            searchedTerm()
                    .map(Request.Search.class::cast)
                    .ifPresent(term -> jumps.add(new GoMenu.Target(
                            Jump.SEARCH, "search " + TextFormat.escapeControl(term.field()) + " for the term")));
        }
        if (features.contains(Feature.DOCUMENTS)
                && focus == Column.POSTINGS
                && postings != null
                && postings.hit().isPresent()) {
            jumps.add(new GoMenu.Target(Jump.DOCUMENT, "document of the posting"));
        }
        return jumps;
    }

    /** Where {@code g} and {@code jump} go from the selection (ADR 0052, ADR 0120). */
    public Optional<Request> jump(Jump jump) {
        if (awaitingTree()) {
            return Optional.empty();
        }
        note = null;
        boolean points = showsPoints();
        boolean graph = showsGraph();
        return switch (jump) {
            case SEARCH -> {
                if (points || graph) {
                    note = "g s searches terms only";
                    yield Optional.empty();
                }
                // A runtime has term queries without the rest of search (ADR 0058).
                if (!searchesTerms()) {
                    yield Optional.of(new Request.Unsupported("Search term"));
                }
                yield searchedTerm().map(Request.class::cast);
            }
            case DOCUMENT -> {
                if (!features.contains(Feature.DOCUMENTS)) {
                    yield Optional.of(new Request.Unsupported("Open doc"));
                }
                if (graph) {
                    if (!features.contains(Feature.KNN_GRAPH)) {
                        yield Optional.of(new Request.Unsupported("HNSW graph"));
                    }
                    yield knnTree != null && knnTree.graph != null ? graphDocument() : Optional.empty();
                }
                if (points || focus != Column.POSTINGS || postings == null) {
                    yield Optional.empty();
                }
                yield postings.hit().map(Request.Document::new);
            }
            default -> Optional.empty();
        };
    }

    /**
     * {@code g s}: the term to search for (ADR 0052), the postings' on the postings column, otherwise
     * the selected one.
     */
    private Optional<Request> searchedTerm() {
        if (focus == Column.POSTINGS && postings != null) {
            return Optional.of(new Request.Search(postings.field, postings.term));
        }
        if (terms == null
                || selectedField()
                        .filter(field -> field.name().equals(Objects.requireNonNull(terms).field))
                        .isEmpty()) {
            return Optional.empty();
        }
        TermList list = Objects.requireNonNull(terms);
        return list.selectedTerm().map(term -> new Request.Search(list.field, term));
    }

    private Optional<Request> seekKey(KeyEvent key) {
        switch (key.code()) {
            case ENTER, ESCAPE -> {
                seek = null;
                return Optional.empty();
            }
            case CHAR -> Objects.requireNonNull(seek).appendCodePoint(key.codePoint());
            case BACKSPACE -> {
                if (!Objects.requireNonNull(seek).isEmpty()) {
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
        BytesValue from = seek.isEmpty() ? null : new BytesValue(seek.toString(), false);
        wantedFirst = new Request.LoadTerms(field.get().name(), from, false);
        return send(wantedFirst);
    }

    private Optional<Request> moveSelection(KeyEvent key) {
        return switch (focus) {
            case FIELDS -> moveField(key);
            case TERMS -> moveTerm(key);
            case POSTINGS -> movePosting(key);
        };
    }

    private Optional<Request> moveField(KeyEvent key) {
        OptionalInt row = Selection.moved(fieldRow, fields.size(), key);
        if (row.isEmpty() || row.getAsInt() == fieldRow) {
            return Optional.empty();
        }
        fieldRow = row.getAsInt();
        return nextRequest();
    }

    /** Moves in the middle column: the graph's rows, the tree's cells or the terms. */
    private Optional<Request> moveTerm(KeyEvent key) {
        if (selectedVectorField().isPresent()) {
            if (knnTree != null) {
                Selection.moved(knnTree.selected, knnTree.visible().size(), key)
                        .ifPresent(row -> Objects.requireNonNull(knnTree).selected = row);
            }
            return Optional.empty();
        }
        if (selectedPointField().isPresent()) {
            if (pointTree != null) {
                Selection.moved(pointTree.selected, pointTree.visible().size(), key)
                        .ifPresent(row -> Objects.requireNonNull(pointTree).selected = row);
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
        return nextRequest();
    }

    /** Moves in the right column: a node's neighbours, a leaf's points or the postings. */
    private Optional<Request> movePosting(KeyEvent key) {
        if (selectedVectorField().isPresent()) {
            if (knnTree != null) {
                knnTree.shown()
                        .ifPresent(shown -> Selection.moved(
                                        shown.selected, shown.neighbours().size(), key)
                                .ifPresent(row -> shown.selected = row));
            }
            return Optional.empty();
        }
        if (selectedPointField().isPresent()) {
            if (pointTree != null && pointTree.leaf != null) {
                Selection.moved(
                                pointTree.pointSelected,
                                Objects.requireNonNull(pointTree.leaf.points).size(),
                                key)
                        .ifPresent(row -> Objects.requireNonNull(pointTree).pointSelected = row);
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
        return nextRequest();
    }

    public void render(Frame frame, Rect area) {
        if (fields.isEmpty()) {
            message(frame, area, " Fields ", true, "No field has postings, points or vectors.");
            return;
        }
        int nameWidth = fields.stream()
                .map(Terms::fieldLabel)
                .mapToInt(label -> label.codePointCount(0, label.length()))
                .max()
                .orElse(0);
        List<Rect> columns = Layout.horizontal()
                .constraints(
                        Constraint.length(Math.clamp((long) nameWidth + 4, 12, FIELDS_MAX_WIDTH)),
                        Constraint.fill(),
                        Constraint.length(showsGraph() ? neighboursWidth() : POSTINGS_WIDTH))
                .split(area);
        renderFields(frame, columns.get(0));
        if (selectedVectorField().isPresent()) {
            renderGraph(frame, columns.get(1));
            renderNeighbours(frame, columns.get(2));
            return;
        }
        if (selectedPointField().isPresent()) {
            renderPointTree(frame, columns.get(1));
            renderLeaf(frame, columns.get(2));
            return;
        }
        renderTerms(frame, columns.get(1));
        renderPostings(frame, columns.get(2));
    }

    /**
     * A field's name, for its points row with their dimensions and bytes, such as {@code pt 1×8}, and
     * for its graph row with its vectors' dimension, such as {@code hnsw 8d}.
     */
    private static String fieldLabel(FieldRow row) {
        String name = TextFormat.escapeControl(row.field().name());
        return switch (row.kind()) {
            case TERMS -> name;
            case POINTS ->
                name + " pt " + row.field().pointDimensionCount() + "×"
                        + row.field().pointNumBytes();
            case GRAPH -> name + " hnsw " + row.field().vectorDimension() + "d";
        };
    }

    private void renderFields(Frame frame, Rect area) {
        boolean focused = focus == Column.FIELDS;
        List<Row> rows = fields.stream()
                .map(row -> {
                    if (row.kind() == RowKind.TERMS) {
                        return Row.from(Cell.from(fieldLabel(row)));
                    }
                    String name = TextFormat.escapeControl(row.field().name());
                    return Row.from(Cell.from(Line.from(
                            Span.raw(name),
                            Span.styled(
                                    fieldLabel(row).substring(name.length()),
                                    Palette.current().muted()))));
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
        } else if (terms.from != null) {
            title = " " + field + " from " + terms.from + " (/ seeks) ";
        } else {
            title = " " + field + " (/ seeks) ";
        }
        if (terms.entries.isEmpty()) {
            String text;
            if (terms.error != null) {
                text = "Cannot read the terms: " + terms.error;
            } else if (terms.from != null) {
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
                .style(Palette.current().heading());
        List<Row> rows = new ArrayList<>();
        for (TermEntry entry : terms.entries) {
            rows.add(Row.from(
                    Cell.from(TextFormat.bytesText(entry.term(), entry.binary())),
                    right(Integer.toString(entry.docFreq()), docsWidth),
                    right(Long.toString(entry.totalTermFreq()), freqWidth)));
        }
        tailRow(terms.next != null, terms.error).ifPresent(rows::add);
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
        boolean onlyMatches = postings.filter != null && postings.filter.onlyMatches();
        String title = postingsTitle(onlyMatches);
        if (postings.postings.isEmpty()) {
            message(frame, area, title, focused, noPostingsText(onlyMatches));
            return;
        }
        boolean marked = postings.filter != null && !onlyMatches;
        int docWidth = Selection.numberWidth(postings.postings.stream().mapToLong(Posting::docId)) + (marked ? 2 : 0);
        Row header = Row.from(right("Doc", docWidth), right("Freq", 4), Cell.from(""))
                .style(Palette.current().heading());
        List<Row> rows = new ArrayList<>();
        for (Posting posting : postings.postings) {
            rows.add(postingRow(posting, marked, docWidth));
        }
        tailRow(postings.nextDoc != null, postings.error).ifPresent(rows::add);
        postings.table.select(postings.selected);
        Table table = table(rows, focused)
                .widths(Constraint.length(docWidth), Constraint.length(4), Constraint.fill())
                .header(header)
                .block(panel(title, focused))
                .build();
        frame.renderStatefulWidget(table, area, postings.table);
    }

    private String postingsTitle(boolean onlyMatches) {
        if (Objects.requireNonNull(postings).filter == null) {
            return POSTINGS_TITLE + postings.docFreq + ") ";
        }
        return POSTINGS_TITLE + postings.docFreq + (onlyMatches ? ") · filter only " : ") · • filter ");
    }

    private String noPostingsText(boolean onlyMatches) {
        if (Objects.requireNonNull(postings).error != null) {
            return "Cannot read the postings: " + postings.error;
        }
        return onlyMatches ? "No postings in the filter." : "No postings.";
    }

    /** A posting; {@code marked}, it is marked in the filter, or dimmed outside it. */
    private static Row postingRow(Posting posting, boolean marked, int docWidth) {
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
                posting.deleted() ? Cell.from("deleted").style(Palette.current().error()) : Cell.from(""));
        return marked && out ? row.style(Palette.current().muted()) : row;
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
                    "Cannot read the points: " + Objects.requireNonNullElse(pointTree.error, ""));
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
        Style dim = Palette.current().muted();
        for (PointTree.Node node : visible) {
            String marker;
            if (Boolean.TRUE.equals(node.leaf)) {
                marker = "•";
            } else if (node.expanded) {
                marker = "▾";
            } else {
                marker = "▸";
            }
            List<Span> spans = new ArrayList<>();
            spans.add(Span.raw("  ".repeat(node.depth()) + marker + " "));
            if (node.name != null || node.depth() == 0) {
                spans.add(Span.styled(
                        Objects.requireNonNullElse(node.name, "#" + node.segment) + "  ",
                        Palette.current().heading()));
            }
            spans.add(Span.raw(node.size + " pts  "));
            spans.add(Span.styled(format.format(node.min) + " … " + format.format(node.max), dim));
            if (node.loading) {
                spans.add(Span.styled("  …", dim));
            }
            Optional.ofNullable(node.error)
                    .ifPresent(error -> spans.add(Span.styled(
                            "  cannot read it: " + error, Palette.current().error())));
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
                ? " Points (" + Objects.requireNonNull(leaf.points).size() + " of " + leaf.size + ") "
                : " Points (" + Objects.requireNonNull(leaf.points).size() + ") ";
        int docWidth = Selection.numberWidth(leaf.points.stream().mapToLong(LeafPoint::docId));
        Row header = Row.from(right("Doc", docWidth), Cell.from("Value"))
                .style(Palette.current().heading());
        List<Row> rows = new ArrayList<>();
        for (LeafPoint point : leaf.points) {
            Row row =
                    Row.from(right(Integer.toString(point.docId()), docWidth), Cell.from(format.format(point.value())));
            rows.add(point.deleted() ? row.style(Palette.current().error()) : row);
        }
        pointTree.pointTable.select(pointTree.pointSelected);
        Table table = table(rows, focused)
                .widths(Constraint.length(docWidth), Constraint.fill())
                .header(header)
                .block(panel(title, focused))
                .build();
        frame.renderStatefulWidget(table, area, pointTree.pointTable);
    }

    private void renderGraph(Frame frame, Rect area) {
        boolean focused = focus == Column.TERMS;
        IndexField field = selectedVectorField().orElseThrow();
        String name = TextFormat.firstChars(TextFormat.escapeControl(field.name()), 30);
        int width = area.width() - 2;
        if (!features.contains(Feature.KNN_GRAPH)) {
            message(
                    frame,
                    area,
                    fitTitle(name, "", List.of(), width),
                    focused,
                    "The runtime that reads this index doesn't read HNSW graphs.");
            return;
        }
        if (knnTree == null) {
            frame.renderWidget(panel(fitTitle(name, "", List.of(), width), focused), area);
            return;
        }
        if (knnTree.graph == null) {
            message(
                    frame,
                    area,
                    fitTitle(name, "", List.of(), width),
                    focused,
                    "Cannot read the graphs: " + Objects.requireNonNullElse(knnTree.error, ""));
            return;
        }
        GetKnnGraphResult graph = knnTree.graph;
        String vectors = TextFormat.count(
                graph.segments().stream().mapToLong(KnnGraphSegment::vectors).sum(), "vector");
        String similarity = " · " + graph.similarity().name().toLowerCase(java.util.Locale.ROOT);
        String title = fitTitle(
                name,
                note != null ? " · " + note : "",
                List.of(
                        " · " + TextFormat.count(graph.segments().size(), "segment") + " · " + vectors + similarity,
                        " · " + vectors + similarity,
                        similarity),
                width);
        if (knnTree.segments.isEmpty()) {
            message(frame, area, title, focused, "No segment has vectors in the field.");
            return;
        }
        List<KnnGraphTree.Item> visible = knnTree.visible();
        knnTree.selected = Math.min(knnTree.selected, visible.size() - 1);
        List<Row> rows = new ArrayList<>();
        Style dim = Palette.current().muted();
        for (KnnGraphTree.Item item : visible) {
            Row row = Row.from(Cell.from(Line.from(itemSpans(item, dim))));
            rows.add(deleted(item) ? row.style(Palette.current().error()) : row);
        }
        knnTree.table.select(knnTree.selected);
        Table table = table(rows, focused)
                .widths(Constraint.fill())
                .block(panel(title, focused))
                .build();
        frame.renderStatefulWidget(table, area, knnTree.table);
    }

    /** A row of the graph tree: a segment, its entry point, a level or a node of it. */
    private static List<Span> itemSpans(KnnGraphTree.Item item, Style dim) {
        List<Span> spans = new ArrayList<>();
        switch (item) {
            case KnnGraphTree.Item.SegmentItem segment -> {
                KnnGraphSegment info = segment.segment().info;
                String marker = segment.segment().expanded ? "▾ " : "▸ ";
                spans.add(Span.raw(info.shape().isEmpty() ? "  " : marker));
                spans.add(Span.styled(
                        info.name().orElse("#" + info.segment()) + "  ",
                        Palette.current().heading()));
                spans.add(Span.raw(TextFormat.count(info.vectors(), "vector")));
                spans.add(Span.styled(info.shape().map(Terms::shapeText).orElse(" · no graph"), dim));
            }
            case KnnGraphTree.Item.EntryItem entry -> {
                spans.add(Span.styled("    entry  ", dim));
                spans.add(Span.raw(nodeText(entry.node())));
            }
            case KnnGraphTree.Item.LevelItem levelItem -> {
                KnnGraphTree.Level level = levelItem.level();
                spans.add(Span.raw("  " + (level.expanded ? "▾" : "▸") + " L" + level.number + "  "));
                spans.add(Span.raw(TextFormat.count(level.total, "node")));
                if (level.loading) {
                    spans.add(Span.styled("  …", dim));
                }
                Optional.ofNullable(level.error)
                        .ifPresent(error -> spans.add(Span.styled(
                                "  cannot read it: " + error, Palette.current().error())));
            }
            case KnnGraphTree.Item.NodeItem nodeItem -> {
                spans.add(Span.raw("      " + nodeText(nodeItem.node())));
                if (nodeItem.node().topLevel() > nodeItem.level().number) {
                    spans.add(Span.styled(" · top L" + nodeItem.node().topLevel(), dim));
                }
            }
            case KnnGraphTree.Item.MoreItem more ->
                spans.add(Span.styled(
                        "      ⏎ more (" + more.level().nodes.size() + " of " + more.level().total + ")", dim));
        }
        return spans;
    }

    private static boolean deleted(KnnGraphTree.Item item) {
        return switch (item) {
            case KnnGraphTree.Item.EntryItem entry -> entry.node().deleted();
            case KnnGraphTree.Item.NodeItem nodeItem -> nodeItem.node().deleted();
            default -> false;
        };
    }

    /** A graph's levels and M, such as {@code · 3 levels · M 16}. */
    private static String shapeText(KnnGraphShape shape) {
        String levels = " · " + TextFormat.count(shape.levelSizes().size(), "level");
        return shape.maxConn().isPresent() ? levels + " · M " + shape.maxConn().getAsInt() : levels;
    }

    /** A node, such as {@code ord 17 · doc 42}, and whether its document is deleted. */
    private static String nodeText(KnnGraphNode node) {
        return "ord " + node.ord() + " · doc " + node.docId() + (node.deleted() ? " · deleted" : "");
    }

    /**
     * The width of the neighbours column: as wide as the graphs' largest ordinal and document need,
     * from the postings' width to {@link #NEIGHBOURS_WIDTH}, which leaves a narrow terminal's middle
     * column the rest.
     */
    private int neighboursWidth() {
        if (knnTree == null || knnTree.graph == null) {
            return POSTINGS_WIDTH;
        }
        List<KnnGraphSegment> segments = knnTree.graph.segments();
        int ordWidth = Selection.numberWidth(segments.stream().mapToLong(segment -> segment.vectors() - 1L));
        int docWidth = Selection.numberWidth(
                segments.stream().mapToLong(segment -> segment.docBase() + segment.maxDoc() - 1L));
        return Math.clamp((long) NEIGHBOURS_FIXED_WIDTH + ordWidth + docWidth, POSTINGS_WIDTH, NEIGHBOURS_WIDTH);
    }

    private void renderNeighbours(Frame frame, Rect area) {
        boolean focused = focus == Column.POSTINGS;
        Optional<KnnGraphTree.Shown> shown = knnTree == null ? Optional.empty() : knnTree.shown();
        Optional<String> error = knnTree == null ? Optional.empty() : Optional.ofNullable(knnTree.nodeError);
        if (shown.isEmpty()) {
            String text = error.map(e -> "Cannot read the node: " + e).orElse("⏎ on a node shows its neighbours.");
            message(frame, area, " Neighbours ", focused, text);
            return;
        }
        GetKnnGraphNodeResult node = shown.get().node;
        List<String> ords = Objects.requireNonNull(knnTree).path.stream()
                .map(step -> Integer.toString(step.node.node().ord()))
                .toList();
        // A step that failed says so in the title, which the walk's ordinals give otherwise.
        String title = error.map(e -> TextFormat.firstChars(" cannot read the node: " + e, area.width() - 3) + " ")
                .orElseGet(() -> walkTitle(ords, area.width() - 2));
        List<KnnGraphNeighbour> neighbours = shown.get().neighbours();
        int ordWidth = Math.max(
                3,
                Selection.numberWidth(LongStream.concat(
                        LongStream.of(node.node().ord()),
                        neighbours.stream().mapToLong(n -> n.node().ord()))));
        int docWidth = Math.max(
                3,
                Selection.numberWidth(LongStream.concat(
                        LongStream.of(node.node().docId()),
                        neighbours.stream().mapToLong(n -> n.node().docId()))));
        Row header = Row.from(
                        Cell.from("Lvl"),
                        right("Ord", ordWidth),
                        right("Doc", docWidth),
                        Cell.from("Top"),
                        right("Score", 6))
                .style(Palette.current().heading());
        List<Row> rows = new ArrayList<>();
        // The node itself first, then each level's neighbours, the level on the first of them.
        Row self = Row.from(
                        Cell.from("node"),
                        right(Integer.toString(node.node().ord()), ordWidth),
                        right(Integer.toString(node.node().docId()), docWidth),
                        Cell.from("L" + node.node().topLevel()),
                        Cell.from(""))
                .style(
                        node.node().deleted()
                                ? Palette.current()
                                        .heading()
                                        .patch(Palette.current().error())
                                : Palette.current().heading());
        rows.add(self);
        int selectedRow = addLevelRows(rows, node, shown.get().selected, ordWidth, docWidth);
        shown.get().table.select(neighbours.isEmpty() ? 0 : selectedRow);
        Table table = table(rows, focused)
                .widths(
                        Constraint.length(4),
                        Constraint.length(ordWidth),
                        Constraint.length(docWidth),
                        Constraint.length(3),
                        Constraint.length(6))
                .header(header)
                .block(panel(title, focused))
                .build();
        frame.renderStatefulWidget(table, area, shown.get().table);
    }

    /**
     * Adds each level's neighbours, the level on the first of them, and returns the row of neighbour
     * {@code selected}.
     */
    private static int addLevelRows(
            List<Row> rows, GetKnnGraphNodeResult node, int selected, int ordWidth, int docWidth) {
        Style dim = Palette.current().muted();
        int selectedRow = 0;
        int index = 0;
        for (KnnGraphLevel level : node.levels()) {
            if (level.neighbours().isEmpty()) {
                rows.add(Row.from(Cell.from("L" + level.level()), Cell.from("none"))
                        .style(dim));
                continue;
            }
            boolean first = true;
            for (KnnGraphNeighbour neighbour : level.neighbours()) {
                if (index == selected) {
                    selectedRow = rows.size();
                }
                Row row = Row.from(
                        Cell.from(first ? "L" + level.level() : ""),
                        right(Integer.toString(neighbour.node().ord()), ordWidth),
                        right(Integer.toString(neighbour.node().docId()), docWidth),
                        Cell.from("L" + neighbour.node().topLevel()),
                        right(TextFormat.score(neighbour.score(), 6), 6));
                rows.add(
                        neighbour.node().deleted() ? row.style(Palette.current().error()) : row);
                first = false;
                index++;
            }
        }
        return selectedRow;
    }

    /**
     * The title of a walk: the ordinals it went through, such as {@code ord 17 › 3 › 9}, its start
     * left out as far as {@code width} needs.
     */
    static String walkTitle(List<String> ords, int width) {
        for (int from = 0; from < ords.size(); from++) {
            String title =
                    " ord " + (from > 0 ? "… › " : "") + String.join(" › ", ords.subList(from, ords.size())) + " ";
            if (CharWidth.of(title) <= width || from == ords.size() - 1) {
                return title;
            }
        }
        return " Neighbours ";
    }

    /** Whether the selected field's HNSW graphs are shown rather than its terms (ADR 0085). */
    public boolean showsGraph() {
        return selectedVectorField().isPresent();
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

    /** What a key that works on terms only says on a points or graph field. */
    private static String termsOnly(KeyEvent key) {
        if (key.isChar('/')) {
            return "/ seeks terms only";
        }
        return "E exports terms only";
    }
}
