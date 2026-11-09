package io.github.mfuerstenau.leia.tui.documents;

import static io.github.mfuerstenau.leia.tui.widgets.Keys.Group.DO;
import static io.github.mfuerstenau.leia.tui.widgets.Keys.Group.FIND;
import static io.github.mfuerstenau.leia.tui.widgets.Keys.Group.MOVE;
import static io.github.mfuerstenau.leia.tui.widgets.Keys.Group.SHOW;
import static io.github.mfuerstenau.leia.tui.widgets.Keys.Group.SORT;
import static io.github.mfuerstenau.leia.tui.widgets.Panels.highlight;
import static io.github.mfuerstenau.leia.tui.widgets.Panels.panel;
import static io.github.mfuerstenau.leia.tui.widgets.Panels.right;

import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Layout;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.Title;
import dev.tamboui.widgets.paragraph.Paragraph;
import dev.tamboui.widgets.table.Cell;
import dev.tamboui.widgets.table.Row;
import dev.tamboui.widgets.table.Table;
import dev.tamboui.widgets.table.TableState;
import io.github.mfuerstenau.leia.client.values.FieldFlags;
import io.github.mfuerstenau.leia.client.values.PointFormat;
import io.github.mfuerstenau.leia.protocol.document.BytesValue;
import io.github.mfuerstenau.leia.protocol.document.DocValues;
import io.github.mfuerstenau.leia.protocol.document.DocumentField;
import io.github.mfuerstenau.leia.protocol.document.GetDocumentResult;
import io.github.mfuerstenau.leia.protocol.document.GetReconstructResult;
import io.github.mfuerstenau.leia.protocol.document.KnnVector;
import io.github.mfuerstenau.leia.protocol.document.ReconstructResult;
import io.github.mfuerstenau.leia.protocol.document.StoredType;
import io.github.mfuerstenau.leia.protocol.document.StoredValue;
import io.github.mfuerstenau.leia.protocol.document.TermPosition;
import io.github.mfuerstenau.leia.protocol.document.TermVectorTerm;
import io.github.mfuerstenau.leia.protocol.filter.FilterMatch;
import io.github.mfuerstenau.leia.protocol.filter.GetFilterResult;
import io.github.mfuerstenau.leia.protocol.filter.StartFilterParams;
import io.github.mfuerstenau.leia.protocol.filter.StepFilterParams;
import io.github.mfuerstenau.leia.protocol.index.DocValuesType;
import io.github.mfuerstenau.leia.protocol.index.Feature;
import io.github.mfuerstenau.leia.protocol.index.IndexField;
import io.github.mfuerstenau.leia.protocol.index.IndexOptions;
import io.github.mfuerstenau.leia.protocol.points.PointValue;
import io.github.mfuerstenau.leia.protocol.search.DocValuesNumber;
import io.github.mfuerstenau.leia.protocol.search.DocumentHighlight;
import io.github.mfuerstenau.leia.protocol.search.FieldMatches;
import io.github.mfuerstenau.leia.protocol.search.GetHighlightResult;
import io.github.mfuerstenau.leia.protocol.search.HighlightOptions;
import io.github.mfuerstenau.leia.protocol.search.MatchSpan;
import io.github.mfuerstenau.leia.protocol.search.SearchQuery;
import io.github.mfuerstenau.leia.protocol.search.StartHighlightParams;
import io.github.mfuerstenau.leia.protocol.search.ValueHighlight;
import io.github.mfuerstenau.leia.tui.documents.DocFilter.FilterStep;
import io.github.mfuerstenau.leia.tui.overview.Overview;
import io.github.mfuerstenau.leia.tui.points.PointFormats;
import io.github.mfuerstenau.leia.tui.search.Search;
import io.github.mfuerstenau.leia.tui.terms.Terms.TermHit;
import io.github.mfuerstenau.leia.tui.text.TextFormat;
import io.github.mfuerstenau.leia.tui.theme.Palette;
import io.github.mfuerstenau.leia.tui.widgets.GoMenu;
import io.github.mfuerstenau.leia.tui.widgets.Jump;
import io.github.mfuerstenau.leia.tui.widgets.Keys;
import io.github.mfuerstenau.leia.tui.widgets.Panels;
import io.github.mfuerstenau.leia.tui.widgets.Selection;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The documents screen: one document of the open index at a time, field by field, as {@code
 * getDocument} reports it (ADR 0006), with a panel for the selected field's values and its term
 * vector. A document opened from the terms screen also shows where the term occurs in it, and
 * {@code [}/{@code ]} step through the term's other documents. {@code v} opens the selected field's
 * values in the {@link Viewer}, and {@code I} rebuilds the terms the field was indexed with (ADR
 * 0029). Through a {@link DocFilter}, ←/→ step through the documents that match a query only (ADR
 * 0031).
 *
 * <p>A rebuild belongs to its document and field, and stops as soon as another document is asked
 * for; a job that starts for a rebuild no longer wanted is cancelled, and progress of any job but the
 * running one is dropped. Likewise, the filter's calls carry its serial.
 *
 * <p>The Rust TUI got each reply before the next key; here a document can come after another one
 * was asked for. So the screen remembers the document it asked for last ({@link #want}), and drops
 * the others: they would use up the field to select and the value to view that belong to it. Term
 * vectors, positions and whole stored values are checked against the shown document.
 */
// A screen that wires its own forms and popups (ADR 0088)
@SuppressWarnings("java:S6539")
public final class Documents {

    private static final String AS = ", as ";

    private static final String FIELDS_TITLE = " Fields (";

    private static final String FLAGS_LABEL = "Flags";

    private static final String TERM_VECTOR = "Term vector";

    /** From this width on, the details panel is next to the fields instead of below them. */
    private static final int SIDE_BY_SIDE_WIDTH = 130;

    /** The width of the details panel next to the fields. */
    private static final int DETAILS_PANEL_WIDTH = 64;

    /** The value column shows at most this many characters of a value. */
    private static final int SUMMARY_CHARS = 200;

    /** The details show at most this many characters of a stored value, and this many lines. */
    private static final int DETAIL_VALUE_CHARS = 2000;

    private static final int DETAIL_VALUE_LINES = 20;

    /** Vector components on one line of the details. */
    static final int VECTOR_LINE = 4;

    /** The name column is at most this wide. */
    private static final int NAME_MAX_WIDTH = 32;

    private static final int COLUMN_SPACING = 2;

    /** A space and {@code ▲} or {@code ▼} after the title of the sorted column. */
    private static final int SORT_MARKER_WIDTH = 2;

    /** Which documents {@code [}/{@code ]} step through. */
    public enum Walk {
        /** The documents of the term of the {@link TermHit}, from the terms screen. */
        TERM_DOCS,
        /** The hits of the search screen. */
        SEARCH_HITS
    }

    /** What the caller has to fetch for the documents screen. */
    public sealed interface Request {

        /** The document with this id, reported back with {@link #loaded} or {@link #failed}. */
        record Document(int docId) implements Request {}

        /**
         * The term vector of a field of the shown document, reported back with {@link
         * #termVectorLoaded} or {@link #termVectorFailed}.
         */
        record TermVector(int docId, String field) implements Request {}

        /**
         * Where the term of the {@link TermHit} occurs in its document, reported back with {@link
         * #positionsLoaded} or {@link #positionsFailed}.
         */
        record Positions(int docId, String field, BytesValue term) implements Request {}

        /** The previous or next document of the {@link Walk}. */
        record Step(Walk walk, boolean forward) implements Request {}

        /**
         * A stored value whole, which {@code getDocument} cut, reported back with {@link
         * #storedValueLoaded} or {@link #storedValueFailed}.
         */
        record StoredValue(int docId, String field, int index) implements Request {}

        /** Put this text on the clipboard. */
        record Copy(String text) implements Request {}

        /** Search for documents like this one on the search screen (ADR 0013). */
        record MoreLikeThis(int docId) implements Request {}

        /** Analyze a value of the document on the analysis screen (ADR 0014). */
        record Analyze(int docId, String field, String text) implements Request {}

        /**
         * Search the documents with any of these doc values of the field on the search screen (ADR
         * 0033): numbers as text in {@code number}, bytes as text, or as hex if {@code hex}.
         */
        record SearchDocValues(String field, DocValuesNumber number, boolean hex, List<String> values)
                implements Request {}

        /** Search the documents nearest to this one's vector in the field on the search screen (ADR 0040). */
        record SearchKnn(String field, int docId) implements Request {}

        /**
         * Cancel the jobs in {@code cancel}, then rebuild the terms a field of the document was
         * indexed with (ADR 0029), reported back with {@link #reconstructStarted} or {@link
         * #reconstructStartFailed}; the job with {@link #reconstructProgress} or {@link
         * #reconstructFailed}.
         */
        record Reconstruct(int docId, String field, List<Long> cancel) implements Request {}

        /**
         * Cancel the job {@code cancel} and release the filter {@code release} of the filter this one
         * replaces, then build filter {@code serial} (ADR 0031), reported back with {@link
         * #filterStarted} or {@link #filterFailed}; the job with {@link #filterProgress} or {@link
         * #filterFailed}.
         */
        record StartFilter(long serial, StartFilterParams params, OptionalLong cancel, OptionalLong release)
                implements Request {}

        /**
         * Find a match of filter {@code serial}, reported back with {@link #filterStepped} or {@link
         * #filterFailed}.
         */
        record StepFilter(long serial, StepFilterParams params, FilterStep step) implements Request {}

        /** Forget the filter: cancel the job that builds it, or release the filter the backend keeps. */
        record ClearFilter(OptionalLong cancel, OptionalLong release) implements Request {}

        /** Ask where to export this document to as JSON (ADR 0030). */
        record Export(int docId) implements Request {}

        /** A key that the index lacks a feature for (ADR 0044); {@code what} names it. Nothing changed. */
        record Unsupported(String what) implements Request {}

        /**
         * Show the segment {@code segment} of the index directory at {@code index} in its opened
         * commit on the commits screen (ADR 0052).
         */
        record ShowCommit(int index, String segment) implements Request {}

        /**
         * Show {@code field} on the terms screen (ADR 0052): with {@code points} its BKD tree, with a
         * term its terms from there on, and otherwise its terms from the first one.
         */
        record BrowseTerms(String field, Optional<BytesValue> term, boolean points) implements Request {}

        /** Show the HNSW graph node of this document's vector in {@code field} on the terms screen (ADR 0085). */
        record BrowseGraph(String field, int docId) implements Request {}

        /** {@code g} in the viewer: open the go-to menu with {@link #jumps()} (ADR 0120). */
        record GoTo() implements Request {}

        /** {@code ?} in the viewer: open the key list with the viewer's keys. */
        record Help() implements Request {}
    }

    /** The filter to locate a document in once it is loaded: its serial and the backend's id. */
    public record Locate(long serial, long filterId) {}

    /** A search's query and how it highlights, to mark its matches in a hit opened from it (ADR 0043). */
    public record MatchQuery(SearchQuery query, HighlightOptions options) {}

    /**
     * Find the matches {@code matchesId} with a document's load, reported back with {@link
     * #matchesStarted} or {@link #matchesFailed}; the job with {@link #matchesProgress}.
     */
    public record MatchesStart(long matchesId, StartHighlightParams params) {}

    /** The matches of a search in the hit it opened (ADR 0043). */
    private static final class Matches {
        final long id;
        final int docId;
        final MatchQuery query;

        /** Whether the load of the document asked for them. */
        boolean sent;

        /** Null until the backend has started the job. */
        Long jobId;

        /** Per field with a text value, once they are in; null before. */
        Map<String, FieldMatches> fields;

        /** Why they could not be found; or null. */
        String error;

        Matches(long id, int docId, MatchQuery query) {
            this.id = id;
            this.docId = docId;
            this.query = query;
        }

        boolean running() {
            return sent && fields == null && error == null;
        }
    }

    /** The term vector of a field of the shown document. */
    private sealed interface TermVector {
        record Loaded(List<TermVectorTerm> terms) implements TermVector {}

        record Failed(String error) implements TermVector {}
    }

    /** Where the hit's term occurs in its document. */
    private sealed interface Positions {
        record Loaded(List<TermPosition> positions) implements Positions {}

        record Failed(String error) implements Positions {}
    }

    /** A stored value to view with its matches once its document is in (ADR 0027). */
    private record PendingView(int docId, String field, int index, List<MatchSpan> spans, int matchCount) {}

    /**
     * An index directory: the document id it starts at, its name as shown, and the major Lucene
     * version that created it, which says how its norms are encoded.
     */
    public record IndexStart(int docBase, String name, int createdVersionMajor) {}

    /**
     * A segment of the open reader (ADR 0051): the position of its index directory among those
     * opened, its name, the document id it starts at in the opened path, and its documents, deleted
     * ones included, and live ones.
     */
    public record SegmentStart(int index, String name, int docBase, int maxDoc, int numDocs) {

        /** The document id after its last document. */
        int end() {
            return docBase + maxDoc;
        }

        boolean contains(int docId) {
            return docId >= docBase && docId < end();
        }

        DocFilter.Range range() {
            return new DocFilter.Range(name, docBase, end());
        }
    }

    /** What the navigation keys move. */
    private enum Focus {
        FIELDS,
        DETAILS
    }

    /** What the field table is sorted by. */
    private enum SortKey {
        NUMBER,
        NAME;

        SortKey next() {
            return this == NUMBER ? NAME : NUMBER;
        }

        /** The column whose header shows the sort. */
        int column() {
            return this == NUMBER ? 0 : 1;
        }
    }

    /** The go-to-document prompt. */
    private static final class DocIdPrompt {
        final StringBuilder input = new StringBuilder();

        /** Or null. */
        String error;
    }

    /** How each field of the index is indexed, by name, from {@code getIndexStats}. */
    private final Map<String, IndexField> schema;

    private final int maxDoc;

    /** The document shown, or the one that failed to load. */
    private int docId;

    /** Or null. */
    private GetDocumentResult document;

    /** Why {@link #docId} could not be read; or null. */
    private String error;

    /** The document asked for last; a reply for another one is dropped. Empty before. */
    private OptionalInt wanted = OptionalInt.empty();

    /**
     * The serial of the filter that a ←/→ step goes through, until the document it finds is shown;
     * empty if no step is under way. The keys pressed meanwhile wait in {@link #queuedSteps}, so that
     * each one steps on from the match before it, as in the Rust TUI, where a step blocked the keys.
     */
    private OptionalLong steppingFilter = OptionalLong.empty();

    /** The ←/→ steps through the filter pressed while one is under way: true for forward. */
    private final Deque<Boolean> queuedSteps = new ArrayDeque<>();

    /** The rows of the field table, as indexes into {@code document.fields()}. */
    private final List<Integer> rows = new ArrayList<>();

    /** The selected row; -1 without rows. */
    private int row = -1;

    private final TableState table = new TableState();

    /** Whether fields in which the document has nothing are left out. */
    private boolean hideEmpty;

    private SortKey sort = SortKey.NUMBER;

    private boolean descending;

    private NumberFormat numbers = NumberFormat.LONG;

    /** The format of each point field, shared with the terms screen (ADR 0041). */
    private PointFormats pointFormats = new PointFormats();

    /** Term vectors of the shown document, by field name. */
    private final Map<String, TermVector> termVectors = new HashMap<>();

    /** The rebuilt fields of the shown document, by field name; at most one is active. */
    private final Map<String, Reconstruction> reconstructions = new HashMap<>();

    /** The filter that ←/→ step through (ADR 0031); or null. */
    private DocFilter filter;

    /** The serial of the next filter. */
    private long nextFilterSerial = 1;

    private Focus focus = Focus.FIELDS;

    /** The first line of the details panel that is shown. */
    private int detailsScroll;

    /** Or null. */
    private DocIdPrompt prompt;

    /** The text typed into the field seek while it is open; null otherwise. */
    private StringBuilder seek;

    /** The term and document that the terms screen opened; or null. */
    private TermHit hit;

    /** Whether the search screen opened the document, so {@code [}/{@code ]} step through its hits. */
    private boolean searchHits;

    /** Where the hit's term occurs in its document, once fetched; or null. */
    private Positions positions;

    /** The field to select once the next document is in; or null. */
    private String selectField;

    /** The values of the selected field, viewed over the whole screen; or null. */
    private Viewer viewer;

    /** The value the viewer opens on once its document is in; or null. */
    private PendingView pendingView;

    /** The matches of the search hit shown, or last shown; or null. */
    private Matches matches;

    private long nextMatchesId = 1;

    /** The jobs of matches dropped while they ran, to cancel with the next load. */
    private final List<Long> matchesToCancel = new ArrayList<>();

    /** The delimiters the viewer last split values at, for the next viewer. */
    private Viewer.Delimiters delimiters = Viewer.Delimiters.DEFAULT;

    /**
     * The index directories, where each starts, its name as shown and its version, in order (ADR
     * 0021).
     */
    private List<IndexStart> indexes = List.of();

    /** The open reader's segments of every index directory, in document order (ADR 0051). */
    private List<SegmentStart> segments = List.of();

    /** The segment that ←/→ stay in (ADR 0051); or null. */
    private SegmentStart pinned;

    /**
     * Said about the last key until the next one, such as that ← is at the pin's first document, or
     * that a field has no terms; or null.
     */
    private String note;

    /**
     * The segment that {@code <}/{@code >} step to the first match of while that step is under way:
     * the step stays in it, and its first document is shown if it has no match; or null.
     */
    private SegmentStart segmentJump;

    /** What the index can do (ADR 0044): the keys it lacks a feature for are refused and left out of the legend. */
    private Set<Feature> features = Feature.all();

    public Documents(List<IndexField> fields, int maxDoc) {
        this.schema = fields.stream()
                .collect(Collectors.toMap(IndexField::name, Function.identity(), (a, b) -> a, HashMap::new));
        this.maxDoc = maxDoc;
    }

    /**
     * Shows which index directory a document is in: each with the document id it starts at, its
     * name and its version, in order. With one, no name is shown.
     */
    public Documents withIndexes(List<IndexStart> indexes) {
        this.indexes = List.copyOf(indexes);
        return this;
    }

    /**
     * Shows which segment of the open reader a document is in, and lets {@code P} pin it (ADR
     * 0051): {@code segments} in document order.
     */
    public Documents withSegments(List<SegmentStart> segments) {
        this.segments = List.copyOf(segments);
        return this;
    }

    /** Shows points in {@code formats}, which the terms screen shares. */
    public Documents withPointFormats(PointFormats formats) {
        this.pointFormats = formats;
        return this;
    }

    /** Offers only the keys that {@code features} allow (ADR 0044). */
    public Documents withFeatures(Set<Feature> features) {
        this.features = Set.copyOf(features);
        return this;
    }

    private boolean supports(Feature... needed) {
        return features.containsAll(List.of(needed));
    }

    /** Whether {@code g n} can search the nearest vectors, with the classic search (ADR 0049). */
    private boolean nearest() {
        return supports(Feature.KNN_VECTORS) && supports(Feature.CLASSIC_SEARCH);
    }

    /** Whether {@code g s} can search a doc value (ADR 0066). */
    private boolean searchesValues() {
        return supports(Feature.DOC_VALUES) && supports(Feature.DOC_VALUES_SEARCH);
    }

    /** Whether {@code g m} can find similar documents (ADR 0066). */
    private boolean findsSimilar() {
        return supports(Feature.MORE_LIKE_THIS);
    }

    /** What the index says about the field {@code name}; empty if it doesn't know it. */
    public Optional<IndexField> field(String name) {
        return Optional.ofNullable(schema.get(name));
    }

    /** The name of the index directory that {@code docId} is in, with several of them. */
    public Optional<String> indexOf(int docId) {
        return indexes.size() > 1 ? indexStart(docId).map(IndexStart::name) : Optional.empty();
    }

    /**
     * The major Lucene version that created the index {@code docId} is in; with no indexes listed,
     * one with today's norms.
     */
    int createdVersionMajorOf(int docId) {
        return indexStart(docId).map(IndexStart::createdVersionMajor).orElse(LENGTH_NORMS_SINCE);
    }

    private Optional<IndexStart> indexStart(int docId) {
        IndexStart in = null;
        for (IndexStart index : indexes) {
            if (index.docBase() > docId) {
                break;
            }
            in = index;
        }
        return Optional.ofNullable(in);
    }

    /** The segment of the open reader that {@code docId} is in, if the segments are known. */
    Optional<SegmentStart> segmentOf(int docId) {
        int low = 0;
        int high = segments.size() - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            SegmentStart segment = segments.get(mid);
            if (docId < segment.docBase()) {
                high = mid - 1;
            } else if (docId >= segment.end()) {
                low = mid + 1;
            } else {
                return Optional.of(segment);
            }
        }
        return Optional.empty();
    }

    /** The segment {@code name} of the index directory at {@code index}, if the open reader has it. */
    public Optional<SegmentStart> segment(int index, String name) {
        return segments.stream()
                .filter(segment -> segment.index() == index && segment.name().equals(name))
                .findFirst();
    }

    /**
     * Pins {@code segment}, as the commits screen asks for (ADR 0051), and forgets the term hit or
     * search hits. Returns its first document to fetch.
     */
    public Request showSegment(SegmentStart segment) {
        hit = null;
        searchHits = false;
        positions = null;
        dropMatches();
        pinned = segment;
        clearNotes();
        return new Request.Document(segment.docBase());
    }

    /** Forgets what was said about the last move: another screen shows a document, or a filter starts. */
    private void clearNotes() {
        note = null;
        if (filter != null) {
            filter.clearNote();
        }
    }

    /** Unpins the segment if {@code docId} is outside it, and says so. */
    private void leaveSegment(int docId) {
        if (pinned != null && !pinned.contains(docId)) {
            note = "left segment " + pinned.name();
            pinned = null;
        }
    }

    /**
     * The screen for the reopened index (ADR 0019): the same document id, clamped to the new {@code
     * maxDoc}, with the same field selected, and values shown as before. The document is fetched
     * again; walks through term docs or hits end. The backend forgets the filter, which is built
     * again under a new serial (ADR 0032). A pinned segment that the new reader still has stays
     * pinned, with the same document of it shown; one merged away is dropped (ADR 0051).
     */
    public Documents reopened(List<IndexField> fields, int maxDoc, List<SegmentStart> segments) {
        Documents documents = new Documents(fields, maxDoc);
        documents.indexes = indexes;
        documents.segments = List.copyOf(segments);
        documents.features = features;
        documents.docId = Math.clamp(docId, 0, Math.max(maxDoc - 1, 0));
        if (pinned != null) {
            Optional<SegmentStart> same = documents.segment(pinned.index(), pinned.name());
            if (same.isPresent()) {
                documents.pinned = same.get();
                documents.docId = same.get().docBase()
                        + Math.clamp(
                                (long) docId - pinned.docBase(), 0, same.get().maxDoc() - 1);
            } else {
                documents.note = "segment " + pinned.name() + " is gone";
            }
        }
        documents.selectField = selectedField().map(DocumentField::name).orElse(null);
        documents.hideEmpty = hideEmpty;
        documents.sort = sort;
        documents.descending = descending;
        documents.numbers = numbers;
        documents.pointFormats = pointFormats;
        documents.delimiters = delimiters;
        documents.nextFilterSerial = nextFilterSerial;
        if (filter != null) {
            documents.filter = filter.rebuild(documents.nextFilterSerial++);
        }
        return documents;
    }

    /** The first document to fetch when the screen is shown, if nothing was fetched yet. */
    public Optional<Request> initialRequest() {
        return document == null && error == null && maxDoc > 0 && wanted.isEmpty()
                ? Optional.of(new Request.Document(docId))
                : Optional.empty();
    }

    /** {@code docId} is fetched: a reply for any other document is dropped from now on. */
    public void want(int docId) {
        wanted = OptionalInt.of(docId);
    }

    /** Whether a reply for {@code docId} is the one wanted. */
    private boolean isWanted(int docId) {
        return wanted.isEmpty() || wanted.getAsInt() == docId;
    }

    /**
     * Shows the document of {@code hit} once it is loaded, with the hit's field selected and where its
     * term occurs in it.
     */
    public void showHit(TermHit hit) {
        selectField = hit.field();
        this.hit = hit;
        searchHits = false;
        positions = null;
        dropMatches();
        clearNotes();
    }

    /**
     * Forgets the term hit: the terms screen opened a document with a point of {@code field} (ADR
     * 0041), or a vector (ADR 0085), which is selected once the document is in.
     */
    public void showPoint(String field) {
        selectField = field;
        hit = null;
        searchHits = false;
        positions = null;
        dropMatches();
        clearNotes();
    }

    /**
     * Forgets the term hit: the search screen opened hit {@code docId}, so {@code [}/{@code ]} step
     * through its hits. With {@code query}, the next load of the document finds the query's matches in
     * it (ADR 0043), unless they are in already.
     */
    public void showSearchHit(int docId, Optional<MatchQuery> query) {
        hit = null;
        searchHits = true;
        positions = null;
        clearNotes();
        boolean same = matches != null
                && matches.docId == docId
                && query.isPresent()
                && matches.query.equals(query.get())
                && matches.error == null;
        if (!same) {
            dropMatches();
            query.ifPresent(q -> matches = new Matches(nextMatchesId++, docId, q));
        }
    }

    /** Forgets the matches, and stops their job with the next load if it runs. */
    private void dropMatches() {
        if (matches != null && matches.running() && matches.jobId != null) {
            matchesToCancel.add(matches.jobId);
        }
        matches = null;
    }

    /** Forgets the matches that {@link #showSearchHit} asked for if no load fetched them: the document was shown already. */
    public void forgetUnsentMatches() {
        if (matches != null && !matches.sent) {
            matches = null;
        }
    }

    /**
     * The matches to find with the load of {@code docId}, if {@link #showSearchHit} asked for them;
     * adds the jobs of dropped matches to {@code cancel}.
     */
    public Optional<MatchesStart> matchesFor(int docId, List<Long> cancel) {
        cancel.addAll(matchesToCancel);
        matchesToCancel.clear();
        if (matches == null || matches.sent || matches.docId != docId) {
            return Optional.empty();
        }
        matches.sent = true;
        return Optional.of(new MatchesStart(
                matches.id,
                new StartHighlightParams(matches.query.query(), docId, Optional.empty(), matches.query.options())));
    }

    private boolean findsMatches(long matchesId) {
        return matches != null && matches.id == matchesId && matches.running();
    }

    /** The job of matches {@code matchesId} has started. Returns the job to cancel if they were dropped. */
    public OptionalLong matchesStarted(long matchesId, long jobId) {
        if (!findsMatches(matchesId)) {
            return OptionalLong.of(jobId);
        }
        matches.jobId = jobId;
        return OptionalLong.empty();
    }

    /** The matches job to poll, while it runs: the matches' id and the job id. */
    public Optional<long[]> matchesJob() {
        return matches != null && matches.running() && matches.jobId != null
                ? Optional.of(new long[] {matches.id, matches.jobId})
                : Optional.empty();
    }

    /** Takes the matches {@code matchesId} once they are in. */
    public void matchesProgress(long matchesId, GetHighlightResult result) {
        Optional<DocumentHighlight> highlight = result.highlight();
        if (!findsMatches(matchesId) || highlight.isEmpty()) {
            return;
        }
        Map<String, FieldMatches> fields = new HashMap<>();
        for (FieldMatches field : highlight.get().fields()) {
            fields.put(field.field(), field);
        }
        matches.fields = fields;
    }

    public void matchesFailed(long matchesId, String error) {
        if (findsMatches(matchesId)) {
            matches.error = error;
        }
    }

    /** The matches of the shown document, if it is the hit they were found in. */
    private Optional<Matches> shownMatches() {
        return matches != null && matches.sent && document != null && shows(matches.docId)
                ? Optional.of(matches)
                : Optional.empty();
    }

    /** The matches in {@code field} of the shown document, if it has any. */
    private Optional<FieldMatches> matchesIn(String field) {
        return shownMatches()
                .filter(shown -> shown.fields != null)
                .map(shown -> shown.fields.get(field))
                .filter(found -> !found.values().isEmpty());
    }

    /**
     * Views a stored value of {@code docId} with its matches highlighted, as the regex scan of the
     * search screen asks for: at once if the document is shown, otherwise once it is loaded. Returns
     * the document to fetch, or the whole value if it is cut.
     */
    public Optional<Request> viewMatches(int docId, String field, int index, List<MatchSpan> spans, int matchCount) {
        selectField = field;
        pendingView = new PendingView(docId, field, index, spans, matchCount);
        if (!shows(docId)) {
            return Optional.of(new Request.Document(docId));
        }
        String select = selectField;
        selectField = null;
        updateRows(select);
        return openPendingView();
    }

    /**
     * Opens the viewer on the value that {@link #viewMatches} asked for, if the shown document is its
     * document. Returns the fetch of the whole value, if it is cut.
     */
    public Optional<Request> openPendingView() {
        PendingView pending = pendingView;
        pendingView = null;
        if (pending == null || !shows(pending.docId())) {
            return Optional.empty();
        }
        Optional<DocumentField> selected = selectedField();
        if (selected.isEmpty() || !selected.get().name().equals(pending.field())) {
            return Optional.empty();
        }
        List<Viewer.Value> values = viewerValues(selected.get());
        closeViewer();
        viewer = Viewer.withMatches(
                pending.docId(),
                pending.field(),
                values,
                delimiters,
                pending.index(),
                pending.spans(),
                pending.matchCount());
        return viewer.fetch().flatMap(Documents::viewerRequest);
    }

    /**
     * What a jump leaves the screen at, for Backspace to show again (ADR 0053): the document, the
     * term hit or search hits that {@code [}/{@code ]} step through, the pinned segment, the selected
     * field, and the matches if they were found.
     */
    public static final class Mark {
        private final int docId;
        private final TermHit hit;
        private final boolean searchHits;
        private final SegmentStart pinned;
        private final String field;
        private final Matches matches;

        private Mark(Documents of) {
            this.docId = of.wanted.orElse(of.docId);
            this.hit = of.hit;
            this.searchHits = of.searchHits;
            this.pinned = of.pinned;
            this.field = of.selectedField().map(DocumentField::name).orElse(null);
            // Running matches are stopped by the next jump's dropMatches: they are found again, or not at all.
            this.matches = of.matches != null && of.matches.fields != null ? of.matches : null;
        }
    }

    /** Where the screen is, for {@link #restore} to come back to (ADR 0053). */
    public Mark mark() {
        return new Mark(this);
    }

    /**
     * Shows the screen as {@code mark} left it (ADR 0053). If it still shows that document, hit and
     * pin, nothing changes; otherwise returns the document to fetch, which shows the hit again.
     */
    public Optional<Request> restore(Mark mark) {
        boolean unchanged = document != null
                && docId == mark.docId
                && wanted.orElse(docId) == mark.docId
                && Objects.equals(hit, mark.hit)
                && searchHits == mark.searchHits
                && Objects.equals(pinned, mark.pinned);
        if (unchanged) {
            return Optional.empty();
        }
        hit = mark.hit;
        searchHits = mark.searchHits;
        pinned = mark.pinned;
        positions = null;
        dropMatches();
        matches = mark.matches;
        clearNotes();
        queuedSteps.clear();
        selectField = mark.field;
        return Optional.of(new Request.Document(mark.docId));
    }

    /** What {@code [}/{@code ]} step through, if anything. */
    public Optional<Walk> walk() {
        if (hit != null) {
            return Optional.of(Walk.TERM_DOCS);
        }
        return searchHits ? Optional.of(Walk.SEARCH_HITS) : Optional.empty();
    }

    /**
     * Starts a filter (ADR 0031) on {@code label}, built from {@code params}, which starts on {@code
     * startDoc} once it is built. Returns its start, with the job to cancel and the filter to release
     * of the filter it replaces.
     */
    public Request.StartFilter startFilter(String label, StartFilterParams params, int startDoc) {
        clearNotes();
        DocFilter old = filter;
        filter = new DocFilter(nextFilterSerial++, label, params, startDoc);
        return new Request.StartFilter(
                filter.serial(),
                params,
                old != null ? old.jobId() : OptionalLong.empty(),
                old != null ? old.filterId() : OptionalLong.empty());
    }

    /** Whether ←/→ step through a filter, or would once it is built. */
    public boolean filtered() {
        return filter != null;
    }

    /** The filter the backend keeps, once it is built. */
    public OptionalLong filterId() {
        return filter != null ? filter.filterId() : OptionalLong.empty();
    }

    /** The job that builds the filter, while it runs. */
    public OptionalLong filterJob() {
        return filter != null ? filter.jobId() : OptionalLong.empty();
    }

    /** The serial of the filter, if there is one. */
    public OptionalLong filterSerial() {
        return filter != null ? OptionalLong.of(filter.serial()) : OptionalLong.empty();
    }

    /** Whether the filter is being built. */
    public boolean filterBuilding() {
        return filter != null && filter.building();
    }

    /**
     * The filter to locate {@code docId} in once it is loaded, if it is built and doesn't know yet
     * whether the document matches.
     */
    public Optional<Locate> filterToLocate(int docId) {
        if (filter == null || !filter.needsLocating(docId)) {
            return Optional.empty();
        }
        return Optional.of(new Locate(filter.serial(), filter.filterId().getAsLong()));
    }

    /**
     * The job of filter {@code serial} has started. Returns whether it is still wanted: if the filter
     * was replaced or cleared meanwhile, the job is to be cancelled.
     */
    public boolean filterStarted(long serial, long jobId) {
        if (!isFilter(serial) || !filter.building() || filter.jobId().isPresent()) {
            return false;
        }
        filter.started(jobId);
        return true;
    }

    /**
     * Takes the progress of the job that builds filter {@code serial}. Once it is built, returns the
     * step to the document it starts on, or for a filter built again after a reopen, the step that
     * locates the shown document in it.
     */
    public Optional<Request> filterProgress(long serial, GetFilterResult result) {
        if (!isFilter(serial)) {
            return Optional.empty();
        }
        return filter.progress(result)
                .map(built -> new Request.StepFilter(
                        serial,
                        new StepFilterParams(
                                built.filterId(), built.step() == FilterStep.LOCATE ? docId : built.startDoc(), true),
                        built.step()));
    }

    /** What to build the filter from again after a reopen (ADR 0032), until it is started. */
    public Optional<Request.StartFilter> filterToRebuild() {
        if (filter == null) {
            return Optional.empty();
        }
        return filter.toRebuild()
                .map(params ->
                        new Request.StartFilter(filter.serial(), params, OptionalLong.empty(), OptionalLong.empty()));
    }

    /**
     * Building or stepping through filter {@code serial} failed: the title says why until {@code b}
     * clears it, and ←/→ step through every document again.
     */
    public void filterFailed(long serial, String error) {
        if (isFilter(serial)) {
            filter.failed(error);
            steppingFilter = OptionalLong.empty();
            queuedSteps.clear();
        }
    }

    /**
     * Takes what {@code stepFilter} found, unless the filter was replaced since. Returns the document
     * to show, or the step back that {@code NEAREST} takes when nothing matches forward.
     */
    public Optional<Request> filterStepped(
            long serial, StepFilterParams params, FilterStep step, Optional<FilterMatch> found) {
        if (!isFilter(serial) || !filter.filterId().equals(OptionalLong.of(params.filterId()))) {
            return Optional.empty();
        }
        // The step of < or > stays in the segment it goes to, any other one in the pinned segment.
        SegmentStart jump = step == FilterStep.MOVE ? segmentJump : null;
        if (step == FilterStep.MOVE) {
            segmentJump = null;
        }
        Optional<DocFilter.Range> within = Optional.ofNullable(jump != null ? jump : pinned)
                .filter(_ -> step != FilterStep.LOCATE)
                .map(SegmentStart::range);
        return switch (filter.stepped(params.fromDoc(), params.forward(), step, found, within)) {
            case DocFilter.Stepped.Show show -> Optional.of(new Request.Document(show.docId()));
            case DocFilter.Stepped.LookBack back ->
                Optional.of(new Request.StepFilter(
                        serial, new StepFilterParams(params.filterId(), back.docId(), false), FilterStep.MOVE));
            case DocFilter.Stepped.Nothing _ -> {
                steppingFilter = OptionalLong.empty();
                if (jump != null) {
                    // The segment has no match: its first document is shown all the same.
                    yield Optional.of(new Request.Document(jump.docBase()));
                }
                yield queuedStep();
            }
        };
    }

    /** A ←/→ step through the filter, or queued behind the one under way. */
    private Optional<Request> moveThroughFilter(boolean forward) {
        if (filter != null && steppingFilter.equals(OptionalLong.of(filter.serial()))) {
            queuedSteps.add(forward);
            return Optional.empty();
        }
        // Steps left from a filter that is gone.
        queuedSteps.clear();
        return stepThroughFilter(forward);
    }

    private Optional<Request> stepThroughFilter(boolean forward) {
        Optional<Request> step = filterStep(forward ? docId + 1 : docId - 1, forward, FilterStep.MOVE);
        steppingFilter = step.isPresent() ? OptionalLong.of(filter.serial()) : OptionalLong.empty();
        return step;
    }

    /** The next queued ←/→ step through the filter, once the one before is done. */
    public Optional<Request> queuedStep() {
        if (filter != null && steppingFilter.equals(OptionalLong.of(filter.serial()))) {
            return Optional.empty();
        }
        Boolean forward = queuedSteps.poll();
        if (forward == null || !stepsThroughFilter()) {
            queuedSteps.clear();
            return Optional.empty();
        }
        return stepThroughFilter(forward);
    }

    /** Forgets the filter. Returns what clears it in the backend, if there is a filter. */
    public Optional<Request> clearFilter() {
        if (filter == null) {
            return Optional.empty();
        }
        DocFilter old = filter;
        filter = null;
        return Optional.of(new Request.ClearFilter(old.jobId(), old.filterId()));
    }

    private boolean isFilter(long serial) {
        return filter != null && filter.serial() == serial;
    }

    /**
     * Whether ←/→ and {@code i} go through the filter: it is built or being built. Without one, or
     * with a failed one, they go through every document.
     */
    private boolean stepsThroughFilter() {
        return filter != null && (filter.building() || filter.filterId().isPresent());
    }

    /**
     * What ←/→ and {@code i} do with a filter that {@link #stepsThroughFilter}: step to a match of a
     * built one from {@code fromDoc}; nothing while it is built.
     */
    private Optional<Request> filterStep(int fromDoc, boolean forward, FilterStep step) {
        OptionalLong filterId = filter.filterId();
        if (filterId.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Request.StepFilter(
                filter.serial(), new StepFilterParams(filterId.getAsLong(), fromDoc, forward), step));
    }

    /** The positions to fetch, if the shown document is the hit's and they are not in yet. */
    public Optional<Request> positionsRequest() {
        if (hit == null || !shows(hit.docId()) || positions != null || !supports(Feature.POSITIONS)) {
            return Optional.empty();
        }
        return Optional.of(new Request.Positions(hit.docId(), hit.field(), hit.term()));
    }

    public void positionsLoaded(int docId, String field, BytesValue term, List<TermPosition> positions) {
        if (isHit(docId, field, term)) {
            this.positions = new Positions.Loaded(positions);
        }
    }

    public void positionsFailed(int docId, String field, BytesValue term, String error) {
        if (isHit(docId, field, term)) {
            positions = new Positions.Failed(error);
        }
    }

    private boolean isHit(int docId, String field, BytesValue term) {
        return hit != null
                && hit.docId() == docId
                && hit.field().equals(field)
                && hit.term().equals(term);
    }

    /** The hit, if the shown document is its document. */
    private Optional<TermHit> shownHit() {
        return hit != null && shows(hit.docId()) ? Optional.of(hit) : Optional.empty();
    }

    /**
     * Shows {@code document}, unless another one is wanted. The selection stays on its field if the
     * new document shows it. Returns whether it is shown.
     */
    public boolean loaded(GetDocumentResult document) {
        if (!isWanted(document.docId())) {
            return false;
        }
        String selected = selectField != null
                ? selectField
                : selectedField().map(DocumentField::name).orElse(null);
        selectField = null;
        docId = document.docId();
        leaveSegment(docId);
        steppingFilter = OptionalLong.empty();
        this.document = document;
        error = null;
        closeViewer();
        termVectors.clear();
        reconstructions.clear();
        detailsScroll = 0;
        updateRows(selected);
        return true;
    }

    /** Shows why {@code docId} could not be read, unless another document is wanted. */
    public void failed(int docId, String error) {
        if (!isWanted(docId)) {
            return;
        }
        this.docId = docId;
        leaveSegment(docId);
        document = null;
        this.error = error;
        steppingFilter = OptionalLong.empty();
        queuedSteps.clear();
        closeViewer();
        termVectors.clear();
        reconstructions.clear();
        rows.clear();
        row = -1;
        detailsScroll = 0;
    }

    public void termVectorLoaded(int docId, String field, List<TermVectorTerm> terms) {
        if (shows(docId)) {
            termVectors.put(field, new TermVector.Loaded(terms));
        }
    }

    public void termVectorFailed(int docId, String field, String error) {
        if (shows(docId)) {
            termVectors.put(field, new TermVector.Failed(error));
        }
    }

    /** The rebuild that is asked for or running, with its field; or null. */
    private Map.Entry<String, Reconstruction> activeReconstruction() {
        return reconstructions.entrySet().stream()
                .filter(entry -> entry.getValue().active())
                .findFirst()
                .orElse(null);
    }

    /** The rebuild job, while one runs. */
    public OptionalLong reconstructJob() {
        Map.Entry<String, Reconstruction> active = activeReconstruction();
        return active != null ? active.getValue().job() : OptionalLong.empty();
    }

    /** Whether a rebuild is asked for or runs. */
    public boolean rebuilding() {
        return activeReconstruction() != null;
    }

    /**
     * Forgets the rebuild that is asked for or runs, so that {@code I} can start it again. Returns
     * its job to cancel, if it has started; otherwise {@link #reconstructStarted} says to cancel it
     * once it has.
     */
    public OptionalLong stopRebuild() {
        OptionalLong job = reconstructJob();
        reconstructions.values().removeIf(Reconstruction::active);
        return job;
    }

    /**
     * Rebuilds the selected field, if it has postings or points and is not rebuilt yet; a rebuild of
     * another field stops.
     */
    private Optional<Request> reconstruct() {
        Optional<DocumentField> selected = selectedField();
        if (selected.isEmpty()) {
            return Optional.empty();
        }
        String field = selected.get().name();
        IndexField index = schema.get(field);
        boolean indexed = index != null && rebuildable(index);
        Reconstruction rebuilt = reconstructions.get(field);
        if (!indexed || (rebuilt != null && !(rebuilt instanceof Reconstruction.Failed))) {
            return Optional.empty();
        }
        List<Long> cancel = new ArrayList<>();
        stopRebuild().ifPresent(cancel::add);
        reconstructions.put(field, new Reconstruction.Starting());
        return Optional.of(new Request.Reconstruct(docId, field, List.copyOf(cancel)));
    }

    /**
     * The job of the rebuild of {@code field} of {@code docId} has started. Returns whether it is
     * still wanted: if it was stopped meanwhile, the job is to be cancelled.
     */
    public boolean reconstructStarted(int docId, String field, long jobId) {
        if (!shows(docId) || !(reconstructions.get(field) instanceof Reconstruction.Starting)) {
            return false;
        }
        reconstructions.put(field, new Reconstruction.Running(jobId, 0, OptionalLong.empty()));
        return true;
    }

    /** Shows why the rebuild of {@code field} of {@code docId} couldn't start, if it is still wanted. */
    public void reconstructStartFailed(int docId, String field, String error) {
        if (shows(docId) && reconstructions.get(field) instanceof Reconstruction.Starting) {
            reconstructions.put(field, new Reconstruction.Failed(error));
        }
    }

    /** Whether a field has postings or points to rebuild. */
    private static boolean rebuildable(IndexField field) {
        return field.indexOptions() != IndexOptions.NONE || field.pointDimensionCount() > 0;
    }

    /** Shows the progress of rebuild job {@code jobId}, or the rebuilt field, if it is the running one. */
    public void reconstructProgress(long jobId, GetReconstructResult result) {
        Map.Entry<String, Reconstruction> active = activeReconstruction();
        if (active == null || active.getValue().job().orElse(-1) != jobId) {
            return;
        }
        Reconstruction next;
        Optional<ReconstructResult> rebuilt = result.result();
        if (rebuilt.isPresent()) {
            next = rebuilt.get().fields().stream()
                    .findFirst()
                    .<Reconstruction>map(Reconstruction.Done::new)
                    .orElse(new Reconstruction.Failed("the server rebuilt no field"));
        } else {
            next = new Reconstruction.Running(jobId, result.scanned(), result.total());
        }
        active.setValue(next);
    }

    /** Shows why rebuild job {@code jobId} failed, if it is the running one. */
    public void reconstructFailed(long jobId, String error) {
        Map.Entry<String, Reconstruction> active = activeReconstruction();
        if (active != null && active.getValue().job().orElse(-1) == jobId) {
            active.setValue(new Reconstruction.Failed(error));
        }
    }

    public void storedValueLoaded(int docId, String field, int index, StoredValue value) {
        if (viewer != null) {
            viewer.loaded(docId, field, index, value);
        }
    }

    public void storedValueFailed(int docId, String field, int index, String error) {
        if (viewer != null) {
            viewer.failed(docId, field, index, error);
        }
    }

    private boolean shows(int docId) {
        return document != null && document.docId() == docId;
    }

    /** Fills the rows from the document, and selects the field {@code selected} if it is among them. */
    private void updateRows(String selected) {
        rows.clear();
        row = -1;
        if (document == null) {
            return;
        }
        // The hit's field has the term, even if nothing else is in it.
        String hitField = shownHit().map(TermHit::field).orElse(null);
        List<DocumentField> fields = document.fields();
        for (int i = 0; i < fields.size(); i++) {
            DocumentField field = fields.get(i);
            if (!hideEmpty || !isEmpty(field) || field.name().equals(hitField)) {
                rows.add(i);
            }
        }
        if (rows.isEmpty()) {
            return;
        }
        rows.sort(rowOrder(fields));
        row = 0;
        for (int i = 0; i < rows.size(); i++) {
            if (fields.get(rows.get(i)).name().equals(selected)) {
                row = i;
                break;
            }
        }
    }

    /** The order of the rows by the sort chosen, the document's own order breaking ties. */
    private Comparator<Integer> rowOrder(List<DocumentField> fields) {
        Comparator<Integer> byKey = switch (sort) {
            case NUMBER ->
                (a, b) -> {
                    OptionalInt numberA = fieldNumber(fields.get(a));
                    OptionalInt numberB = fieldNumber(fields.get(b));
                    if (numberA.isPresent() && numberB.isPresent()) {
                        int ordering = Integer.compare(numberA.getAsInt(), numberB.getAsInt());
                        return descending ? -ordering : ordering;
                    }
                    // A field the schema doesn't know comes last in either direction.
                    return Boolean.compare(numberA.isEmpty(), numberB.isEmpty());
                };
            case NAME -> {
                Comparator<Integer> byName =
                        Comparator.comparing(i -> fields.get(i).name());
                yield descending ? Collections.reverseOrder(byName) : byName;
            }
        };
        return byKey.thenComparingInt(Integer::intValue);
    }

    /** The field's number in the index, if the schema knows the field. */
    private OptionalInt fieldNumber(DocumentField field) {
        IndexField indexed = schema.get(field.name());
        return indexed == null ? OptionalInt.empty() : OptionalInt.of(indexed.number());
    }

    /** The field under the selection. */
    Optional<DocumentField> selectedField() {
        if (document == null || row < 0) {
            return Optional.empty();
        }
        return Optional.of(document.fields().get(rows.get(row)));
    }

    /** Whether the go-to-document prompt, the field seek or the viewer takes the keys, Esc included. */
    public boolean prompting() {
        return prompt != null || seek != null || viewer != null;
    }

    /** Whether the field seek takes the keys. */
    public boolean seeking() {
        return seek != null;
    }

    /** Whether the go-to-document prompt takes the keys. */
    public boolean goingTo() {
        return prompt != null;
    }

    /**
     * Edits the field seek, and selects the first shown field whose name starts with its text,
     * ignoring case, or else the first that contains it. Enter and Esc close it.
     */
    private void seekKey(KeyEvent key) {
        switch (key.code()) {
            case ENTER, ESCAPE -> {
                seek = null;
                return;
            }
            case CHAR -> seek.appendCodePoint(key.codePoint());
            case BACKSPACE -> {
                if (!seek.isEmpty()) {
                    seek.setLength(seek.offsetByCodePoints(seek.length(), -1));
                }
            }
            default -> {
                return;
            }
        }
        OptionalInt match = seekMatch();
        if (match.isPresent()) {
            if (match.getAsInt() != row) {
                detailsScroll = 0;
            }
            row = match.getAsInt();
        }
    }

    /** The row that the field seek's text finds, if it finds one. An empty text finds nothing. */
    private OptionalInt seekMatch() {
        if (seek == null || seek.isEmpty() || document == null) {
            return OptionalInt.empty();
        }
        String text = seek.toString().toLowerCase(Locale.ROOT);
        List<String> names = rows.stream()
                .map(i -> document.fields().get(i).name().toLowerCase(Locale.ROOT))
                .toList();
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).startsWith(text)) {
                return OptionalInt.of(i);
            }
        }
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).contains(text)) {
                return OptionalInt.of(i);
            }
        }
        return OptionalInt.empty();
    }

    /** The viewer, if it is open. */
    public Optional<Viewer> viewer() {
        return Optional.ofNullable(viewer);
    }

    /** Closes the viewer, keeping its delimiters for the next one. */
    private void closeViewer() {
        if (viewer != null) {
            delimiters = viewer.delimiters();
            viewer = null;
        }
    }

    /** Opens the viewer on the selected field's stored values and doc values, if it has any. */
    private Optional<Request> view() {
        Optional<DocumentField> field = selectedField();
        if (field.isEmpty()) {
            return Optional.empty();
        }
        List<Viewer.Value> values = viewerValues(field.get());
        if (values.isEmpty()) {
            return Optional.empty();
        }
        Optional<FieldMatches> found = matchesIn(field.get().name());
        viewer = found.isPresent()
                ? Viewer.withMatches(
                        docId,
                        field.get().name(),
                        values,
                        delimiters,
                        found.get().values().getFirst().valueIndex(),
                        found.get().values())
                : new Viewer(docId, field.get().name(), values, delimiters);
        return viewer.fetch().flatMap(Documents::viewerRequest);
    }

    /** A field's stored values and doc values, as the viewer shows them. */
    private List<Viewer.Value> viewerValues(DocumentField field) {
        List<Viewer.Value> values = new ArrayList<>();
        for (int i = 0; i < field.stored().size(); i++) {
            values.add(Viewer.Value.stored(i, field.stored().get(i)));
        }
        field.docValues().ifPresent(docValues -> {
            String kind = Overview.docValues(docValues.type());
            for (long number : docValues.numbers()) {
                values.add(Viewer.Value.docValueNumber(kind, number, numbers.format(number)));
            }
            for (BytesValue value : docValues.values()) {
                values.add(Viewer.Value.docValue(kind, value));
            }
        });
        field.vector().ifPresent(vector -> values.add(Viewer.Value.vector(vector)));
        return values;
    }

    /**
     * The selected field's doc values, for a search for the documents that share any of them (ADR
     * 0033): numbers in the shown format, bytes as text unless one of them is not UTF-8.
     */
    private Optional<Request> searchDocValues() {
        Optional<DocumentField> field = selectedField();
        if (field.isEmpty()) {
            return Optional.empty();
        }
        String name = field.get().name();
        Optional<DocValues> fieldDocValues = field.get().docValues();
        if (fieldDocValues.isEmpty()) {
            return Optional.empty();
        }
        DocValues docValues = fieldDocValues.get();
        boolean hex = docValues.values().stream().anyMatch(BytesValue::binary);
        List<String> values = new ArrayList<>();
        for (long number : docValues.numbers()) {
            // Rust's names for the infinities, as Java's parser reads them.
            values.add(
                    switch (numbers.format(number)) {
                        case "inf" -> "Infinity";
                        case "-inf" -> "-Infinity";
                        case String text -> text;
                    });
        }
        for (BytesValue value : docValues.values()) {
            values.add(
                    hex && !value.binary()
                            ? TextFormat.hex(value.value().getBytes(java.nio.charset.StandardCharsets.UTF_8))
                            : value.value());
        }
        // Rust's dedup: repeats that follow each other.
        List<String> deduped = new ArrayList<>();
        for (String value : values) {
            if (deduped.isEmpty() || !deduped.getLast().equals(value)) {
                deduped.add(value);
            }
        }
        if (deduped.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Request.SearchDocValues(name, numbers.docValuesNumber(), hex, deduped));
    }

    /** The selected field's first stored text, for the analysis screen; the viewer analyzes any other value. */
    private Optional<Request> analyze() {
        return selectedField()
                .flatMap(field -> field.stored().stream()
                        .filter(value -> value.type() == StoredType.STRING
                                || (value.type() == StoredType.BINARY && !value.binary()))
                        .findFirst()
                        .map(value -> new Request.Analyze(docId, field.name(), value.value())));
    }

    /**
     * Goes to another document with ←/→, the {@code i} prompt, or {@code [}/{@code ]} through the
     * documents of the hit's term; with a filter, ←/→ and {@code i} go to its matches only, and {@code
     * b} clears it. {@code P} pins the segment and {@code <}/{@code >} step through the segments.
     * Loads the selected field's term vector with Enter, views its values with {@code v}, cycles the
     * number format with {@code n} and the point format with {@code t}, hides empty fields with
     * {@code e}, sorts the fields with {@code s}/{@code S}, rebuilds the field's indexed terms and
     * points with {@code I}, switches the focus with Tab, and moves the selection or scrolls the
     * details. The jumps to other screens are {@link #jump}'s (ADR 0120).
     */
    public Optional<Request> handleKey(KeyEvent key) {
        if (viewer != null) {
            return viewerKey(key);
        }
        if (seek != null) {
            seekKey(key);
            return Optional.empty();
        }
        if (prompt != null) {
            return docIdPromptKey(key);
        }
        clearNotes();
        KeyCode code = key.code();
        // A key pressed before the last step's document is in goes on from that document.
        int from = wanted.orElse(docId);
        if (code == KeyCode.LEFT) {
            return previous(from);
        }
        if (code == KeyCode.RIGHT) {
            return next(from);
        }
        Optional<String> unsupported = unsupported(key);
        if (unsupported.isPresent()) {
            return unsupported.map(Request.Unsupported::new);
        }
        if (code == KeyCode.ENTER) {
            return termVector();
        }
        if (code == KeyCode.TAB) {
            focus = focus == Focus.FIELDS ? Focus.DETAILS : Focus.FIELDS;
            return Optional.empty();
        }
        if (code == KeyCode.CHAR) {
            Optional<Optional<Request>> handled = charKey(key, from);
            if (handled.isPresent()) {
                return handled.get();
            }
        }
        moveKey(key);
        return Optional.empty();
    }

    private Optional<Request> viewerKey(KeyEvent key) {
        Optional<Viewer.Event> event = viewer.handleKey(key);
        if (event.isPresent() && event.get() instanceof Viewer.Event.Close) {
            closeViewer();
        }
        return event.flatMap(Documents::viewerRequest);
    }

    private Optional<Request> docIdPromptKey(KeyEvent key) {
        if (key.code() == KeyCode.ESCAPE) {
            prompt = null;
            return Optional.empty();
        }
        OptionalInt chosen = promptKey(prompt, key, maxDoc);
        if (chosen.isEmpty()) {
            return Optional.empty();
        }
        prompt = null;
        // i goes anywhere: a document outside the pinned segment unpins it.
        leaveSegment(chosen.getAsInt());
        return stepsThroughFilter()
                ? filterStep(chosen.getAsInt(), true, FilterStep.NEAREST)
                : Optional.of(new Request.Document(chosen.getAsInt()));
    }

    /** The document before {@code from}: the previous match while a filter steps, in the pinned segment. */
    private Optional<Request> previous(int from) {
        if (stepsThroughFilter()) {
            return moveThroughFilter(false);
        }
        if (pinned != null && from - 1 < pinned.docBase()) {
            note = "first document of " + pinned.name();
            return Optional.empty();
        }
        return from > 0 ? Optional.of(new Request.Document(from - 1)) : Optional.empty();
    }

    /** The document after {@code from}: the next match while a filter steps, in the pinned segment. */
    private Optional<Request> next(int from) {
        if (stepsThroughFilter()) {
            return moveThroughFilter(true);
        }
        if (pinned != null && from + 1 >= pinned.end()) {
            note = "last document of " + pinned.name();
            return Optional.empty();
        }
        return from < maxDoc - 1 ? Optional.of(new Request.Document(from + 1)) : Optional.empty();
    }

    /** The term vector of the selected field, unless it has none or it is loaded. */
    private Optional<Request> termVector() {
        Optional<DocumentField> field = selectedField();
        if (field.isEmpty()
                || !field.get().hasTermVector()
                || termVectors.containsKey(field.get().name())) {
            return Optional.empty();
        }
        return Optional.of(new Request.TermVector(docId, field.get().name()));
    }

    /** A letter's command; empty for a key that moves the selection. */
    private Optional<Optional<Request>> charKey(KeyEvent key, int from) {
        switch (key.codePoint()) {
            case 'i' -> {
                if (maxDoc > 0) {
                    prompt = new DocIdPrompt();
                }
                return Optional.of(Optional.empty());
            }
            case 'P' -> {
                if (pinned != null) {
                    pinned = null;
                } else {
                    pinned = segmentOf(from).orElse(null);
                }
                return Optional.of(Optional.empty());
            }
            case '<', '>' -> {
                return Optional.of(jumpSegment(key.codePoint() == '>', from));
            }
            case '/' -> {
                if (!rows.isEmpty()) {
                    seek = new StringBuilder();
                    focus = Focus.FIELDS;
                }
                return Optional.of(Optional.empty());
            }
            case '[', ']' -> {
                boolean forward = key.codePoint() == ']';
                return Optional.of(walk().map(walk -> new Request.Step(walk, forward)));
            }
            case 'v' -> {
                return Optional.of(view());
            }
            case 'I' -> {
                return Optional.of(reconstruct());
            }
            case 'b' -> {
                return Optional.of(clearFilter());
            }
            case 'n' -> {
                numbers = numbers.next();
                return Optional.of(Optional.empty());
            }
            case 't' -> {
                selectedField()
                        .map(field -> schema.get(field.name()))
                        .filter(field -> field.pointDimensionCount() > 0)
                        .ifPresent(pointFormats::cycle);
                return Optional.of(Optional.empty());
            }
            case 'E' -> {
                return Optional.of(Optional.ofNullable(document).map(doc -> new Request.Export(doc.docId())));
            }
            case 'e' -> {
                hideEmpty = !hideEmpty;
                updateRows(selectedField().map(DocumentField::name).orElse(null));
                return Optional.of(Optional.empty());
            }
            case 's' -> {
                sort = sort.next();
                descending = false;
                updateRows(selectedField().map(DocumentField::name).orElse(null));
                return Optional.of(Optional.empty());
            }
            case 'S' -> {
                descending = !descending;
                updateRows(selectedField().map(DocumentField::name).orElse(null));
                return Optional.of(Optional.empty());
            }
            default -> {
                return Optional.empty();
            }
        }
    }

    /** Moves the selected field, or scrolls the details. */
    private void moveKey(KeyEvent key) {
        if (focus == Focus.DETAILS) {
            scrollDetails(key);
        } else if (!rows.isEmpty()) {
            OptionalInt moved = Selection.moved(row, rows.size(), key);
            if (moved.isPresent() && moved.getAsInt() != row) {
                row = moved.getAsInt();
                detailsScroll = 0;
            }
        }
    }

    /**
     * {@code g t}: the selected field on the terms screen (ADR 0052), at the hit's term if the terms
     * screen opened the document from that field; its points if it has no terms; or a note if it has
     * neither.
     */
    private Optional<Request> browseTerms() {
        Optional<DocumentField> selected = selectedField();
        if (selected.isEmpty()) {
            return Optional.empty();
        }
        String name = selected.get().name();
        Optional<IndexField> field = Optional.ofNullable(schema.get(name));
        if (field.filter(f -> f.indexOptions() != IndexOptions.NONE).isPresent()) {
            Optional<BytesValue> term =
                    hit != null && hit.field().equals(name) ? Optional.of(hit.term()) : Optional.empty();
            return Optional.of(new Request.BrowseTerms(name, term, false));
        }
        if (field.filter(f -> f.pointDimensionCount() > 0).isPresent()) {
            return Optional.of(new Request.BrowseTerms(name, Optional.empty(), true));
        }
        if (field.filter(f -> f.vectorDimension() > 0).isPresent()) {
            if (!supports(Feature.KNN_GRAPH)) {
                note = "HNSW graph not supported";
                return Optional.empty();
            }
            if (selected.get().vector().isEmpty()) {
                note = "no vector in " + TextFormat.escapeControl(name);
                return Optional.empty();
            }
            return Optional.of(new Request.BrowseGraph(name, docId));
        }
        note = TextFormat.escapeControl(name) + " has no terms";
        return Optional.empty();
    }

    /**
     * {@code <}/{@code >}: the first document of the previous or next segment from {@code from}'s,
     * or with a filter, its first match. The pin moves along.
     */
    private Optional<Request> jumpSegment(boolean forward, int from) {
        Optional<SegmentStart> current = pinned != null ? Optional.of(pinned) : segmentOf(from);
        if (current.isEmpty()) {
            return Optional.empty();
        }
        int at = segments.indexOf(current.get()) + (forward ? 1 : -1);
        if (at < 0 || at >= segments.size()) {
            note = forward ? "no next segment" : "no previous segment";
            return Optional.empty();
        }
        SegmentStart target = segments.get(at);
        if (pinned != null) {
            pinned = target;
        }
        if (stepsThroughFilter() && filter.filterId().isPresent()) {
            queuedSteps.clear();
            segmentJump = target;
            steppingFilter = OptionalLong.of(filter.serial());
            return filterStep(target.docBase(), true, FilterStep.MOVE);
        }
        return Optional.of(new Request.Document(target.docBase()));
    }

    /** What {@code key} is called if the index lacks a feature it needs (ADR 0044); empty otherwise. */
    private Optional<String> unsupported(KeyEvent key) {
        if (key.code() == KeyCode.ENTER) {
            return supports(Feature.TERM_VECTORS) ? Optional.empty() : Optional.of(TERM_VECTOR);
        }
        if (key.code() != KeyCode.CHAR) {
            return Optional.empty();
        }
        return switch (key.codePoint()) {
            case 'v' -> supports(Feature.STORED_VALUES) ? Optional.empty() : Optional.of("View");
            case 'E' -> supports(Feature.STORED_VALUES) ? Optional.empty() : Optional.of("Export");
            case 'I' -> supports(Feature.RECONSTRUCT) ? Optional.empty() : Optional.of("Rebuild");
            case 't' -> supports(Feature.POINTS) ? Optional.empty() : Optional.of("Points");
            case 'b' -> supports(Feature.FILTER) ? Optional.empty() : Optional.of("Filter");
            default -> Optional.empty();
        };
    }

    /** What {@code jump} is called if the index lacks a feature it needs (ADR 0044); empty otherwise. */
    private Optional<String> unsupported(Jump jump) {
        boolean has = switch (jump) {
            case TERMS -> supports(Feature.TERMS);
            case SEARCH -> searchesValues();
            case COMMIT -> supports(Feature.COMMITS);
            case SIMILAR -> findsSimilar();
            case NEAREST -> nearest();
            case ANALYSIS, DOCUMENT -> true;
        };
        if (has) {
            return Optional.empty();
        }
        return Optional.of(
                switch (jump) {
                    case TERMS -> "Terms";
                    case SEARCH -> "Search value";
                    case COMMIT -> "Commits";
                    case SIMILAR -> "Similar";
                    default -> "Nearest";
                });
    }

    /**
     * The jumps of the {@code g} menu that the index and the selected field allow (ADR 0120); the
     * viewer's while it is open.
     */
    public List<GoMenu.Target> jumps() {
        if (viewer != null) {
            return viewer.jumps();
        }
        List<GoMenu.Target> jumps = new ArrayList<>();
        Optional<DocumentField> selected = selectedField();
        String name =
                selected.map(field -> TextFormat.escapeControl(field.name())).orElse("");
        Optional<IndexField> field = selected.map(DocumentField::name).map(schema::get);
        if (supports(Feature.TERMS) && field.isPresent()) {
            IndexField indexed = field.get();
            if (indexed.indexOptions() != IndexOptions.NONE) {
                jumps.add(new GoMenu.Target(Jump.TERMS, "terms of " + name));
            } else if (indexed.pointDimensionCount() > 0) {
                jumps.add(new GoMenu.Target(Jump.TERMS, "points of " + name));
            } else if (indexed.vectorDimension() > 0
                    && supports(Feature.KNN_GRAPH)
                    && selected.get().vector().isPresent()) {
                jumps.add(new GoMenu.Target(Jump.TERMS, "graph node of " + name));
            }
        }
        if (searchesValues() && selected.flatMap(DocumentField::docValues).isPresent()) {
            jumps.add(new GoMenu.Target(Jump.SEARCH, "search the doc values of " + name));
        }
        if (analyze().isPresent()) {
            jumps.add(new GoMenu.Target(Jump.ANALYSIS, "analyze " + name));
        }
        Optional<SegmentStart> segment = segmentOf(wanted.orElse(docId));
        if (supports(Feature.COMMITS) && segment.isPresent()) {
            jumps.add(new GoMenu.Target(
                    Jump.COMMIT, "commit of segment " + segment.get().name()));
        }
        if (findsSimilar() && maxDoc > 0) {
            jumps.add(new GoMenu.Target(Jump.SIMILAR, "documents like this one"));
        }
        if (nearest() && selected.flatMap(DocumentField::vector).isPresent()) {
            jumps.add(new GoMenu.Target(Jump.NEAREST, "nearest to the vector of " + name));
        }
        return jumps;
    }

    /**
     * Where {@code g} and {@code jump} go from the selected field or the document (ADR 0052, ADR
     * 0120); the viewer's jump while it is open.
     */
    public Optional<Request> jump(Jump jump) {
        if (viewer != null) {
            return viewer.jump(jump).flatMap(Documents::viewerRequest);
        }
        clearNotes();
        Optional<String> unsupported = unsupported(jump);
        if (unsupported.isPresent()) {
            return unsupported.map(Request.Unsupported::new);
        }
        return switch (jump) {
            case TERMS -> browseTerms();
            case SEARCH -> searchDocValues();
            case ANALYSIS -> analyze();
            case COMMIT ->
                segmentOf(wanted.orElse(docId)).map(segment -> new Request.ShowCommit(segment.index(), segment.name()));
            case SIMILAR -> maxDoc > 0 ? Optional.of(new Request.MoreLikeThis(docId)) : Optional.empty();
            case NEAREST ->
                selectedField()
                        .filter(field -> field.vector().isPresent())
                        .map(field -> new Request.SearchKnn(field.name(), docId));
            case DOCUMENT -> Optional.empty();
        };
    }

    /** The screen's keys (ADR 0120): those the index has the features for (ADR 0044). */
    public Keys keys() {
        Keys.Builder keys = Keys.builder();
        if (filtered()) {
            keys.key(MOVE, "←→", "match");
        } else {
            keys.key(MOVE, "←→", "doc");
        }
        walk().ifPresent(walk -> keys.key(MOVE, "[ ]", walk == Walk.TERM_DOCS ? "term docs" : "hits"));
        keys.key(MOVE, "i", "go to doc")
                .moreIf(!segments.isEmpty(), MOVE, "< >", "segment")
                .moreIf(!segments.isEmpty(), MOVE, "P", pinned != null ? "unpin segment" : "pin segment")
                .key(FIND, "/", "seek field")
                .keyIf(supports(Feature.TERM_VECTORS), SHOW, "⏎", "term vector")
                .keyIf(supports(Feature.STORED_VALUES), SHOW, "v", "view")
                .more(SHOW, "n", "number format")
                .moreIf(supports(Feature.POINTS), SHOW, "t", "point format")
                .more(SHOW, "e", hideEmpty ? "show empty fields" : "hide empty fields")
                .key(SORT, "s/S", "sort")
                .keyIf(filtered(), DO, "b", "unfilter")
                .moreIf(supports(Feature.RECONSTRUCT), DO, "I", "indexed terms")
                .keyIf(supports(Feature.STORED_VALUES), DO, "E", "export");
        return keys.build();
    }

    private void scrollDetails(KeyEvent key) {
        switch (key.code()) {
            case DOWN -> scrollBy(1);
            case UP -> scrollBy(-1);
            case PAGE_DOWN -> scrollBy(Selection.PAGE);
            case PAGE_UP -> scrollBy(-Selection.PAGE);
            case HOME -> detailsScroll = 0;
            case END -> detailsScroll = Integer.MAX_VALUE;
            case CHAR -> {
                switch (key.codePoint()) {
                    case 'j' -> scrollBy(1);
                    case 'k' -> scrollBy(-1);
                    case 'g' -> detailsScroll = 0;
                    case 'G' -> detailsScroll = Integer.MAX_VALUE;
                    default -> {}
                }
            }
            default -> {}
        }
    }

    private void scrollBy(int lines) {
        detailsScroll = Math.clamp((long) detailsScroll + lines, 0, Integer.MAX_VALUE);
    }

    /** Edits the prompt. Returns the document id once a valid one is submitted. */
    private static OptionalInt promptKey(DocIdPrompt prompt, KeyEvent key, int maxDoc) {
        switch (key.code()) {
            case CHAR -> {
                int c = key.codePoint();
                if (c >= '0' && c <= '9' && prompt.input.length() < 10) {
                    prompt.input.append((char) c);
                    prompt.error = null;
                }
            }
            case BACKSPACE -> {
                if (!prompt.input.isEmpty()) {
                    prompt.input.setLength(prompt.input.length() - 1);
                }
                prompt.error = null;
            }
            case ENTER -> {
                try {
                    int docId = Integer.parseInt(prompt.input.toString());
                    if (docId < maxDoc) {
                        return OptionalInt.of(docId);
                    }
                } catch (NumberFormatException _) {
                    // Said below.
                }
                prompt.error = "Enter a number from 0 to " + (maxDoc - 1) + ".";
            }
            default -> {}
        }
        return OptionalInt.empty();
    }

    /** What a viewer event asks of the caller. */
    private static Optional<Request> viewerRequest(Viewer.Event event) {
        return switch (event) {
            case Viewer.Event.Fetch fetch ->
                Optional.of(new Request.StoredValue(fetch.docId(), fetch.field(), fetch.index()));
            case Viewer.Event.Copy copy -> Optional.of(new Request.Copy(copy.text()));
            case Viewer.Event.Analyze analyze ->
                Optional.of(new Request.Analyze(analyze.docId(), analyze.field(), analyze.text()));
            case Viewer.Event.GoTo _ -> Optional.of(new Request.GoTo());
            case Viewer.Event.Help _ -> Optional.of(new Request.Help());
            case Viewer.Event.Close _ -> Optional.empty();
        };
    }

    public void render(Frame frame, Rect area) {
        List<Rect> parts = Layout.vertical()
                .constraints(Constraint.length(3), Constraint.fill())
                .split(area);
        frame.renderWidget(
                Paragraph.builder()
                        .text(Text.from(header()))
                        .block(Panels.box().title(Title.from(title())).build())
                        .build(),
                parts.get(0));
        Rect bottom = parts.get(1);
        List<Rect> panels = bottom.width() >= SIDE_BY_SIDE_WIDTH
                ? Layout.horizontal()
                        .constraints(Constraint.fill(), Constraint.length(DETAILS_PANEL_WIDTH))
                        .split(bottom)
                : Layout.vertical()
                        .constraints(Constraint.fill(), Constraint.percentage(50))
                        .split(bottom);
        renderFields(frame, panels.get(0));
        renderDetails(frame, panels.get(1));
        if (prompt != null) {
            renderPrompt(frame, area, prompt, maxDoc);
        }
        if (viewer != null) {
            viewer.render(frame, area);
        }
    }

    /** The title of the document's panel, with what the filter says: in red if it failed. */
    private Line title() {
        List<Span> spans = new ArrayList<>();
        spans.add(Span.raw(" Document "));
        String segment = Stream.of(pinned != null ? "segment " + pinned.name() : null, note)
                .filter(Objects::nonNull)
                .collect(Collectors.joining(" · "));
        if (!segment.isEmpty()) {
            spans.add(Span.styled(
                    segment + " ",
                    Palette.current().heading().patch(Palette.current().accent())));
        }
        if (filter != null) {
            DocFilter.Title title = filter.title(docId, maxDoc);
            Style style = Palette.current()
                    .heading()
                    .patch(
                            title.error()
                                    ? Palette.current().error()
                                    : Palette.current().warning());
            spans.add(Span.styled(title.text() + " ", style));
        }
        return Line.from(spans);
    }

    private Line header() {
        if (maxDoc == 0) {
            return Line.from("The index has no documents.");
        }
        List<Span> spans = new ArrayList<>();
        spans.add(Span.styled("#" + docId, Palette.current().heading()));
        spans.add(Span.raw(" of 0–" + (maxDoc - 1)));
        indexOf(docId)
                .ifPresent(index ->
                        spans.add(Span.raw("   in " + TextFormat.firstChars(TextFormat.escapeControl(index), 60))));
        segmentOf(docId)
                .ifPresent(segment -> spans.add(Span.raw("   " + segment.name() + " #" + (docId - segment.docBase())
                        + " of 0–" + (segment.maxDoc() - 1))));
        if (error != null) {
            spans.add(Span.raw("   Cannot read it: " + error));
        } else if (document != null && document.deleted()) {
            spans.add(Span.raw("   "));
            spans.add(Span.styled(
                    "deleted",
                    Palette.current().heading().patch(Palette.current().error())));
        }
        if (hit != null) {
            // Dimmed while another document is shown.
            spans.add(Span.styled(
                    "   " + TextFormat.firstChars(TextFormat.escapeControl(hit.field()), 30) + ":"
                            + TextFormat.firstChars(
                                    TextFormat.bytesText(
                                            hit.term().value(), hit.term().binary()),
                                    40)
                            + "  " + (hit.index() + 1) + " of " + hit.docFreq() + "  [ ] steps",
                    shows(hit.docId()) ? Style.EMPTY : Palette.current().muted()));
        }
        return Line.from(spans);
    }

    private void renderFields(Frame frame, Rect area) {
        boolean focused = focus == Focus.FIELDS;
        if (document == null) {
            frame.renderWidget(panel(" Fields ", focused), area);
            return;
        }
        List<String[]> cells = new ArrayList<>();
        List<Boolean> empty = new ArrayList<>();
        List<Boolean> matched = new ArrayList<>();
        for (int i : rows) {
            DocumentField field = document.fields().get(i);
            empty.add(isEmpty(field));
            matched.add(matchesIn(field.name()).isPresent());
            cells.add(new String[] {
                fieldNumber(field).isPresent()
                        ? Integer.toString(fieldNumber(field).getAsInt())
                        : "-",
                TextFormat.escapeControl(field.name()),
                flags(Optional.ofNullable(schema.get(field.name())), field),
                field.norm().isPresent() ? Long.toString(field.norm().getAsLong()) : "-",
                valueSummary(field, numbers)
            });
        }
        String[] titles = {"#", "Field", FLAGS_LABEL, "Norm", "Value"};
        String marker = descending ? " ▼" : " ▲";
        String[] shown = titles.clone();
        shown[sort.column()] += marker;
        int[] widths = columnWidths(titles, cells);
        Row header = Row.from(
                        right(shown[0], widths[0]),
                        Cell.from(shown[1]),
                        Cell.from(FLAGS_LABEL),
                        right("Norm", widths[3]),
                        Cell.from("Value"))
                .style(Palette.current().heading());
        List<Row> tableRows = new ArrayList<>();
        for (int i = 0; i < cells.size(); i++) {
            String[] cellRow = cells.get(i);
            Cell name = matched.get(i).booleanValue()
                    ? Cell.from(
                            Line.from(Span.styled(cellRow[1], Palette.current().match())))
                    : Cell.from(cellRow[1]);
            Row tableRow = Row.from(
                    right(cellRow[0], widths[0]),
                    name,
                    Cell.from(cellRow[2]),
                    right(cellRow[3], widths[3]),
                    Cell.from(cellRow[4]));
            tableRows.add(
                    empty.get(i).booleanValue()
                            ? tableRow.style(Palette.current().muted())
                            : tableRow);
        }
        String title = fieldsTitle();
        if (row >= 0) {
            table.select(row);
        } else {
            table.clearSelection();
        }
        Table widget = Table.builder()
                .rows(tableRows)
                .header(header)
                .widths(
                        Constraint.length(widths[0]),
                        Constraint.length(Math.min(widths[1], NAME_MAX_WIDTH)),
                        Constraint.length(widths[2]),
                        Constraint.length(widths[3]),
                        Constraint.fill())
                .columnSpacing(COLUMN_SPACING)
                .highlightStyle(highlight(focused))
                .highlightSymbol("")
                .highlightSpacing(Table.HighlightSpacing.NEVER)
                .block(panel(title, focused))
                .build();
        frame.renderStatefulWidget(widget, area, table);
    }

    /**
     * The widths of the first four columns: of their title or their widest cell, the sortable
     * columns with room for the marker.
     */
    private static int[] columnWidths(String[] titles, List<String[]> cells) {
        int[] widths = new int[4];
        for (int column = 0; column < 4; column++) {
            int width = titles[column].length() + (column < 2 ? SORT_MARKER_WIDTH : 0);
            for (String[] cellRow : cells) {
                width = Math.max(width, cellRow[column].codePointCount(0, cellRow[column].length()));
            }
            widths[column] = width;
        }
        return widths;
    }

    /** How many fields there are, how many are shown, and the seek. */
    private String fieldsTitle() {
        int shown = rows.size();
        int total = document.fields().size();
        String count =
                (shown == total ? Integer.toString(total) : shown + " of " + total + ", e shows all") + matchesTitle();
        if (seek == null) {
            return FIELDS_TITLE + count + ", / seeks) ";
        }
        if (!seek.isEmpty() && seekMatch().isEmpty()) {
            return FIELDS_TITLE + count + ") /" + TextFormat.escapeControl(seek.toString()) + "▏ no match ";
        }
        return FIELDS_TITLE + count + ") /" + TextFormat.escapeControl(seek.toString()) + "▏ ";
    }

    private void renderDetails(Frame frame, Rect area) {
        boolean focused = focus == Focus.DETAILS;
        Optional<DocumentField> selected = selectedField();
        if (selected.isEmpty()) {
            frame.renderWidget(panel(" Details ", focused), area);
            return;
        }
        DocumentField field = selected.get();
        String title = " " + TextFormat.firstChars(TextFormat.escapeControl(field.name()), 40) + " ";
        Optional<TermHit> fieldHit = shownHit().filter(shown -> shown.field().equals(field.name()));
        List<Line> lines = detailLines(
                Optional.ofNullable(schema.get(field.name())),
                field,
                fieldHit,
                matchesLine(field.name()),
                matchesIn(field.name()),
                positions,
                termVectors.get(field.name()),
                reconstructions.get(field.name()),
                numbers,
                pointFormats,
                features,
                createdVersionMajorOf(docId));
        // TamboUI scrolls no further than to show the last line at the bottom, so the scroll stops
        // there too: the next key after End moves.
        Block block = panel(title, focused);
        Rect inner = block.inner(area);
        List<Line> wrapped = Panels.wrap(lines, inner.width());
        detailsScroll = Math.clamp(detailsScroll, 0, Math.max(wrapped.size() - inner.height(), 0));
        frame.renderWidget(
                Paragraph.builder()
                        .text(Text.from(wrapped))
                        .scroll(detailsScroll)
                        .block(block)
                        .build(),
                area);
    }

    /** What the fields table's title says about the matches of the search hit shown (ADR 0043). */
    private String matchesTitle() {
        Optional<Matches> shown = shownMatches();
        if (shown.isEmpty()) {
            return "";
        }
        if (shown.get().error != null) {
            return ", cannot find the matches";
        }
        if (shown.get().fields == null) {
            return ", finding matches…";
        }
        long fields = shown.get().fields.values().stream()
                .filter(field -> !field.values().isEmpty())
                .count();
        return ", matches in " + fields;
    }

    /**
     * The details' line about the matches of the search hit in {@code field}: how many there are,
     * where their offsets came from, or why they could not be found; empty if the field has none.
     */
    private Optional<String> matchesLine(String field) {
        Optional<Matches> shown = shownMatches();
        if (shown.isPresent() && shown.get().error != null) {
            return Optional.of("cannot find them: " + shown.get().error);
        }
        return matchesIn(field).map(found -> {
            int count =
                    found.values().stream().mapToInt(ValueHighlight::matchCount).sum();
            return count + " in " + TextFormat.count(found.values().size(), "value") + ", offsets from "
                    + Search.offsetSourceText(found.offsetSource()) + ", v views them"
                    + (found.truncated()
                            ? " (cut at " + TextFormat.size(HighlightOptions.MAX_DOCUMENT_CHARS) + ")"
                            : "");
        });
    }

    /**
     * Where the matches of stored value {@code index} fall in {@code shown}, the beginning of its
     * {@code text}: as {@link TextFormat#charRanges}, cut to the characters that {@code shown} has of
     * {@code text}.
     */
    private static List<int[]> valueMarks(String text, String shown, Optional<FieldMatches> matches, int index) {
        Optional<ValueHighlight> value = matches.flatMap(found -> found.values().stream()
                .filter(each -> each.valueIndex() == index)
                .findFirst());
        if (value.isEmpty()) {
            return List.of();
        }
        // Without the … that firstChars adds.
        int length = shown.equals(text) ? shown.length() : shown.length() - 1;
        return TextFormat.charRanges(text.substring(0, length), 0, value.get().spans());
    }

    /** Whether the document has nothing that {@code getDocument} reports in the field. It may still have postings there. */
    static boolean isEmpty(DocumentField field) {
        return FieldFlags.isEmpty(field);
    }

    /** As {@link FieldFlags#of}. */
    static String flags(Optional<IndexField> schema, DocumentField field) {
        return FieldFlags.of(schema.orElse(null), field);
    }

    /** The letters of {@link #flags}, in order. */
    private static final String FLAGS = FieldFlags.LETTERS;

    /** What each letter of {@link #FLAGS} stands for. */
    private static final List<String> FLAG_WORDS =
            List.of("indexed", "norm", "payloads", "stored", "term vector", "doc values", "points", "KNN vector");

    /** The flags that {@link #flags} set, in words: {@code IN-S----} is {@code indexed, norm, stored}. */
    static String flagWords(String flags) {
        List<String> words = new ArrayList<>();
        for (int i = 0; i < flags.length(); i++) {
            if (flags.charAt(i) == FLAGS.charAt(i)) {
                words.add(FLAG_WORDS.get(i));
            }
        }
        return words.isEmpty() ? "none" : String.join(", ", words);
    }

    /** One line for the value column: the first stored value, or else the doc values, or else the vector. */
    private static String valueSummary(DocumentField field, NumberFormat numbers) {
        if (!field.stored().isEmpty()) {
            String text = TextFormat.escapeControl(
                    TextFormat.firstChars(storedText(field.stored().getFirst()), SUMMARY_CHARS));
            int count = field.stored().size();
            return count == 1 ? text : text + " (+" + (count - 1) + " more)";
        }
        Optional<DocValues> docValues = field.docValues();
        if (docValues.isPresent()) {
            return "dv: "
                    + TextFormat.escapeControl(TextFormat.firstChars(
                            String.join(", ", docValueTexts(docValues.get(), numbers)), SUMMARY_CHARS));
        }
        return field.vector().map(Documents::vectorSummary).orElse("");
    }

    /** A vector's first components and its dimension: {@code [0.734, -0.211, …] 8d}. */
    static String vectorSummary(KnnVector vector) {
        List<String> components = vectorTexts(vector);
        StringBuilder text = new StringBuilder("[");
        for (int i = 0; i < components.size(); i++) {
            String next = (i == 0 ? "" : ", ") + components.get(i);
            if (text.length() + next.length() > SUMMARY_CHARS) {
                text.append(", …");
                break;
            }
            text.append(next);
        }
        return text.append("] ").append(vector.dimension()).append('d').toString();
    }

    /** Each component of a vector as text: floats exactly, as Java writes them, and bytes as signed numbers. */
    static List<String> vectorTexts(KnnVector vector) {
        return switch (vector.encoding()) {
            case FLOAT32 -> vector.floats().stream().map(f -> Float.toString(f)).toList();
            case BYTE -> vector.bytes().stream().map(b -> Byte.toString(b)).toList();
        };
    }

    /** A vector's euclidean length; a field compared by dot product holds vectors of length 1. */
    static double vectorLength(KnnVector vector) {
        double sum = 0;
        for (Float f : vector.floats()) {
            sum += (double) f * f;
        }
        for (Byte b : vector.bytes()) {
            sum += (double) b * b;
        }
        return Math.sqrt(sum);
    }

    /** A vector's components, {@link #VECTOR_LINE} to a line, each line after the index of its first. */
    static List<Line> vectorLines(KnnVector vector) {
        List<String> components = vectorTexts(vector);
        int width = Integer.toString(Math.max(components.size() - 1, 0)).length();
        List<Line> lines = new ArrayList<>();
        Style dim = Palette.current().muted();
        String index = "  %" + width + "d  ";
        for (int from = 0; from < components.size(); from += VECTOR_LINE) {
            List<String> line = components.subList(from, Math.min(from + VECTOR_LINE, components.size()));
            lines.add(Line.from(
                    Span.styled(String.format(Locale.ROOT, index, from), dim), Span.raw(String.join("  ", line))));
        }
        return lines;
    }

    /** A stored value as text, unescaped: binary values that are not UTF-8 as {@code 0x} and their hex. */
    private static String storedText(StoredValue value) {
        return value.binary() ? "0x" + value.value() : value.value();
    }

    /** Each doc value as text: numbers in {@code numbers}' format, byte strings as text or hex. */
    private static List<String> docValueTexts(DocValues values, NumberFormat numbers) {
        List<String> texts = new ArrayList<>();
        for (long number : values.numbers()) {
            texts.add(numbers.format(number));
        }
        for (BytesValue value : values.values()) {
            texts.add(TextFormat.bytesText(value.value(), value.binary()));
        }
        return texts;
    }

    private static Span label(String text) {
        return Span.styled(
                String.format(Locale.ROOT, "%-12s", text), Palette.current().label());
    }

    private static Line labelled(String label, String text) {
        return Line.from(label(label), Span.raw(text));
    }

    /**
     * Everything about the selected field: how it is indexed, then what the document has in it, as
     * far as the index has the {@code features} to read it (ADR 0044).
     */
    // Separate options of the call (ADR 0088)
    @SuppressWarnings("java:S107")
    private static List<Line> detailLines(
            Optional<IndexField> schema,
            DocumentField field,
            Optional<TermHit> hit,
            Optional<String> matchesLine,
            Optional<FieldMatches> matches,
            Positions positions,
            TermVector termVector,
            Reconstruction reconstruction,
            NumberFormat numbers,
            PointFormats pointFormats,
            Set<Feature> features,
            int createdVersionMajor) {
        List<Line> lines = new ArrayList<>();
        String flags = flags(schema, field);
        lines.add(labelled(FLAGS_LABEL, flags + "  " + flagWords(flags)));
        schema.ifPresent(index -> lines.addAll(schemaLines(index)));
        lines.add(labelled(
                "Norm", field.norm().isPresent() ? normText(field.norm().getAsLong(), createdVersionMajor) : "-"));
        matchesLine.ifPresent(text -> lines.add(labelled("Matches", text)));
        if (hit.isPresent()) {
            lines.add(Line.empty());
            lines.addAll(hitLines(hit.get(), positions));
        }

        lines.add(Line.empty());
        lines.addAll(storedLines(field, matches));

        lines.add(Line.empty());
        lines.addAll(docValuesLines(field, numbers));

        lines.addAll(vectorSection(schema, field));

        lines.add(Line.empty());
        lines.addAll(termVectorLines(field, termVector, features));

        if (features.contains(Feature.RECONSTRUCT)
                && schema.filter(Documents::rebuildable).isPresent()) {
            lines.add(Line.empty());
            lines.addAll(reconstructionLines(reconstruction, schema.get(), pointFormats));
        }
        return lines;
    }

    /** How the field is indexed, and what else it has. */
    private static List<Line> schemaLines(IndexField index) {
        List<Line> lines = new ArrayList<>();
        List<String> indexed = new ArrayList<>();
        indexed.add(
                index.indexOptions() == IndexOptions.NONE ? "not indexed" : Overview.postings(index.indexOptions()));
        if (index.hasPayloads()) {
            indexed.add("payloads");
        }
        lines.add(labelled("Indexed", String.join(", ", indexed)));
        List<String> other = new ArrayList<>();
        if (index.hasNorms()) {
            other.add("norms");
        }
        if (index.hasTermVectors()) {
            other.add("term vectors");
        }
        if (index.docValuesType() != DocValuesType.NONE) {
            other.add(Overview.docValues(index.docValuesType()) + " doc values");
        }
        if (index.pointDimensionCount() > 0) {
            other.add("points " + index.pointDimensionCount() + "×" + index.pointNumBytes() + "B");
        }
        if (index.vectorDimension() > 0) {
            other.add("vectors " + index.vectorDimension() + "d"
                    + index.vectorEncoding()
                            .map(e -> " " + Overview.vectorEncoding(e))
                            .orElse("")
                    + index.vectorSimilarity()
                            .map(s -> " " + Overview.vectorSimilarity(s))
                            .orElse(""));
        }
        if (!other.isEmpty()) {
            lines.add(labelled("Field has", String.join(", ", other)));
        }
        return lines;
    }

    private static List<Line> storedLines(DocumentField field, Optional<FieldMatches> matches) {
        if (field.stored().isEmpty()) {
            return List.of(labelled("Stored", "-"));
        }
        List<Line> lines = new ArrayList<>();
        lines.add(labelled("Stored", TextFormat.count(field.stored().size(), "value")));
        for (int v = 0; v < field.stored().size(); v++) {
            lines.addAll(storedValueLines(field.stored().get(v), v, matches));
        }
        return lines;
    }

    /**
     * Multi-line text keeps its lines, indented under the first; a long value shows its beginning,
     * and the viewer the rest.
     */
    private static List<Line> storedValueLines(StoredValue value, int v, Optional<FieldMatches> matches) {
        List<Line> lines = new ArrayList<>();
        Style dim = Palette.current().muted();
        String text = storedText(value);
        String shown = TextFormat.firstChars(text, DETAIL_VALUE_CHARS);
        boolean more = !shown.equals(text);
        List<int[]> marks = valueMarks(text, shown, matches, v);
        String[] valueLines = shown.split("\n", -1);
        int lineStart = 0;
        for (int i = 0; i < valueLines.length; i++) {
            if (i == DETAIL_VALUE_LINES) {
                more = true;
                break;
            }
            String prefix =
                    i == 0 ? String.format(Locale.ROOT, "  %-8s", Viewer.storedType(value.type())) : " ".repeat(10);
            List<Span> spans = new ArrayList<>();
            spans.add(Span.styled(prefix, dim));
            spans.addAll(TextFormat.marked(shown, lineStart, lineStart + valueLines[i].length(), marks));
            lines.add(Line.from(spans));
            lineStart += valueLines[i].length() + 1;
        }
        if (value.fullLength().isPresent()) {
            lines.add(Line.styled(
                    " ".repeat(10) + "cut at " + Viewer.cutSize() + " of "
                            + TextFormat.size(value.fullLength().getAsLong()) + ", v views it whole",
                    dim));
        } else if (more) {
            lines.add(Line.styled(" ".repeat(10) + "… v views it whole", dim));
        }
        return lines;
    }

    private static List<Line> docValuesLines(DocumentField field, NumberFormat numbers) {
        Optional<DocValues> docValues = field.docValues();
        if (docValues.isEmpty()) {
            return List.of(labelled("Doc values", "-"));
        }
        List<Line> lines = new ArrayList<>();
        DocValues values = docValues.get();
        String heading = Overview.docValues(values.type());
        if (!values.numbers().isEmpty()) {
            heading += AS + numbers.label() + " (n)";
        }
        lines.add(labelled("Doc values", heading));
        for (String text : docValueTexts(values, numbers)) {
            lines.add(Line.from("  " + text));
        }
        return lines;
    }

    /** The field's vector, for a field with vectors. */
    private static List<Line> vectorSection(Optional<IndexField> schema, DocumentField field) {
        if (field.vector().isEmpty()
                && schema.filter(index -> index.vectorDimension() > 0).isEmpty()) {
            return List.of();
        }
        List<Line> lines = new ArrayList<>();
        lines.add(Line.empty());
        Optional<KnnVector> knnVector = field.vector();
        if (knnVector.isEmpty()) {
            lines.add(labelled("Vector", "-"));
            return lines;
        }
        KnnVector vector = knnVector.get();
        lines.add(labelled(
                "Vector",
                vector.dimension() + "d " + Overview.vectorEncoding(vector.encoding()) + ", length "
                        + String.format(Locale.ROOT, "%.6g", vectorLength(vector))
                        + ", N finds the nearest, T its graph node"));
        lines.addAll(vectorLines(vector));
        return lines;
    }

    private static List<Line> termVectorLines(DocumentField field, TermVector termVector, Set<Feature> features) {
        if (!field.hasTermVector()) {
            return List.of(labelled(TERM_VECTOR, "-"));
        }
        List<Line> lines = new ArrayList<>();
        switch (termVector) {
            case null ->
                lines.add(labelled(TERM_VECTOR, features.contains(Feature.TERM_VECTORS) ? "⏎ loads it" : "not read"));
            case TermVector.Failed failed -> lines.add(labelled(TERM_VECTOR, "cannot read it: " + failed.error()));
            case TermVector.Loaded loaded -> {
                lines.add(labelled(TERM_VECTOR, TextFormat.count(loaded.terms().size(), "term")));
                lines.addAll(termLines(loaded.terms(), true));
            }
        }
        return lines;
    }

    /**
     * The terms and points the field was indexed with, once rebuilt: the terms in position order if
     * they have positions, then term by term; the points one per line, decoded.
     */
    private static List<Line> reconstructionLines(
            Reconstruction reconstruction, IndexField field, PointFormats pointFormats) {
        boolean terms = field.indexOptions() != IndexOptions.NONE;
        boolean points = field.pointDimensionCount() > 0;
        if (reconstruction == null) {
            String what = "the terms";
            if (points) {
                what = terms ? "the terms and points" : "the points";
            }
            return List.of(labelled("Rebuilt", "I rebuilds " + what + " it was indexed with"));
        }
        List<Line> lines = new ArrayList<>();
        PointFormat format = pointFormats.of(field);
        String summary = reconstruction.summary(terms, points);
        boolean onlyPoints = points && !terms && reconstruction instanceof Reconstruction.Done;
        lines.add(labelled("Rebuilt", onlyPoints ? summary + AS + format.label() + " (t)" : summary));
        if (reconstruction instanceof Reconstruction.Done done) {
            lines.addAll(rebuiltLines(done, terms, points, format));
        }
        return lines;
    }

    /** The terms and the points that a rebuild found. */
    private static List<Line> rebuiltLines(
            Reconstruction.Done done, boolean terms, boolean points, PointFormat format) {
        List<Line> lines = new ArrayList<>();
        if (terms) {
            Optional<String> tokens = Reconstruction.tokenLine(done.field().terms());
            if (tokens.isPresent()) {
                lines.add(Line.from("  " + tokens.get()));
                lines.add(Line.empty());
            }
            lines.addAll(termLines(done.field().terms(), Reconstruction.hasFreqs(done.field())));
        }
        if (points) {
            if (terms) {
                lines.add(Line.empty());
                lines.add(labelled(
                        "Points",
                        TextFormat.count(done.field().points().size(), "point") + AS + format.label() + " (t)"));
            }
            for (PointValue point : done.field().points()) {
                lines.add(Line.from("  " + format.format(point)));
            }
        }
        return lines;
    }

    /** One line per term: the term, {@code ×freq} if {@code freqs}, and where it occurs if known. */
    static List<Line> termLines(List<TermVectorTerm> terms, boolean freqs) {
        List<Line> lines = new ArrayList<>();
        for (TermVectorTerm term : terms) {
            List<Span> spans = new ArrayList<>();
            spans.add(Span.raw("  " + TextFormat.bytesText(term.term(), term.binary())));
            if (freqs) {
                spans.add(Span.styled(" ×" + term.freq(), Palette.current().muted()));
            }
            if (!term.positions().isEmpty()) {
                spans.add(Span.raw("  "
                        + term.positions().stream().map(Documents::positionText).collect(Collectors.joining(", "))));
            }
            lines.add(Line.from(spans));
        }
        return lines;
    }

    /** The term of the hit, how often it occurs, and where. */
    private static List<Line> hitLines(TermHit hit, Positions positions) {
        List<Span> heading = new ArrayList<>();
        heading.add(label("Term"));
        heading.add(Span.raw(TextFormat.bytesText(hit.term().value(), hit.term().binary())));
        if (hit.freq().isPresent()) {
            heading.add(
                    Span.styled(" ×" + hit.freq().getAsInt(), Palette.current().muted()));
        }
        heading.add(Span.raw(", doc " + (hit.index() + 1) + " of " + hit.docFreq() + " ([ ])"));
        List<Line> lines = new ArrayList<>();
        lines.add(Line.from(heading));
        switch (positions) {
            // Fetched right after the document, so this shows only for a moment.
            case null -> {}
            case Positions.Failed failed -> lines.add(Line.from("  cannot read the positions: " + failed.error()));
            case Positions.Loaded loaded
            when loaded.positions().isEmpty() -> lines.add(Line.from("  no positions indexed"));
            case Positions.Loaded loaded ->
                lines.add(Line.from("  "
                        + loaded.positions().stream()
                                .map(Documents::positionText)
                                .collect(Collectors.joining(", "))));
        }
        return lines;
    }

    /** {@code 3}, {@code 3 [9–12]}, {@code [9–12]} or with a payload {@code 3 [9–12] ⟨0a0b⟩}. */
    static String positionText(TermPosition position) {
        List<String> parts = new ArrayList<>();
        position.position().ifPresent(at -> parts.add(Integer.toString(at)));
        if (position.startOffset().isPresent() && position.endOffset().isPresent()) {
            parts.add("[" + position.startOffset().getAsInt() + "–"
                    + position.endOffset().getAsInt() + "]");
        }
        position.payload().ifPresent(payload -> parts.add("⟨" + payload + "⟩"));
        return String.join(" ", parts);
    }

    /** The first major Lucene version whose similarities encode the field length itself. */
    static final int LENGTH_NORMS_SINCE = 7;

    /**
     * The norm, and the field length it stands for if Lucene's similarities wrote it in an index
     * that Lucene {@code createdVersionMajor} created. Before 7, norms include the index-time boost.
     */
    static String normText(long norm, int createdVersionMajor) {
        String boost = createdVersionMajor < LENGTH_NORMS_SINCE ? " at boost 1" : "";
        return normLength(norm, createdVersionMajor)
                .map(length -> length.low() == length.high()
                        ? norm + " → length " + length.low() + boost
                        : norm + " → length " + length.low() + "–" + length.high() + boost)
                .orElse(Long.toString(norm));
    }

    /** The field lengths a norm stands for, from {@code low} to {@code high}. */
    record NormLength(long low, long high) {}

    /**
     * The field lengths a norm stands for if Lucene's similarities wrote it. From Lucene 7 they all
     * encode the number of terms with {@code SmallFloat.intToByte4}, which is exact below 40 and a
     * range above. Before 7, the default and BM25 similarities stored {@code
     * floatToByte315(boost / √length)}; the lengths are those at boost 1. Empty for a norm that is not
     * a byte, which some other similarity wrote, and before 7 for one that no length gives at boost 1.
     */
    static Optional<NormLength> normLength(long norm, int createdVersionMajor) {
        if (norm < Byte.MIN_VALUE || norm > Byte.MAX_VALUE) {
            return Optional.empty();
        }
        int b = (int) norm & 0xff;
        if (createdVersionMajor < LENGTH_NORMS_SINCE) {
            return byte315Length(b);
        }
        long low = byte4ToInt(b);
        long high = b < 0xff ? byte4ToInt(b + 1) - 1 : low;
        return Optional.of(new NormLength(low, high));
    }

    /**
     * The lengths L with {@code floatToByte315(1 / √L) == b}: those with {@code byte315ToFloat(b) ≤ 1 /
     * √L < byte315ToFloat(b + 1)}, adjusted at the edges by encoding them. Byte 255 is length 0, whose
     * norm is infinite.
     */
    private static Optional<NormLength> byte315Length(int b) {
        if (b == 0xff) {
            return Optional.of(new NormLength(0, 0));
        }
        if (b == 0) {
            return Optional.empty();
        }
        double low = 1 / Math.pow(byte315ToFloat(b + 1), 2);
        double high = 1 / Math.pow(byte315ToFloat(b), 2);
        long from = Math.max((long) Math.floor(low), 1);
        long to = Math.min((long) Math.ceil(high), Integer.MAX_VALUE);
        while (from <= to && lengthNorm(from) != b) {
            from++;
        }
        while (to >= from && lengthNorm(to) != b) {
            to--;
        }
        return from <= to ? Optional.of(new NormLength(from, to)) : Optional.empty();
    }

    /** The norm byte that Lucene's {@code DefaultSimilarity} before 7 stored for {@code length} terms. */
    private static int lengthNorm(long length) {
        return floatToByte315((float) (1.0 / Math.sqrt(length)));
    }

    /** {@code SmallFloat.byte315ToFloat}: 3 mantissa bits, 5 exponent bits, zero exponent at 15. */
    private static float byte315ToFloat(int b) {
        if (b == 0) {
            return 0f;
        }
        int bits = (b & 0xff) << (24 - 3);
        bits += (63 - 15) << 24;
        return Float.intBitsToFloat(bits);
    }

    /** {@code SmallFloat.floatToByte315}, as an unsigned byte; it rounds down. */
    private static int floatToByte315(float f) {
        int bits = Float.floatToRawIntBits(f);
        int smallfloat = bits >> (24 - 3);
        if (smallfloat <= ((63 - 15) << 3)) {
            return bits <= 0 ? 0 : 1;
        }
        if (smallfloat >= ((63 - 15) << 3) + 0x100) {
            return 0xff;
        }
        return smallfloat - ((63 - 15) << 3);
    }

    /**
     * {@code SmallFloat.byte4ToInt}: values below 24 stand for themselves, the others are a 4-bit
     * float ({@code int4ToLong}) on top of 24.
     */
    private static long byte4ToInt(int b) {
        int numFreeValues = 24;
        if (b < numFreeValues) {
            return b;
        }
        long int4 = (long) b - numFreeValues;
        long bits = int4 & 0x07;
        long exponent = int4 >>> 3;
        long decoded = exponent == 0 ? bits : (bits | 0x08) << (exponent - 1);
        return numFreeValues + decoded;
    }

    private static void renderPrompt(Frame frame, Rect area, DocIdPrompt prompt, int maxDoc) {
        int width = Math.min(area.width(), 48);
        int height = 4;
        Rect popup = new Rect(
                area.x() + (area.width() - width) / 2,
                area.y() + Math.max(area.height() - height, 0) / 2,
                width,
                Math.min(height, area.height()));
        String message =
                prompt.error != null ? prompt.error : "0 to " + (maxDoc - 1) + "; Enter goes there, Esc cancels.";
        Panels.clear(frame, popup);
        frame.renderWidget(
                Paragraph.builder()
                        .text(Text.from(
                                Line.from(prompt.input + "▏"),
                                Line.styled(message, Palette.current().muted())))
                        .block(Panels.box(" Go to document "))
                        .build(),
                popup);
    }
}
