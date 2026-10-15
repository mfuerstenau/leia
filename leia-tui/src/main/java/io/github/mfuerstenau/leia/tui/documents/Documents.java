package io.github.mfuerstenau.leia.tui.documents;

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
import dev.tamboui.widgets.Clear;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.block.Title;
import dev.tamboui.widgets.paragraph.Paragraph;
import dev.tamboui.widgets.table.Cell;
import dev.tamboui.widgets.table.Row;
import dev.tamboui.widgets.table.Table;
import dev.tamboui.widgets.table.TableState;
import io.github.mfuerstenau.leia.protocol.document.BytesValue;
import io.github.mfuerstenau.leia.protocol.document.DocValues;
import io.github.mfuerstenau.leia.protocol.document.DocumentField;
import io.github.mfuerstenau.leia.protocol.document.GetDocumentResult;
import io.github.mfuerstenau.leia.protocol.document.GetReconstructResult;
import io.github.mfuerstenau.leia.protocol.document.KnnVector;
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
import io.github.mfuerstenau.leia.protocol.search.FieldMatches;
import io.github.mfuerstenau.leia.protocol.search.GetHighlightResult;
import io.github.mfuerstenau.leia.protocol.search.HighlightOptions;
import io.github.mfuerstenau.leia.protocol.search.MatchSpan;
import io.github.mfuerstenau.leia.protocol.search.SearchQuery;
import io.github.mfuerstenau.leia.protocol.search.StartHighlightParams;
import io.github.mfuerstenau.leia.protocol.search.ValueHighlight;
import io.github.mfuerstenau.leia.tui.documents.DocFilter.FilterStep;
import io.github.mfuerstenau.leia.tui.overview.Overview;
import io.github.mfuerstenau.leia.tui.points.PointFormat;
import io.github.mfuerstenau.leia.tui.points.PointFormats;
import io.github.mfuerstenau.leia.tui.search.Search;
import io.github.mfuerstenau.leia.tui.terms.Terms.TermHit;
import io.github.mfuerstenau.leia.tui.text.TextFormat;
import io.github.mfuerstenau.leia.tui.widgets.Panels;
import io.github.mfuerstenau.leia.tui.widgets.Selection;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The documents screen: one document of the open index at a time, field by field, as {@code
 * getDocument} reports it (ADR 0006), with a panel for the selected field's values and its term
 * vector. A document opened from the terms screen also shows where the term occurs in it, and
 * {@code [}/{@code ]} step through the term's other documents. {@code v} opens the selected field's
 * values in the {@link Viewer}, and {@code r} rebuilds the terms the field was indexed with (ADR
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
public final class Documents {

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

    /** An index directory: the document id it starts at, and its name as shown. */
    public record IndexStart(int docBase, String name) {}

    /** What the navigation keys move. */
    private enum Focus {
        FIELDS,
        DETAILS
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
     * With several index directories, where each starts and its name as shown, in order (ADR 0021);
     * empty with one.
     */
    private List<IndexStart> indexes = List.of();

    /** What the index can do (ADR 0044): the keys it lacks a feature for are refused and left out of the legend. */
    private Set<Feature> features = Feature.all();

    public Documents(List<IndexField> fields, int maxDoc) {
        this.schema = fields.stream()
                .collect(Collectors.toMap(IndexField::name, Function.identity(), (a, b) -> a, HashMap::new));
        this.maxDoc = maxDoc;
    }

    /**
     * Shows which index directory a document is in: each with the document id it starts at and its
     * name, in order. With one, nothing is shown.
     */
    public Documents withIndexes(List<IndexStart> indexes) {
        this.indexes = indexes.size() > 1 ? List.copyOf(indexes) : List.of();
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

    /** What the index says about the field {@code name}; empty if it doesn't know it. */
    public Optional<IndexField> field(String name) {
        return Optional.ofNullable(schema.get(name));
    }

    /** The name of the index directory that {@code docId} is in, with several of them. */
    public Optional<String> indexOf(int docId) {
        IndexStart in = null;
        for (IndexStart index : indexes) {
            if (index.docBase() > docId) {
                break;
            }
            in = index;
        }
        return Optional.ofNullable(in).map(IndexStart::name);
    }

    /**
     * The screen for the reopened index (ADR 0019): the same document id, clamped to the new {@code
     * maxDoc}, with the same field selected, and values shown as before. The document is fetched
     * again; walks through term docs or hits end. The backend forgets the filter, which is built
     * again under a new serial (ADR 0032).
     */
    public Documents reopened(List<IndexField> fields, int maxDoc) {
        Documents documents = new Documents(fields, maxDoc);
        documents.indexes = indexes;
        documents.features = features;
        documents.docId = Math.max(Math.min(docId, maxDoc - 1), 0);
        documents.selectField = selectedField().map(DocumentField::name).orElse(null);
        documents.hideEmpty = hideEmpty;
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
    }

    /**
     * Forgets the term hit: the terms screen opened a document with a point of {@code field} (ADR
     * 0041), which is selected once the document is in.
     */
    public void showPoint(String field) {
        selectField = field;
        hit = null;
        searchHits = false;
        positions = null;
        dropMatches();
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
        if (!findsMatches(matchesId) || result.highlight().isEmpty()) {
            return;
        }
        Map<String, FieldMatches> fields = new HashMap<>();
        for (FieldMatches field : result.highlight().get().fields()) {
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
        return switch (filter.stepped(params.fromDoc(), params.forward(), step, found)) {
            case DocFilter.Stepped.Show show -> Optional.of(new Request.Document(show.docId()));
            case DocFilter.Stepped.LookBack back ->
                Optional.of(new Request.StepFilter(
                        serial, new StepFilterParams(params.filterId(), back.docId(), false), FilterStep.MOVE));
            case DocFilter.Stepped.Nothing _ -> {
                steppingFilter = OptionalLong.empty();
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
     * Forgets the rebuild that is asked for or runs, so that {@code r} can start it again. Returns
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
        if (result.result().isPresent()) {
            next = result.result().get().fields().stream()
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
        row = 0;
        for (int i = 0; i < rows.size(); i++) {
            if (fields.get(rows.get(i)).name().equals(selected)) {
                row = i;
                break;
            }
        }
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
        if (field.isEmpty() || field.get().docValues().isEmpty()) {
            return Optional.empty();
        }
        DocValues docValues = field.get().docValues().get();
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
        return Optional.of(new Request.SearchDocValues(field.get().name(), numbers.docValuesNumber(), hex, deduped));
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
     * b} clears it. Loads the selected field's term vector with Enter, views its values with {@code
     * v}, analyzes its stored text with {@code a}, searches for its doc values with {@code s} and for
     * the documents nearest to its vector with {@code N}, cycles
     * the number format with {@code x} and the point format with {@code t}, hides empty fields with
     * {@code e}, searches for documents like this one with {@code m}, rebuilds the field's indexed
     * terms and points with {@code r}, switches the focus with Tab, and moves the selection or
     * scrolls the details.
     */
    public Optional<Request> handleKey(KeyEvent key) {
        if (viewer != null) {
            Optional<Viewer.Event> event = viewer.handleKey(key);
            if (event.isPresent() && event.get() instanceof Viewer.Event.Close) {
                closeViewer();
            }
            return event.flatMap(Documents::viewerRequest);
        }
        if (seek != null) {
            seekKey(key);
            return Optional.empty();
        }
        if (prompt != null) {
            if (key.code() == KeyCode.ESCAPE) {
                prompt = null;
                return Optional.empty();
            }
            OptionalInt chosen = promptKey(prompt, key, maxDoc);
            if (chosen.isEmpty()) {
                return Optional.empty();
            }
            prompt = null;
            return stepsThroughFilter()
                    ? filterStep(chosen.getAsInt(), true, FilterStep.NEAREST)
                    : Optional.of(new Request.Document(chosen.getAsInt()));
        }
        if (filter != null) {
            filter.clearNote();
        }
        KeyCode code = key.code();
        // A key pressed before the last step's document is in goes on from that document.
        int from = wanted.orElse(docId);
        if (code == KeyCode.LEFT || key.isChar('p')) {
            if (stepsThroughFilter()) {
                return moveThroughFilter(false);
            }
            return from > 0 ? Optional.of(new Request.Document(from - 1)) : Optional.empty();
        }
        if (code == KeyCode.RIGHT || key.isChar('n')) {
            if (stepsThroughFilter()) {
                return moveThroughFilter(true);
            }
            return from < maxDoc - 1 ? Optional.of(new Request.Document(from + 1)) : Optional.empty();
        }
        Optional<String> unsupported = unsupported(key);
        if (unsupported.isPresent()) {
            return unsupported.map(Request.Unsupported::new);
        }
        if (code == KeyCode.ENTER) {
            Optional<DocumentField> field = selectedField();
            if (field.isEmpty()
                    || !field.get().hasTermVector()
                    || termVectors.containsKey(field.get().name())) {
                return Optional.empty();
            }
            return Optional.of(new Request.TermVector(docId, field.get().name()));
        }
        if (code == KeyCode.TAB) {
            focus = focus == Focus.FIELDS ? Focus.DETAILS : Focus.FIELDS;
            return Optional.empty();
        }
        if (code == KeyCode.CHAR) {
            switch (key.codePoint()) {
                case 'i' -> {
                    if (maxDoc > 0) {
                        prompt = new DocIdPrompt();
                    }
                    return Optional.empty();
                }
                case '/' -> {
                    if (!rows.isEmpty()) {
                        seek = new StringBuilder();
                        focus = Focus.FIELDS;
                    }
                    return Optional.empty();
                }
                case '[', ']' -> {
                    boolean forward = key.codePoint() == ']';
                    return walk().map(walk -> new Request.Step(walk, forward));
                }
                case 'v' -> {
                    return view();
                }
                case 'r' -> {
                    return reconstruct();
                }
                case 'b' -> {
                    return clearFilter();
                }
                case 'a' -> {
                    return analyze();
                }
                case 'm' -> {
                    return maxDoc > 0 ? Optional.of(new Request.MoreLikeThis(docId)) : Optional.empty();
                }
                case 'x' -> {
                    numbers = numbers.next();
                    return Optional.empty();
                }
                case 't' -> {
                    selectedField()
                            .map(field -> schema.get(field.name()))
                            .filter(field -> field.pointDimensionCount() > 0)
                            .ifPresent(pointFormats::cycle);
                    return Optional.empty();
                }
                case 's' -> {
                    return searchDocValues();
                }
                case 'N' -> {
                    return selectedField()
                            .filter(field -> field.vector().isPresent())
                            .map(field -> new Request.SearchKnn(field.name(), docId));
                }
                case 'E' -> {
                    return Optional.ofNullable(document).map(doc -> new Request.Export(doc.docId()));
                }
                case 'e' -> {
                    hideEmpty = !hideEmpty;
                    updateRows(selectedField().map(DocumentField::name).orElse(null));
                    return Optional.empty();
                }
                default -> {}
            }
        }
        if (focus == Focus.DETAILS) {
            scrollDetails(key);
        } else if (!rows.isEmpty()) {
            OptionalInt moved = Selection.moved(row, rows.size(), key);
            if (moved.isPresent() && moved.getAsInt() != row) {
                row = moved.getAsInt();
                detailsScroll = 0;
            }
        }
        return Optional.empty();
    }

    /** What {@code key} is called if the index lacks a feature it needs (ADR 0044); empty otherwise. */
    private Optional<String> unsupported(KeyEvent key) {
        if (key.code() == KeyCode.ENTER) {
            return supports(Feature.TERM_VECTORS) ? Optional.empty() : Optional.of("Term vector");
        }
        if (key.code() != KeyCode.CHAR) {
            return Optional.empty();
        }
        return switch (key.codePoint()) {
            case 'v' -> supports(Feature.STORED_VALUES) ? Optional.empty() : Optional.of("View");
            case 'E' -> supports(Feature.STORED_VALUES) ? Optional.empty() : Optional.of("Export");
            case 'r' -> supports(Feature.RECONSTRUCT) ? Optional.empty() : Optional.of("Rebuild");
            case 't' -> supports(Feature.POINTS) ? Optional.empty() : Optional.of("Points");
            case 's' -> supports(Feature.SEARCH, Feature.DOC_VALUES) ? Optional.empty() : Optional.of("Search value");
            case 'N' -> supports(Feature.SEARCH, Feature.KNN_VECTORS) ? Optional.empty() : Optional.of("Nearest");
            case 'm' -> supports(Feature.SEARCH) ? Optional.empty() : Optional.of("Similar");
            case 'b' -> supports(Feature.FILTER) ? Optional.empty() : Optional.of("Filter");
            default -> Optional.empty();
        };
    }

    /**
     * The screen's keys for the footer, after the app's: those the index has the features for (ADR
     * 0044).
     */
    public String keys() {
        String steps = walk().map(walk -> walk == Walk.TERM_DOCS ? "[ ] term docs   " : "[ ] hits   ")
                .orElse("");
        String step = filtered() ? "←→ match   b unfilter   " : "←→ doc   ";
        StringBuilder keys = new StringBuilder(step).append(steps).append("i go to   ");
        keyIf(keys, supports(Feature.TERM_VECTORS), "⏎ term vector");
        keyIf(keys, supports(Feature.STORED_VALUES), "v view");
        keys.append("a analyze   ");
        keyIf(keys, supports(Feature.SEARCH, Feature.DOC_VALUES), "s search value");
        keyIf(keys, supports(Feature.SEARCH, Feature.KNN_VECTORS), "N nearest");
        keys.append("x numbers   ");
        keyIf(keys, supports(Feature.POINTS), "t points");
        keys.append("e empty   ");
        keyIf(keys, supports(Feature.SEARCH), "m similar");
        keyIf(keys, supports(Feature.RECONSTRUCT), "r rebuild");
        keyIf(keys, supports(Feature.STORED_VALUES), "E export");
        return keys.append("Tab panel   ↑↓").toString();
    }

    private static void keyIf(StringBuilder keys, boolean shown, String key) {
        if (shown) {
            keys.append(key).append("   ");
        }
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
                } catch (NumberFormatException e) {
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
                        .block(Block.builder()
                                .borders(Borders.ALL)
                                .title(Title.from(title()))
                                .build())
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
        if (filter == null) {
            return Line.from(" Document ");
        }
        DocFilter.Title title = filter.title(docId, maxDoc);
        Style style = (title.error() ? Style.EMPTY.red() : Style.EMPTY.yellow()).bold();
        return Line.from(Span.raw(" Document "), Span.styled(title.text() + " ", style));
    }

    private Line header() {
        if (maxDoc == 0) {
            return Line.from("The index has no documents.");
        }
        List<Span> spans = new ArrayList<>();
        spans.add(Span.styled("#" + docId, Style.EMPTY.bold()));
        spans.add(Span.raw(" of 0–" + (maxDoc - 1)));
        indexOf(docId)
                .ifPresent(index ->
                        spans.add(Span.raw("   in " + TextFormat.firstChars(TextFormat.escapeControl(index), 60))));
        if (error != null) {
            spans.add(Span.raw("   Cannot read it: " + error));
        } else if (document != null && document.deleted()) {
            spans.add(Span.raw("   "));
            spans.add(Span.styled("deleted", Style.EMPTY.red().bold()));
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
                    shows(hit.docId()) ? Style.EMPTY : Style.EMPTY.dim()));
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
                TextFormat.escapeControl(field.name()),
                flags(Optional.ofNullable(schema.get(field.name())), field),
                field.norm().isPresent() ? Long.toString(field.norm().getAsLong()) : "-",
                valueSummary(field, numbers)
            });
        }
        String[] titles = {"Field", "Flags", "Norm", "Value"};
        int[] widths = new int[3];
        for (int column = 0; column < 3; column++) {
            int width = titles[column].length();
            for (String[] row : cells) {
                width = Math.max(width, row[column].codePointCount(0, row[column].length()));
            }
            widths[column] = width;
        }
        Row header = Row.from(Cell.from("Field"), Cell.from("Flags"), right("Norm", widths[2]), Cell.from("Value"))
                .style(Style.EMPTY.bold());
        List<Row> tableRows = new ArrayList<>();
        for (int i = 0; i < cells.size(); i++) {
            String[] row = cells.get(i);
            Cell name =
                    matched.get(i) ? Cell.from(Line.from(Span.styled(row[0], TextFormat.MATCH))) : Cell.from(row[0]);
            Row tableRow = Row.from(name, Cell.from(row[1]), right(row[2], widths[2]), Cell.from(row[3]));
            tableRows.add(empty.get(i) ? tableRow.style(Style.EMPTY.dim()) : tableRow);
        }
        int shown = rows.size();
        int total = document.fields().size();
        String count =
                (shown == total ? Integer.toString(total) : shown + " of " + total + ", e shows all") + matchesTitle();
        String title;
        if (seek == null) {
            title = " Fields (" + count + ", / seeks) ";
        } else if (!seek.isEmpty() && seekMatch().isEmpty()) {
            title = " Fields (" + count + ") /" + TextFormat.escapeControl(seek.toString()) + "▏ no match ";
        } else {
            title = " Fields (" + count + ") /" + TextFormat.escapeControl(seek.toString()) + "▏ ";
        }
        if (row >= 0) {
            table.select(row);
        } else {
            table.clearSelection();
        }
        Table widget = Table.builder()
                .rows(tableRows)
                .header(header)
                .widths(
                        Constraint.length(Math.min(widths[0], NAME_MAX_WIDTH)),
                        Constraint.length(widths[1]),
                        Constraint.length(widths[2]),
                        Constraint.fill())
                .columnSpacing(COLUMN_SPACING)
                .highlightStyle(highlight(focused))
                .highlightSymbol("")
                .highlightSpacing(Table.HighlightSpacing.NEVER)
                .block(panel(title, focused))
                .build();
        frame.renderStatefulWidget(widget, area, table);
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
                features);
        // TamboUI scrolls no further than to show the last line at the bottom, so the scroll stops
        // there too: the next key after End moves.
        Block block = panel(title, focused);
        Rect inner = block.inner(area);
        List<Line> wrapped = Panels.wrap(lines, inner.width());
        detailsScroll = Math.min(detailsScroll, Math.max(wrapped.size() - inner.height(), 0));
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
        return field.norm().isEmpty()
                && field.stored().isEmpty()
                && field.docValues().isEmpty()
                && !field.hasTermVector()
                && field.vector().isEmpty();
    }

    /**
     * {@code I} indexed, {@code N} norm, {@code P} payloads, {@code S} stored, {@code V} term vector,
     * {@code D} doc values, {@code T} points, {@code K} KNN vector; {@code -} where absent. {@code N},
     * {@code S}, {@code V}, {@code D} and {@code K} are about this document, the others about the
     * field.
     */
    static String flags(Optional<IndexField> schema, DocumentField field) {
        boolean[] set = {
            schema.filter(f -> f.indexOptions() != IndexOptions.NONE).isPresent(),
            field.norm().isPresent(),
            schema.filter(IndexField::hasPayloads).isPresent(),
            !field.stored().isEmpty(),
            field.hasTermVector(),
            field.docValues().isPresent(),
            schema.filter(f -> f.pointDimensionCount() > 0).isPresent(),
            field.vector().isPresent()
        };
        StringBuilder flags = new StringBuilder();
        for (int i = 0; i < set.length; i++) {
            flags.append(set[i] ? FLAGS.charAt(i) : '-');
        }
        return flags.toString();
    }

    /** The letters of {@link #flags}, in order. */
    private static final String FLAGS = "INPSVDTK";

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
        if (field.docValues().isPresent()) {
            return "dv: "
                    + TextFormat.escapeControl(TextFormat.firstChars(
                            String.join(", ", docValueTexts(field.docValues().get(), numbers)), SUMMARY_CHARS));
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
        for (float f : vector.floats()) {
            sum += (double) f * f;
        }
        for (byte b : vector.bytes()) {
            sum += (double) b * b;
        }
        return Math.sqrt(sum);
    }

    /** A vector's components, {@link #VECTOR_LINE} to a line, each line after the index of its first. */
    static List<Line> vectorLines(KnnVector vector) {
        List<String> components = vectorTexts(vector);
        int width = Integer.toString(Math.max(components.size() - 1, 0)).length();
        List<Line> lines = new ArrayList<>();
        Style dim = Style.EMPTY.dim();
        for (int from = 0; from < components.size(); from += VECTOR_LINE) {
            List<String> line = components.subList(from, Math.min(from + VECTOR_LINE, components.size()));
            lines.add(Line.from(
                    Span.styled(String.format(Locale.ROOT, "  %" + width + "d  ", from), dim),
                    Span.raw(String.join("  ", line))));
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
        return Span.styled(String.format(Locale.ROOT, "%-12s", text), Style.EMPTY.bold());
    }

    private static Line labelled(String label, String text) {
        return Line.from(label(label), Span.raw(text));
    }

    /**
     * Everything about the selected field: how it is indexed, then what the document has in it, as
     * far as the index has the {@code features} to read it (ADR 0044).
     */
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
            Set<Feature> features) {
        List<Line> lines = new ArrayList<>();
        String flags = flags(schema, field);
        lines.add(labelled("Flags", flags + "  " + flagWords(flags)));
        if (schema.isPresent()) {
            IndexField index = schema.get();
            List<String> indexed = new ArrayList<>();
            indexed.add(
                    index.indexOptions() == IndexOptions.NONE
                            ? "not indexed"
                            : Overview.postings(index.indexOptions()));
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
        }
        lines.add(labelled(
                "Norm", field.norm().isPresent() ? normText(field.norm().getAsLong()) : "-"));
        matchesLine.ifPresent(text -> lines.add(labelled("Matches", text)));
        if (hit.isPresent()) {
            lines.add(Line.empty());
            lines.addAll(hitLines(hit.get(), positions));
        }

        lines.add(Line.empty());
        if (field.stored().isEmpty()) {
            lines.add(labelled("Stored", "-"));
        } else {
            lines.add(labelled("Stored", TextFormat.count(field.stored().size(), "value")));
            Style dim = Style.EMPTY.dim();
            for (int v = 0; v < field.stored().size(); v++) {
                StoredValue value = field.stored().get(v);
                // Multi-line text keeps its lines, indented under the first; a long value shows its
                // beginning, and the viewer the rest.
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
                    String prefix = i == 0
                            ? String.format(Locale.ROOT, "  %-8s", Viewer.storedType(value.type()))
                            : " ".repeat(10);
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
            }
        }

        lines.add(Line.empty());
        if (field.docValues().isEmpty()) {
            lines.add(labelled("Doc values", "-"));
        } else {
            DocValues values = field.docValues().get();
            String heading = Overview.docValues(values.type());
            if (!values.numbers().isEmpty()) {
                heading += ", as " + numbers.label() + " (x)";
            }
            lines.add(labelled("Doc values", heading));
            for (String text : docValueTexts(values, numbers)) {
                lines.add(Line.from("  " + text));
            }
        }

        if (field.vector().isPresent()
                || schema.filter(index -> index.vectorDimension() > 0).isPresent()) {
            lines.add(Line.empty());
            if (field.vector().isEmpty()) {
                lines.add(labelled("Vector", "-"));
            } else {
                KnnVector vector = field.vector().get();
                lines.add(labelled(
                        "Vector",
                        vector.dimension() + "d " + Overview.vectorEncoding(vector.encoding()) + ", length "
                                + String.format(Locale.ROOT, "%.6g", vectorLength(vector)) + ", N finds the nearest"));
                lines.addAll(vectorLines(vector));
            }
        }

        lines.add(Line.empty());
        if (!field.hasTermVector()) {
            lines.add(labelled("Term vector", "-"));
        } else {
            switch (termVector) {
                case null ->
                    lines.add(labelled(
                            "Term vector", features.contains(Feature.TERM_VECTORS) ? "⏎ loads it" : "not read"));
                case TermVector.Failed failed ->
                    lines.add(labelled("Term vector", "cannot read it: " + failed.error()));
                case TermVector.Loaded loaded -> {
                    lines.add(labelled(
                            "Term vector", TextFormat.count(loaded.terms().size(), "term")));
                    lines.addAll(termLines(loaded.terms(), true));
                }
            }
        }

        if (features.contains(Feature.RECONSTRUCT)
                && schema.filter(Documents::rebuildable).isPresent()) {
            lines.add(Line.empty());
            lines.addAll(reconstructionLines(reconstruction, schema.get(), pointFormats));
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
            String what = terms && points ? "the terms and points" : points ? "the points" : "the terms";
            return List.of(labelled("Rebuilt", "r rebuilds " + what + " it was indexed with"));
        }
        List<Line> lines = new ArrayList<>();
        PointFormat format = pointFormats.of(field);
        String summary = reconstruction.summary(terms, points);
        boolean onlyPoints = points && !terms && reconstruction instanceof Reconstruction.Done;
        lines.add(labelled("Rebuilt", onlyPoints ? summary + ", as " + format.label() + " (t)" : summary));
        if (reconstruction instanceof Reconstruction.Done done) {
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
                            TextFormat.count(done.field().points().size(), "point") + ", as " + format.label()
                                    + " (t)"));
                }
                for (PointValue point : done.field().points()) {
                    lines.add(Line.from("  " + format.format(point)));
                }
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
                spans.add(Span.styled(" ×" + term.freq(), Style.EMPTY.dim()));
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
            heading.add(Span.styled(" ×" + hit.freq().getAsInt(), Style.EMPTY.dim()));
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

    /** The norm, and the field length it stands for if Lucene's similarities wrote it. */
    static String normText(long norm) {
        return normLength(norm)
                .map(length -> length.low() == length.high()
                        ? norm + " → length " + length.low()
                        : norm + " → length " + length.low() + "–" + length.high())
                .orElse(Long.toString(norm));
    }

    /** The field lengths a norm stands for, from {@code low} to {@code high}. */
    record NormLength(long low, long high) {}

    /**
     * The field lengths a norm stands for if Lucene's similarities wrote it: they all encode the
     * number of terms with {@code SmallFloat.intToByte4}, which is exact below 40 and a range above.
     * Empty for a norm that is not a byte, which some other similarity wrote.
     */
    static Optional<NormLength> normLength(long norm) {
        if (norm < Byte.MIN_VALUE || norm > Byte.MAX_VALUE) {
            return Optional.empty();
        }
        int b = (int) norm & 0xff;
        long low = byte4ToInt(b);
        long high = b < 0xff ? byte4ToInt(b + 1) - 1 : low;
        return Optional.of(new NormLength(low, high));
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
        long int4 = b - numFreeValues;
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
        frame.renderWidget(Clear.INSTANCE, popup);
        frame.renderWidget(
                Paragraph.builder()
                        .text(Text.from(Line.from(prompt.input + "▏"), Line.styled(message, Style.EMPTY.dim())))
                        .block(Block.builder()
                                .borders(Borders.ALL)
                                .title(" Go to document ")
                                .build())
                        .build(),
                popup);
    }
}
