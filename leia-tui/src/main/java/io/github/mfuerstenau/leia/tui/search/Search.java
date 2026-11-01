package io.github.mfuerstenau.leia.tui.search;

import static io.github.mfuerstenau.leia.tui.widgets.Keys.Group.DO;
import static io.github.mfuerstenau.leia.tui.widgets.Keys.Group.FIND;
import static io.github.mfuerstenau.leia.tui.widgets.Keys.Group.SHOW;
import static io.github.mfuerstenau.leia.tui.widgets.Keys.Group.SORT;
import static io.github.mfuerstenau.leia.tui.widgets.Panels.highlight;
import static io.github.mfuerstenau.leia.tui.widgets.Panels.panel;
import static io.github.mfuerstenau.leia.tui.widgets.Panels.right;
import static io.github.mfuerstenau.leia.tui.widgets.Panels.tailRow;

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
import dev.tamboui.widgets.paragraph.Paragraph;
import dev.tamboui.widgets.table.Cell;
import dev.tamboui.widgets.table.Row;
import dev.tamboui.widgets.table.Table;
import dev.tamboui.widgets.table.TableState;
import io.github.mfuerstenau.leia.protocol.document.BytesValue;
import io.github.mfuerstenau.leia.protocol.document.StoredValue;
import io.github.mfuerstenau.leia.protocol.filter.FilterPattern;
import io.github.mfuerstenau.leia.protocol.filter.StartFilterParams;
import io.github.mfuerstenau.leia.protocol.index.DocValuesType;
import io.github.mfuerstenau.leia.protocol.index.Feature;
import io.github.mfuerstenau.leia.protocol.index.IndexField;
import io.github.mfuerstenau.leia.protocol.index.IndexOptions;
import io.github.mfuerstenau.leia.protocol.index.VectorEncoding;
import io.github.mfuerstenau.leia.protocol.knn.GetKnnRecallResult;
import io.github.mfuerstenau.leia.protocol.knn.KnnRecallTarget;
import io.github.mfuerstenau.leia.protocol.knn.StartKnnRecallParams;
import io.github.mfuerstenau.leia.protocol.search.ClassicQuery;
import io.github.mfuerstenau.leia.protocol.search.DocValuesNumber;
import io.github.mfuerstenau.leia.protocol.search.DocValuesQuery;
import io.github.mfuerstenau.leia.protocol.search.DocumentHighlight;
import io.github.mfuerstenau.leia.protocol.search.Explanation;
import io.github.mfuerstenau.leia.protocol.search.FieldMatches;
import io.github.mfuerstenau.leia.protocol.search.GetExplainResult;
import io.github.mfuerstenau.leia.protocol.search.GetHighlightResult;
import io.github.mfuerstenau.leia.protocol.search.GetSearchResult;
import io.github.mfuerstenau.leia.protocol.search.GetStoredScanResult;
import io.github.mfuerstenau.leia.protocol.search.HighlightOptions;
import io.github.mfuerstenau.leia.protocol.search.HighlightPassage;
import io.github.mfuerstenau.leia.protocol.search.Hit;
import io.github.mfuerstenau.leia.protocol.search.HitField;
import io.github.mfuerstenau.leia.protocol.search.HitHighlight;
import io.github.mfuerstenau.leia.protocol.search.KnnQuery;
import io.github.mfuerstenau.leia.protocol.search.LikeTerm;
import io.github.mfuerstenau.leia.protocol.search.MatchSpan;
import io.github.mfuerstenau.leia.protocol.search.MoreLikeThisQuery;
import io.github.mfuerstenau.leia.protocol.search.OffsetSource;
import io.github.mfuerstenau.leia.protocol.search.QueryOperator;
import io.github.mfuerstenau.leia.protocol.search.SearchQuery;
import io.github.mfuerstenau.leia.protocol.search.SearchResults;
import io.github.mfuerstenau.leia.protocol.search.SortValue;
import io.github.mfuerstenau.leia.protocol.search.StandardQuery;
import io.github.mfuerstenau.leia.protocol.search.StartExplainParams;
import io.github.mfuerstenau.leia.protocol.search.StartHighlightParams;
import io.github.mfuerstenau.leia.protocol.search.StartSearchParams;
import io.github.mfuerstenau.leia.protocol.search.StartStoredScanParams;
import io.github.mfuerstenau.leia.protocol.search.StoredScanHit;
import io.github.mfuerstenau.leia.protocol.search.TermQuery;
import io.github.mfuerstenau.leia.protocol.search.TotalHitsRelation;
import io.github.mfuerstenau.leia.protocol.search.ValueHighlight;
import io.github.mfuerstenau.leia.tui.analysis.AnalysisSettings;
import io.github.mfuerstenau.leia.tui.documents.Documents;
import io.github.mfuerstenau.leia.tui.overview.Overview;
import io.github.mfuerstenau.leia.tui.prompt.LineInput;
import io.github.mfuerstenau.leia.tui.search.LikeSettings.LikeSource;
import io.github.mfuerstenau.leia.tui.text.TextFormat;
import io.github.mfuerstenau.leia.tui.theme.Palette;
import io.github.mfuerstenau.leia.tui.widgets.Form;
import io.github.mfuerstenau.leia.tui.widgets.GoMenu;
import io.github.mfuerstenau.leia.tui.widgets.Jump;
import io.github.mfuerstenau.leia.tui.widgets.Keys;
import io.github.mfuerstenau.leia.tui.widgets.Panels;
import io.github.mfuerstenau.leia.tui.widgets.Selection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The search screen (ADR 0010 to ADR 0014): a query line, the hits of the last query, and a details
 * panel with the parsed query or the explanation of a hit. Searches and explanations run as backend
 * jobs; the next page of hits is fetched once the selection reaches the last row. Enter on a hit
 * shows its document on the documents screen, where {@code [}/{@code ]} step through the hits.
 * Popups set the query parser options (point types included), the More-Like-This settings, the
 * similarity and the sort, and the stored fields per hit. The parsers and More-Like-This analyze with
 * the analysis screen's analyzer. A fifth mode scans the stored values with a regular expression
 * ({@link StoredScan}, ADR 0027).
 *
 * <p>The Rust TUI got each reply before the next key; here a reply can come after the user moved on.
 * So each search, explanation and scan has an id, which its start, its polls and its replies carry: a
 * reply for one that was replaced or stopped is dropped, and a job that starts for one is cancelled.
 */
// A screen that wires its own forms and popups (ADR 0088)
@SuppressWarnings("java:S6539")
public final class Search {

    private static final String CANCELLED = "Cancelled.";

    /** How many hits one {@code startSearch} call fetches. */
    public static final int SEARCH_PAGE = 100;

    /** Rows that PageUp and PageDown scroll the details. */
    private static final int PAGE = 10;

    /** The widest the sort values of a hit get in the hit list. */
    private static final int MAX_SORT_WIDTH = 32;

    /** The most passages per field that a hit shows (ADR 0042). */
    static final int HIGHLIGHT_PASSAGES = 3;

    /** The k of a KNN query until another is typed. */
    static final int DEFAULT_K = 10;

    /** Marks the source document among the hits of a More-Like-This query. */
    private static final String SOURCE_MARK = "◆";

    private static final int COLUMN_SPACING = 1;

    /** What the caller has to start, stop or show for the search screen. */
    public sealed interface Request {

        /**
         * Cancel the jobs in {@code cancel}, then start search {@code searchId}, reported back with
         * {@link #searchStarted} or {@link #searchFailed}; its job with {@link #searchProgress}.
         */
        record Start(long searchId, StartSearchParams params, List<Long> cancel) implements Request {}

        /**
         * Cancel the jobs in {@code cancel}, then start explanation {@code explainId}, reported back
         * with {@link #explainStarted} or {@link #explainFailed}; its job with {@link
         * #explainProgress}.
         */
        record Explain(long explainId, StartExplainParams params, List<Long> cancel) implements Request {}

        /**
         * Cancel the jobs in {@code cancel}, then start scan {@code scanId} of the stored values,
         * reported back with {@link #storedScanStarted} or {@link #storedScanFailed}; its job with
         * {@link #storedScanProgress}.
         */
        record StoredScan(long scanId, StartStoredScanParams params, List<Long> cancel) implements Request {}

        /**
         * Cancel the jobs in {@code cancel}, then start recall check {@code recallId} of the KNN mode
         * (ADR 0086), reported back with {@link #knnRecallStarted} or {@link #knnRecallFailed}; its job
         * with {@link #knnRecallProgress}.
         */
        record Recall(long recallId, StartKnnRecallParams params, List<Long> cancel) implements Request {}

        /** Cancel these jobs; the screen has forgotten them already, so nothing is reported back. */
        record Cancel(List<Long> jobs) implements Request {}

        /** Show this document on the documents screen. */
        record Document(int docId) implements Request {}

        /** Browse the documents screen through the documents that match (ADR 0031), from {@code startDoc} on. */
        record Filter(StartFilterParams params, String label, int startDoc) implements Request {}

        /**
         * Cancel the jobs in {@code cancel}, then start highlight {@code highlightId} (ADR 0042),
         * reported back with {@link #highlightStarted} or {@link #highlightFailed}; its job with
         * {@link #highlightProgress}, which asks to view the value once its matches are in.
         */
        record Highlight(long highlightId, StartHighlightParams params, List<Long> cancel) implements Request {}

        /** Show this stored value in the viewer of the documents screen, with its matches highlighted. */
        record View(int docId, String field, int index, List<MatchSpan> spans, int matchCount) implements Request {}

        /** A key that the index lacks a feature for (ADR 0044); {@code what} names it. Nothing changed. */
        record Unsupported(String what) implements Request {}
    }

    /** How the query line is read. */
    enum Mode {
        /** Lucene's classic query syntax. */
        CLASSIC,
        /** Lucene's standard query syntax, which knows point fields. */
        STANDARD,
        /** One term of the selected field, exactly as typed. */
        TERM,
        /** Documents like the document whose id the line holds (More-Like-This). */
        LIKE,
        /** Documents like the text the line holds (More-Like-This, ADR 0028). */
        LIKE_TEXT,
        /** A regular expression over the stored values of every document (ADR 0027). */
        REGEX,
        /** Values of the selected field's doc values: one, several or a range (ADR 0033). */
        DOC_VALUES,
        /** The documents whose vectors are nearest to a document's or a typed vector (ADR 0040). */
        KNN
    }

    /** The line that the keys edit while {@link #editing}. */
    private enum EditLine {
        /** The query line. */
        QUERY,
        /** The filter line of the KNN mode. */
        FILTER,
        /** The k of the KNN mode. */
        K
    }

    private enum Focus {
        HITS,
        DETAILS
    }

    /** Which fields of the hits the query's matches are highlighted in (ADR 0042). */
    enum Highlights {
        /** The stored fields per hit, each with the query's terms in that field. */
        QUERY_FIELDS("terms of the field"),
        /** The stored fields per hit, each with the query's terms in any field. */
        ANY_FIELD("terms of any field"),
        /** None: the hits show their stored fields. */
        OFF("off");

        final String text;

        Highlights(String text) {
            this.text = text;
        }
    }

    /** The search for every match in the value that {@code v} views (ADR 0042). */
    private static final class Highlighting {
        final long id;
        final StartHighlightParams params;

        /** The value to view: that of the hit's first passage. */
        final int valueIndex;

        /** Null until the backend has started the job. */
        Long jobId;

        /** Why the matches could not be found; null while the job runs. */
        String error;

        Highlighting(long id, StartHighlightParams params, int valueIndex) {
            this.id = id;
            this.params = params;
            this.valueIndex = valueIndex;
        }
    }

    /** The hits of a query, as far as they are fetched. */
    private static final class HitList {
        final String query;
        final String rewrittenQuery;
        long totalHits;
        TotalHitsRelation relation;
        final long tookMillis;
        final List<Hit> hits;

        /** The terms a More-Like-This query chose, best first. */
        final Optional<List<LikeTerm>> moreLikeThisTerms;

        /** Whether more hits may follow: the last page was full. */
        boolean more;

        /** Why the last page could not be fetched; no more pages are fetched then. Null otherwise. */
        String error;

        HitList(SearchResults results, boolean more) {
            this.query = results.query();
            this.rewrittenQuery = results.rewrittenQuery();
            this.totalHits = results.totalHits();
            this.relation = results.totalHitsRelation();
            this.tookMillis = results.tookMillis();
            this.hits = new ArrayList<>(results.hits());
            this.moreLikeThisTerms = results.moreLikeThisTerms();
            this.more = more;
        }

        /** The total hit count: exact, or a lower bound. */
        String total() {
            if (relation == TotalHitsRelation.GREATER_THAN_OR_EQUAL_TO) {
                return "at least " + totalHits + " hits";
            }
            return totalHits == 1 ? "1 hit" : totalHits + " hits";
        }
    }

    /** A submitted search and what came of it: null until the first page is in, then the hits or the error. */
    private static final class Submitted {
        /** The first page's params; later pages differ only in their offset and limit. */
        final StartSearchParams params;

        HitList hits;
        String error;

        Submitted(StartSearchParams params) {
            this.params = params;
        }

        boolean done() {
            return hits != null || error != null;
        }
    }

    /**
     * The search job that runs in the backend.
     *
     * @param jobId null until the backend has started the job
     * @param more whether the page continues the shown hits
     */
    private record Running(long id, Long jobId, boolean more, int limit, long docsSearched) {}

    /** The explanation of one hit for one query and similarity. */
    private static final class Explain {
        final long id;
        final StartExplainParams params;

        /** Null until the backend has started the job. */
        Long jobId;

        /** Null while the job runs. */
        Explanation explanation;

        String error;

        Explain(long id, StartExplainParams params) {
            this.id = id;
            this.params = params;
        }

        boolean running() {
            return explanation == null && error == null;
        }
    }

    /** The fields with postings, in field number order. */
    private final List<String> fields;

    private final int maxDoc;
    private Mode mode = Mode.CLASSIC;

    /** The default field of a classic query, or the field of a term query. */
    private int field;

    private QueryOperator operator = QueryOperator.OR;
    private ParserSettings parser;
    private LikeSettings like;
    private ScoringSettings scoring;
    private HitFields hitFields;

    /** Analyzes classic and standard queries and More-Like-This text. */
    private AnalysisSettings analyzer = new AnalysisSettings();

    /** The popup that takes the keys, while it is open: one of the four forms, or null. */
    private Object form;

    private boolean exact;

    private Highlights highlights = Highlights.QUERY_FIELDS;

    /** The highlight that runs for {@code v}, or the one that failed; null otherwise. */
    private Highlighting highlighting;

    /** Whether the regex scan ignores case. */
    private boolean caseInsensitive;

    /** Whether the regex scan reads deleted documents too. */
    private boolean includeDeleted;

    /** How many documents {@code r} on a blank KNN line samples (ADR 0086). */
    static final int RECALL_SAMPLE = 100;

    /** The last recall check of the KNN mode, until the next search; null otherwise (ADR 0086). */
    private KnnRecall recall;

    private long recallId;

    /** The last regex scan, while the mode is {@link Mode#REGEX}; null otherwise. */
    private StoredScan scan;

    private long scanId;
    private LineInput input = new LineInput("");

    /**
     * Whether the input holds a binary term in hex, as the overview's top terms bring it. An edit
     * makes it text again.
     */
    private boolean binary;

    /** The fields with doc values and their types, in field number order (ADR 0033). */
    private final List<IndexField> docValuesFields;

    /** The field of a doc values query. */
    private int docValuesField;

    /** How the selected number field encodes its numbers. */
    private DocValuesNumber number = DocValuesNumber.LONG;

    /** Whether the values of a doc values query on bytes are hex; edits keep it. */
    private boolean hex;

    /** The fields with KNN vectors, in field number order (ADR 0040). */
    private final List<IndexField> vectorFields;

    /** The field of a KNN query. */
    private int vectorField;

    /** How many nearest documents an approximate KNN query finds. */
    private int k = DEFAULT_K;

    /** Whether a KNN query scores every document with a vector instead of searching the graph. */
    private boolean knnExact;

    /** The filter of a KNN query, in classic syntax with the classic mode's default field; blank for none. */
    private LineInput knnFilter = new LineInput("");

    /** The k being typed. */
    private LineInput kInput = new LineInput("");

    private EditLine editLine = EditLine.QUERY;

    /** Why the doc values or KNN line could not be read, until the next search; or null. */
    private String lineError;

    private boolean editing;
    private Focus focus = Focus.HITS;
    private Submitted submitted;
    private Running running;
    private Explain explain;

    /** Whether the documents screen waits for the next page to step to its first hit. */
    private boolean stepPending;

    /** The selected hit; -1 for none. */
    private int selected = -1;

    private final TableState table = new TableState();

    /** The first line of the details panel that is shown. */
    private int detailsScroll;

    /** The id of the next search, explanation or scan. */
    private long nextId = 1;

    /**
     * What the index can do (ADR 0044): the modes and keys it lacks a feature for are refused and
     * left out of the legend.
     */
    private Set<Feature> features = Feature.all();

    /** The default field is the first one with positions, which is most likely text. */
    public Search(List<IndexField> indexFields, int maxDoc) {
        List<IndexField> searchable = indexFields.stream()
                .filter(f -> f.indexOptions() != IndexOptions.NONE)
                .toList();
        this.fields = searchable.stream().map(IndexField::name).toList();
        for (int i = 0; i < searchable.size(); i++) {
            IndexOptions options = searchable.get(i).indexOptions();
            if (options == IndexOptions.DOCS_AND_FREQS_AND_POSITIONS
                    || options == IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS) {
                field = i;
                break;
            }
        }
        this.maxDoc = maxDoc;
        this.parser = ParserSettings.of(indexFields);
        this.like = LikeSettings.of(indexFields);
        this.scoring = ScoringSettings.of(indexFields);
        this.hitFields = HitFields.of(indexFields);
        this.docValuesFields = indexFields.stream()
                .filter(f -> f.docValuesType() != DocValuesType.NONE)
                .toList();
        this.vectorFields =
                indexFields.stream().filter(f -> f.vectorDimension() > 0).toList();
    }

    /**
     * The screen for the reopened index (ADR 0019): the same query line, mode, field, operator, exact
     * count and analyzer, without hits; a search is not run again by itself. The parser,
     * More-Like-This, scoring and hit field settings are kept if {@code sameFields}, otherwise they
     * start over for the new fields.
     */
    public Search reopened(List<IndexField> indexFields, int maxDoc, boolean sameFields) {
        Search search = new Search(indexFields, maxDoc);
        if (field < fields.size()) {
            int row = search.fields.indexOf(fields.get(field));
            if (row >= 0) {
                search.field = row;
            }
        }
        docValuesField().ifPresent(old -> {
            for (int i = 0; i < search.docValuesFields.size(); i++) {
                if (search.docValuesFields.get(i).name().equals(old.name())) {
                    search.docValuesField = i;
                }
            }
        });
        vectorField().ifPresent(old -> {
            for (int i = 0; i < search.vectorFields.size(); i++) {
                if (search.vectorFields.get(i).name().equals(old.name())) {
                    search.vectorField = i;
                }
            }
        });
        search.k = k;
        search.knnExact = knnExact;
        search.knnFilter = new LineInput(knnFilter.text());
        search.number = number;
        search.hex = hex;
        search.mode = mode;
        search.operator = operator;
        search.exact = exact;
        search.highlights = highlights;
        search.caseInsensitive = caseInsensitive;
        search.includeDeleted = includeDeleted;
        search.input = new LineInput(input.text());
        search.binary = binary;
        search.analyzer = analyzer;
        search.nextId = nextId;
        search.features = features;
        if (sameFields) {
            search.parser = parser;
            search.like = like;
            search.scoring = scoring;
            search.hitFields = hitFields;
        }
        return search;
    }

    /**
     * Offers only {@code stored} as the fields per hit, those that a scan found documents store values
     * in (ADR 0113), each shown as it was.
     */
    public void storedFieldsKnown(List<String> stored) {
        hitFields = hitFields.storedOnly(stored);
    }

    /**
     * Offers only the modes and keys that {@code features} allow (ADR 0044): without {@link
     * Feature#HIGHLIGHT}, the hits aren't highlighted, and the mode is the first one left if its own
     * is gone.
     */
    public Search withFeatures(Set<Feature> features) {
        this.features = Set.copyOf(features);
        if (!features.contains(Feature.HIGHLIGHT)) {
            highlights = Highlights.OFF;
        }
        List<Mode> modes = modes();
        if (!modes.isEmpty() && !modes.contains(mode)) {
            mode = modes.getFirst();
        }
        return this;
    }

    private boolean supports(Feature... needed) {
        return features.containsAll(List.of(needed));
    }

    /** Whether the similarity and sort apply (ADR 0064). */
    private boolean scores() {
        return supports(Feature.SCORING);
    }

    /** The modes the index has the features for, in the order {@code m} cycles through them. */
    private List<Mode> modes() {
        List<Mode> modes = new ArrayList<>();
        for (Mode each : MODE_CYCLE) {
            boolean available = switch (each) {
                case CLASSIC -> supports(Feature.CLASSIC_SEARCH);
                case TERM -> supports(Feature.TERM_SEARCH);
                case STANDARD -> supports(Feature.STANDARD_SEARCH);
                case LIKE, LIKE_TEXT -> supports(Feature.MORE_LIKE_THIS);
                case DOC_VALUES -> supports(Feature.DOC_VALUES_SEARCH) && supports(Feature.DOC_VALUES);
                // A runtime searches KNN vectors with its classic search (ADR 0049).
                case KNN -> supports(Feature.KNN_VECTORS) && supports(Feature.CLASSIC_SEARCH);
                case REGEX -> supports(Feature.STORED_SCAN);
            };
            if (available) {
                modes.add(each);
            }
        }
        return modes;
    }

    /** The order in which {@code m} cycles through the modes. */
    private static final List<Mode> MODE_CYCLE = List.of(
            Mode.CLASSIC, Mode.STANDARD, Mode.TERM, Mode.DOC_VALUES, Mode.KNN, Mode.LIKE, Mode.LIKE_TEXT, Mode.REGEX);

    /** What {@code key} is called if the index lacks a feature it needs (ADR 0044); empty otherwise. */
    private Optional<String> unsupported(KeyEvent key) {
        boolean regex = mode == Mode.REGEX;
        if (key.code() == KeyCode.ENTER) {
            return need("Open doc", Feature.DOCUMENTS);
        }
        if (key.code() != KeyCode.CHAR) {
            return Optional.empty();
        }
        return switch (key.codePoint()) {
            case 'm' -> modes().size() > 1 ? Optional.empty() : Optional.of("Other modes");
            case 'b', 'B' -> need("Browse", Feature.FILTER);
            case 's', 'S' -> regex || scores() ? Optional.empty() : Optional.of("Similarity and sort");
            case 'x' -> regex ? Optional.empty() : need("Explain", Feature.EXPLAIN);
            case 'H' -> regex ? Optional.empty() : need("Highlight", Feature.HIGHLIGHT);
            case 'v' -> regex ? Optional.empty() : need("View", Feature.HIGHLIGHT);
            default -> Optional.empty();
        };
    }

    private Optional<String> need(String what, Feature feature) {
        return supports(feature) ? Optional.empty() : Optional.of(what);
    }

    /** From now on, queries are analyzed with {@code settings}; the shown hits stay as they are. */
    public void setAnalyzer(AnalysisSettings settings) {
        this.analyzer = settings;
    }

    /** Whether the query line or a popup takes the keys, Esc included. */
    public boolean prompting() {
        return editing || form != null;
    }

    /** The search job to poll, while one runs: its search id and job id. */
    public Optional<long[]> searchJob() {
        return running != null && running.jobId() != null
                ? Optional.of(new long[] {running.id(), running.jobId()})
                : Optional.empty();
    }

    /** The explanation job to poll, while one runs: its explanation id and job id. */
    public Optional<long[]> explainJob() {
        return explain != null && explain.running() && explain.jobId != null
                ? Optional.of(new long[] {explain.id, explain.jobId})
                : Optional.empty();
    }

    /** The regex scan job to poll, while one runs: its scan id and job id. */
    public Optional<long[]> storedScanJob() {
        if (scan == null || scan.job().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new long[] {scanId, scan.job().getAsLong()});
    }

    /** The first hit the next poll of the regex scan asks for. */
    public int storedScanFromHit() {
        return scan == null ? 0 : scan.fromHit();
    }

    /** The recall check job to poll, while one runs: its check id and job id (ADR 0086). */
    public Optional<long[]> knnRecallJob() {
        if (recall == null || recall.job().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new long[] {recallId, recall.job().getAsLong()});
    }

    /** The jobs that have started and still run. */
    List<Long> jobs() {
        List<Long> jobs = new ArrayList<>();
        searchJob().ifPresent(job -> jobs.add(job[1]));
        knnRecallJob().ifPresent(job -> jobs.add(job[1]));
        explainJob().ifPresent(job -> jobs.add(job[1]));
        storedScanJob().ifPresent(job -> jobs.add(job[1]));
        highlightJob().ifPresent(job -> jobs.add(job[1]));
        return jobs;
    }

    /** The highlight job to poll, while one runs: its highlight id and job id. */
    public Optional<long[]> highlightJob() {
        return highlighting != null && highlighting.error == null && highlighting.jobId != null
                ? Optional.of(new long[] {highlighting.id, highlighting.jobId})
                : Optional.empty();
    }

    /** Whether a search, explanation, scan or highlight runs, started or not: what Esc stops. */
    public boolean running() {
        return running != null
                || (explain != null && explain.running())
                || (scan != null && scan.running())
                || (recall != null && recall.running())
                || (highlighting != null && highlighting.error == null);
    }

    /**
     * Stops what runs: a first page that was not in yet becomes an error, a regex scan keeps the hits
     * it has. Returns the jobs to cancel; one that hasn't started yet is cancelled when it starts.
     */
    public List<Long> cancel() {
        List<Long> jobs = jobs();
        if (scan != null) {
            scan.cancelled();
        }
        if (recall != null) {
            recall.cancelled();
        }
        if (running != null) {
            fail(running, CANCELLED);
        }
        if (explain != null && explain.running()) {
            explain.error = CANCELLED;
        }
        if (highlighting != null && highlighting.error == null) {
            highlighting.error = CANCELLED;
        }
        return jobs;
    }

    /**
     * What a jump leaves the screen at, for Backspace to show again (ADR 0054): the query line, the
     * submitted search with its hits, the selection, the explanation, the highlight and the regex
     * scan, which a jump into the screen or the next search replaces. The settings are not marked.
     */
    public static final class Mark {
        private final Mode mode;
        private final int field;
        private final String input;
        private final boolean binary;
        private final int docValuesField;
        private final DocValuesNumber number;
        private final boolean hex;
        private final int vectorField;
        private final String lineError;
        private final Submitted submitted;
        private final Running running;
        private final int selected;
        private final int detailsScroll;
        private final Focus focus;
        private final Explain explain;
        private final Highlighting highlighting;
        private final StoredScan scan;
        private final long scanId;
        private final KnnRecall recall;
        private final long recallId;

        private Mark(Search of) {
            this.mode = of.mode;
            this.field = of.field;
            this.input = of.input.text();
            this.binary = of.binary;
            this.docValuesField = of.docValuesField;
            this.number = of.number;
            this.hex = of.hex;
            this.vectorField = of.vectorField;
            this.lineError = of.lineError;
            this.submitted = of.submitted;
            this.running = of.running;
            this.selected = of.selected;
            this.detailsScroll = of.detailsScroll;
            this.focus = of.focus;
            this.explain = of.explain;
            this.highlighting = of.highlighting;
            this.scan = of.scan;
            this.scanId = of.scanId;
            this.recall = of.recall;
            this.recallId = of.recallId;
        }
    }

    /** Where the screen is, for {@link #restore} to come back to (ADR 0054). */
    public Mark mark() {
        return new Mark(this);
    }

    /**
     * Shows the screen as {@code mark} left it (ADR 0054). If it still shows that search, nothing
     * changes; otherwise returns the jobs of the newer one to cancel, if any run. What the mark left
     * running was stopped by the newer search, and shows so, as Esc would have.
     */
    public Optional<Request.Cancel> restore(Mark mark) {
        if (submitted == mark.submitted && scan == mark.scan && recall == mark.recall && mode == mark.mode) {
            return Optional.empty();
        }
        List<Long> cancel = jobs();
        mode = mark.mode;
        field = mark.field;
        input.set(mark.input);
        binary = mark.binary;
        docValuesField = mark.docValuesField;
        number = mark.number;
        hex = mark.hex;
        vectorField = mark.vectorField;
        lineError = mark.lineError;
        submitted = mark.submitted;
        selected = mark.selected;
        detailsScroll = mark.detailsScroll;
        focus = mark.focus;
        explain = mark.explain;
        highlighting = mark.highlighting;
        scan = mark.scan;
        scanId = mark.scanId;
        recall = mark.recall;
        recallId = mark.recallId;
        editing = false;
        running = null;
        stepPending = false;
        // A next page that never came is fetched again on scroll; a first page shows as cancelled.
        if (mark.running != null && !mark.running.more()) {
            fail(mark.running, CANCELLED);
        }
        if (explain != null && explain.running()) {
            explain.error = CANCELLED;
        }
        if (highlighting != null && highlighting.error == null) {
            highlighting.error = CANCELLED;
        }
        if (scan != null) {
            scan.cancelled();
        }
        if (recall != null) {
            recall.cancelled();
        }
        return cancel.isEmpty() ? Optional.empty() : Optional.of(new Request.Cancel(cancel));
    }

    /** Searches for exactly {@code term} in {@code fieldName}, as the overview's top terms ask for. */
    public Optional<Request> termQuery(String fieldName, BytesValue term) {
        int row = fields.indexOf(fieldName);
        if (row < 0) {
            return Optional.empty();
        }
        field = row;
        mode = Mode.TERM;
        input.set(term.value());
        binary = term.binary();
        editing = false;
        focus = Focus.HITS;
        return submit();
    }

    /** Searches for documents like {@code docId}, as the documents screen asks for. */
    public Optional<Request> moreLikeThis(int docId) {
        mode = Mode.LIKE;
        input.set(Integer.toString(docId));
        binary = false;
        editing = false;
        focus = Focus.HITS;
        return submit();
    }

    /**
     * Searches {@code fieldName}'s doc values for {@code values} (ADR 0033), as the documents screen
     * asks for: numbers in {@code docValuesNumber}, bytes in hex if {@code inHex}.
     */
    public Optional<Request> docValuesQuery(
            String fieldName, DocValuesNumber docValuesNumber, boolean inHex, List<String> values) {
        int row = -1;
        for (int i = 0; i < docValuesFields.size(); i++) {
            if (docValuesFields.get(i).name().equals(fieldName)) {
                row = i;
                break;
            }
        }
        if (row < 0) {
            return Optional.empty();
        }
        docValuesField = row;
        mode = Mode.DOC_VALUES;
        if (numberField()) {
            number = docValuesNumber;
        } else {
            hex = inHex;
        }
        input.set(values.stream().map(DocValuesSyntax::quote).collect(Collectors.joining(" ")));
        binary = false;
        editing = false;
        focus = Focus.HITS;
        return submit();
    }

    /**
     * Searches for the documents whose vectors in {@code fieldName} are nearest to document {@code
     * docId}'s (ADR 0040), as the documents screen asks for, with the k, exactness and filter set
     * before.
     */
    public Optional<Request> knnQuery(String fieldName, int docId) {
        int row = -1;
        for (int i = 0; i < vectorFields.size(); i++) {
            if (vectorFields.get(i).name().equals(fieldName)) {
                row = i;
                break;
            }
        }
        if (row < 0) {
            return Optional.empty();
        }
        vectorField = row;
        mode = Mode.KNN;
        input.set(Integer.toString(docId));
        binary = false;
        editing = false;
        focus = Focus.HITS;
        return submit();
    }

    /** The vector field of the KNN mode. */
    private Optional<IndexField> vectorField() {
        return vectorField < vectorFields.size() ? Optional.of(vectorFields.get(vectorField)) : Optional.empty();
    }

    /** The doc values field of the doc values mode. */
    private Optional<IndexField> docValuesField() {
        return docValuesField < docValuesFields.size()
                ? Optional.of(docValuesFields.get(docValuesField))
                : Optional.empty();
    }

    /** Whether the doc values field holds numbers, which need {@link #number}. */
    private boolean numberField() {
        return docValuesField()
                .map(f ->
                        f.docValuesType() == DocValuesType.NUMERIC || f.docValuesType() == DocValuesType.SORTED_NUMERIC)
                .orElse(false);
    }

    /** Whether the mode has a field to search. */
    private boolean hasFields() {
        return switch (mode) {
            case DOC_VALUES -> !docValuesFields.isEmpty();
            case KNN -> !vectorFields.isEmpty();
            case REGEX -> true;
            default -> !fields.isEmpty();
        };
    }

    private static SearchQuery query(
            ClassicQuery classic,
            StandardQuery standard,
            TermQuery term,
            MoreLikeThisQuery moreLikeThis,
            DocValuesQuery docValues) {
        return new SearchQuery(
                Optional.ofNullable(classic),
                Optional.ofNullable(standard),
                Optional.ofNullable(term),
                Optional.ofNullable(moreLikeThis),
                Optional.ofNullable(docValues),
                Optional.empty());
    }

    /** A classic query of {@code text}, with the classic mode's operator, options and analyzer. */
    private ClassicQuery classic(String text, String defaultField) {
        return new ClassicQuery(
                text,
                defaultField,
                operator,
                parser.options(),
                parser.fuzzyMaxEdits(),
                parser.splitOnWhitespace(),
                parser.autoGeneratePhraseQueries(),
                analyzer.analyzer());
    }

    /**
     * The KNN query of the line and the filter line (ADR 0040); empty while the line is blank.
     *
     * @throws IllegalArgumentException if the line doesn't read, with the message to show
     */
    private Optional<SearchQuery> knnLine() {
        Optional<IndexField> vectors = vectorField();
        if (vectors.isEmpty() || input.text().isBlank()) {
            return Optional.empty();
        }
        KnnSyntax.Target target =
                KnnSyntax.target(input.text(), vectors.get().vectorEncoding().orElse(VectorEncoding.FLOAT32));
        Optional<ClassicQuery> filter = knnFilter.text().isBlank() || field >= fields.size()
                ? Optional.empty()
                : Optional.of(classic(knnFilter.text(), fields.get(field)));
        KnnQuery knn = new KnnQuery(
                vectors.get().name(),
                target instanceof KnnSyntax.Target.Doc doc ? OptionalInt.of(doc.docId()) : OptionalInt.empty(),
                target instanceof KnnSyntax.Target.Vector vector ? Optional.of(vector.vector()) : Optional.empty(),
                knnExact ? OptionalInt.empty() : OptionalInt.of(k),
                knnExact,
                filter);
        return Optional.of(new SearchQuery(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(knn)));
    }

    /**
     * The doc values query of the line; empty while the line is blank.
     *
     * @throws IllegalArgumentException if the line doesn't read, with the message to show
     */
    private Optional<SearchQuery> docValuesLine() {
        Optional<IndexField> dvField = docValuesField();
        if (dvField.isEmpty() || input.text().isBlank()) {
            return Optional.empty();
        }
        Optional<DocValuesNumber> encoding = numberField() ? Optional.of(number) : Optional.empty();
        return Optional.of(query(
                null, null, null, null, DocValuesSyntax.query(dvField.get().name(), encoding, hex, input.text())));
    }

    /**
     * The query that the query line and the settings describe; empty while the line is blank, in
     * More-Like-This mode while it is no document id, or in doc values or KNN mode while it doesn't
     * read.
     */
    private Optional<SearchQuery> query() {
        if (mode == Mode.DOC_VALUES || mode == Mode.KNN) {
            try {
                return mode == Mode.KNN ? knnLine() : docValuesLine();
            } catch (IllegalArgumentException _) {
                return Optional.empty();
            }
        }
        if (field >= fields.size()) {
            return Optional.empty();
        }
        String defaultField = fields.get(field);
        String text = input.text();
        if (text.isBlank()) {
            return Optional.empty();
        }
        return switch (mode) {
            case CLASSIC -> Optional.of(query(classic(text, defaultField), null, null, null, null));
            case STANDARD ->
                Optional.of(query(
                        null,
                        new StandardQuery(
                                text,
                                defaultField,
                                operator,
                                parser.options(),
                                parser.pointFields(),
                                analyzer.analyzer()),
                        null,
                        null,
                        null));
            case TERM ->
                Optional.of(query(null, null, new TermQuery(defaultField, new BytesValue(text, binary)), null, null));
            case LIKE -> {
                int docId;
                try {
                    docId = Integer.parseInt(text.strip());
                } catch (NumberFormatException _) {
                    yield Optional.empty();
                }
                yield like.query(new LikeSource.Doc(docId), analyzer.analyzer())
                        .map(q -> query(null, null, null, q, null));
            }
            case LIKE_TEXT ->
                like.query(new LikeSource.Text(text), analyzer.analyzer()).map(q -> query(null, null, null, q, null));
            case REGEX, DOC_VALUES, KNN -> Optional.empty();
        };
    }

    /**
     * Narrows the documents screen to the documents that match the query (ADR 0031), or have a stored
     * value in which the pattern is found (ADR 0032), starting on the selected hit if there is one.
     */
    private Optional<Request> filter(boolean withDeleted) {
        if (mode == Mode.REGEX) {
            return patternFilter(withDeleted);
        }
        Optional<SearchQuery> query = query();
        if (query.isEmpty()) {
            return Optional.empty();
        }
        String text = input.text().strip();
        String label = switch (mode) {
            case TERM -> fields.get(field) + ":" + text;
            case DOC_VALUES -> docValuesField().map(IndexField::name).orElse("") + ":" + text;
            case KNN ->
                "nearest " + vectorField().map(IndexField::name).orElse("") + " " + text
                        + (knnFilter.text().isBlank()
                                ? ""
                                : " where " + knnFilter.text().strip());
            case LIKE -> "like #" + text;
            case LIKE_TEXT -> "like \"" + text + "\"";
            default -> text;
        };
        return Optional.of(new Request.Filter(
                new StartFilterParams(query, Optional.empty(), Optional.empty(), withDeleted),
                label,
                selectedHit().map(Hit::docId).orElse(0)));
    }

    /**
     * The regex over stored values as a filter, with its fields and case setting; empty while the line
     * is empty. The scan's own setting for deleted documents doesn't carry over.
     */
    private Optional<Request> patternFilter(boolean withDeleted) {
        String pattern = input.text();
        if (pattern.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Request.Filter(
                new StartFilterParams(
                        Optional.empty(),
                        Optional.of(new FilterPattern(pattern, caseInsensitive, hitFields.param())),
                        Optional.empty(),
                        withDeleted),
                "/" + pattern + "/" + (caseInsensitive ? "i" : ""),
                scan == null ? 0 : scan.selected().map(StoredScanHit::docId).orElse(0)));
    }

    /** Starts a search for the query line, stopping the jobs of the previous one. */
    private Optional<Request> submit() {
        if (mode == Mode.REGEX) {
            return submitScan();
        }
        lineError = null;
        if (mode == Mode.DOC_VALUES || mode == Mode.KNN) {
            try {
                if (mode == Mode.KNN) {
                    knnLine();
                } else {
                    docValuesLine();
                }
            } catch (IllegalArgumentException e) {
                List<Long> cancel = jobs();
                forgetHits();
                recall = null;
                lineError = e.getMessage();
                return cancel.isEmpty() ? Optional.empty() : Optional.of(new Request.Cancel(cancel));
            }
        }
        Optional<SearchQuery> query = query();
        if (query.isEmpty()) {
            return Optional.empty();
        }
        StartSearchParams params = new StartSearchParams(
                query.get(),
                scoring.similarity(),
                scoring.sortKeys(),
                0,
                SEARCH_PAGE,
                exact,
                hitFields.param(),
                highlightOptions());
        List<Long> cancel = jobs();
        explain = null;
        highlighting = null;
        recall = null;
        stepPending = false;
        submitted = new Submitted(params);
        long id = nextId++;
        running = new Running(id, null, false, SEARCH_PAGE, 0);
        selected = -1;
        detailsScroll = 0;
        return Optional.of(new Request.Start(id, params, cancel));
    }

    /** Starts a regex scan for the query line, stopping the jobs of the previous one; empty while the line is empty. */
    private Optional<Request> submitScan() {
        String pattern = input.text();
        if (pattern.isEmpty()) {
            return Optional.empty();
        }
        StartStoredScanParams params =
                new StartStoredScanParams(pattern, caseInsensitive, hitFields.param(), includeDeleted);
        List<Long> cancel = jobs();
        scan = new StoredScan(params);
        scanId = nextId++;
        detailsScroll = 0;
        return Optional.of(new Request.StoredScan(scanId, params, cancel));
    }

    /** Searches or scans again once a setting changed, if a query was submitted. */
    private Optional<Request> resubmit() {
        if (mode == Mode.REGEX) {
            if (scan == null) {
                return Optional.empty();
            }
        } else if (submitted == null && lineError == null) {
            return Optional.empty();
        }
        return submit();
    }

    /**
     * Searches again in another mode; forgets the hits, and stops their jobs, if the line is no query
     * in that mode, so that they don't pass for its hits. Into or out of the regex mode, nothing runs
     * by itself: a scan reads every document.
     */
    private Optional<Request> switchMode(Mode next) {
        boolean regex = mode == Mode.REGEX || next == Mode.REGEX;
        mode = next;
        if (recall != null) {
            // A recall check belongs to the KNN mode: the next mode doesn't show it.
            List<Long> cancel = jobs();
            recall = null;
            if (submitted == null || query().isEmpty()) {
                forgetHits();
                return cancel.isEmpty() ? Optional.empty() : Optional.of(new Request.Cancel(cancel));
            }
            return submit();
        }
        if (regex) {
            List<Long> cancel = jobs();
            scan = null;
            forgetHits();
            return cancel.isEmpty() ? Optional.empty() : Optional.of(new Request.Cancel(cancel));
        }
        if (submitted == null) {
            return Optional.empty();
        }
        if (query().isPresent()) {
            return submit();
        }
        List<Long> cancel = jobs();
        forgetHits();
        return cancel.isEmpty() ? Optional.empty() : Optional.of(new Request.Cancel(cancel));
    }

    /** Forgets the submitted search, its hits and its explanation. */
    private void forgetHits() {
        lineError = null;
        submitted = null;
        running = null;
        explain = null;
        highlighting = null;
        stepPending = false;
        selected = -1;
        detailsScroll = 0;
    }

    private Optional<HitList> hitList() {
        return submitted == null ? Optional.empty() : Optional.ofNullable(submitted.hits);
    }

    private Optional<Hit> selectedHit() {
        return hitList()
                .filter(list -> !list.hits.isEmpty() && selected >= 0)
                .map(list -> list.hits.get(Math.min(selected, list.hits.size() - 1)));
    }

    /** The next page of hits, once the selection is on the last row and more may follow. */
    private Optional<Request> nextPage() {
        if (running != null || submitted == null || submitted.hits == null) {
            return Optional.empty();
        }
        HitList list = submitted.hits;
        if (list.error != null || !list.more || Math.max(selected, 0) + 1 < list.hits.size()) {
            return Optional.empty();
        }
        int offset = list.hits.size();
        int limit = Math.min(SEARCH_PAGE, StartSearchParams.MAX_WINDOW - offset);
        if (limit < 1) {
            return Optional.empty();
        }
        StartSearchParams first = submitted.params;
        StartSearchParams params = new StartSearchParams(
                first.query(),
                first.similarity(),
                first.sort(),
                offset,
                limit,
                first.exactTotalHits(),
                first.fields(),
                first.highlight());
        long id = nextId++;
        running = new Running(id, null, true, limit, 0);
        return Optional.of(new Request.Start(id, params, List.of()));
    }

    /** Whether {@code searchId} is the search that runs. */
    private boolean runs(long searchId) {
        return running != null && running.id() == searchId;
    }

    /**
     * The job of search {@code searchId} has started. Returns the job to cancel if that search was
     * replaced or stopped meanwhile.
     */
    public OptionalLong searchStarted(long searchId, long jobId) {
        if (!runs(searchId)) {
            return OptionalLong.of(jobId);
        }
        running = new Running(running.id(), jobId, running.more(), running.limit(), running.docsSearched());
        return OptionalLong.empty();
    }

    /**
     * Shows the progress of search {@code searchId}, or its page of hits. Returns the next page if
     * the selection already waits for it, or the next document if a step waited for the page.
     */
    public Optional<Request> searchProgress(long searchId, GetSearchResult result) {
        if (!runs(searchId) || submitted == null) {
            return Optional.empty();
        }
        Optional<SearchResults> found = result.results();
        if (found.isEmpty()) {
            running =
                    new Running(running.id(), running.jobId(), running.more(), running.limit(), result.docsSearched());
            return Optional.empty();
        }
        SearchResults results = found.get();
        Running page = running;
        running = null;
        boolean full = results.hits().size() == page.limit();
        if (page.more()) {
            if (submitted.hits == null) {
                return Optional.empty();
            }
            HitList list = submitted.hits;
            list.hits.addAll(results.hits());
            list.totalHits = results.totalHits();
            list.relation = results.totalHitsRelation();
            list.more = moreHits(full, list.hits.size(), list.totalHits, list.relation);
            if (stepPending) {
                stepPending = false;
                return step(true);
            }
        } else {
            submitted.hits = new HitList(
                    results, moreHits(full, results.hits().size(), results.totalHits(), results.totalHitsRelation()));
            submitted.error = null;
            selected = 0;
        }
        return nextPage();
    }

    /** Shows why search {@code searchId} could not start or failed. */
    public void searchFailed(long searchId, String error) {
        if (runs(searchId)) {
            fail(running, error);
        }
    }

    private void fail(Running page, String error) {
        stepPending = false;
        running = null;
        if (submitted == null) {
            return;
        }
        if (page.more()) {
            if (submitted.hits != null) {
                submitted.hits.error = error;
            }
        } else {
            submitted.error = error;
        }
    }

    /** Explains the selected hit for the shown query, unless it is explained already. */
    private Optional<Request> explainSelected() {
        Optional<Hit> hit = selectedHit();
        if (submitted == null || hit.isEmpty()) {
            return Optional.empty();
        }
        StartExplainParams params = new StartExplainParams(
                submitted.params.query(),
                submitted.params.similarity(),
                hit.get().docId());
        if (explain != null && explain.params.equals(params) && explain.error == null) {
            return Optional.empty();
        }
        List<Long> cancel = new ArrayList<>();
        explainJob().ifPresent(job -> cancel.add(job[1]));
        explain = new Explain(nextId++, params);
        detailsScroll = 0;
        return Optional.of(new Request.Explain(explain.id, params, cancel));
    }

    private boolean explains(long explainId) {
        return explain != null && explain.id == explainId && explain.running();
    }

    /** The job of explanation {@code explainId} has started. Returns the job to cancel if it was replaced or stopped. */
    public OptionalLong explainStarted(long explainId, long jobId) {
        if (!explains(explainId)) {
            return OptionalLong.of(jobId);
        }
        explain.jobId = jobId;
        return OptionalLong.empty();
    }

    public void explainProgress(long explainId, GetExplainResult result) {
        if (explains(explainId)) {
            result.explanation().ifPresent(explanation -> explain.explanation = explanation);
        }
    }

    public void explainFailed(long explainId, String error) {
        if (explains(explainId)) {
            explain.error = error;
        }
    }

    /** How the hits are highlighted: absent while highlighting is off, and for queries without terms. */
    private Optional<HighlightOptions> highlightOptions() {
        if (highlights == Highlights.OFF || mode == Mode.DOC_VALUES || mode == Mode.KNN) {
            return Optional.empty();
        }
        return Optional.of(
                new HighlightOptions(HIGHLIGHT_PASSAGES, highlights == Highlights.QUERY_FIELDS, analyzer.analyzer()));
    }

    /**
     * The submitted query and how its hits are highlighted, for the documents screen to mark the
     * matches in a hit it opens (ADR 0043); absent while highlighting is off, for queries without
     * terms, and for the regex scan.
     */
    public Optional<Documents.MatchQuery> matchesQuery() {
        if (submitted == null || mode == Mode.REGEX) {
            return Optional.empty();
        }
        return submitted.params.highlight().map(options -> new Documents.MatchQuery(submitted.params.query(), options));
    }

    /**
     * Finds every match in the value of the selected hit's first passage, to view it with them
     * marked (ADR 0042); nothing for a hit without passages.
     */
    private Optional<Request> viewSelected() {
        Optional<Hit> hit = selectedHit();
        Optional<HighlightOptions> options = submitted.params.highlight();
        if (hit.isEmpty() || hit.get().highlights().isEmpty() || options.isEmpty()) {
            return Optional.empty();
        }
        HitHighlight first = hit.get().highlights().getFirst();
        StartHighlightParams params = new StartHighlightParams(
                submitted.params.query(), hit.get().docId(), Optional.of(first.field()), options.get());
        List<Long> cancel = new ArrayList<>();
        highlightJob().ifPresent(job -> cancel.add(job[1]));
        highlighting =
                new Highlighting(nextId++, params, first.passages().getFirst().valueIndex());
        return Optional.of(new Request.Highlight(highlighting.id, params, cancel));
    }

    private boolean highlights(long highlightId) {
        return highlighting != null && highlighting.id == highlightId && highlighting.error == null;
    }

    /** The job of highlight {@code highlightId} has started. Returns the job to cancel if it was replaced or stopped. */
    public OptionalLong highlightStarted(long highlightId, long jobId) {
        if (!highlights(highlightId)) {
            return OptionalLong.of(jobId);
        }
        highlighting.jobId = jobId;
        return OptionalLong.empty();
    }

    /**
     * Takes the matches of highlight {@code highlightId} once they are in, and asks to view the value
     * with them marked: the value of the hit's first passage, or else the first with a match.
     */
    public Optional<Request> highlightProgress(long highlightId, GetHighlightResult result) {
        Optional<DocumentHighlight> highlight = result.highlight();
        if (!highlights(highlightId) || highlight.isEmpty()) {
            return Optional.empty();
        }
        Highlighting done = highlighting;
        String highlighted = done.params.field().orElseThrow();
        List<ValueHighlight> values = highlight.get().fields().stream()
                .filter(matches -> matches.field().equals(highlighted))
                .findFirst()
                .map(FieldMatches::values)
                .orElse(List.of());
        Optional<ValueHighlight> value = values.stream()
                .filter(v -> v.valueIndex() == done.valueIndex)
                .findFirst()
                .or(() -> values.stream().findFirst());
        if (value.isEmpty()) {
            done.error = "No match in " + TextFormat.escapeControl(highlighted) + " of document " + done.params.docId()
                    + ".";
            return Optional.empty();
        }
        highlighting = null;
        return Optional.of(new Request.View(
                done.params.docId(),
                highlighted,
                value.get().valueIndex(),
                value.get().spans(),
                value.get().matchCount()));
    }

    public void highlightFailed(long highlightId, String error) {
        if (highlights(highlightId)) {
            highlighting.error = error;
        }
    }

    /** The explanation that belongs to the selected hit and the shown search, if any. */
    private Optional<Explain> shownExplanation() {
        if (explain == null || submitted == null) {
            return Optional.empty();
        }
        boolean shows = selectedHit().map(Hit::docId).orElse(-1) == explain.params.docId()
                && explain.params.query().equals(submitted.params.query())
                && explain.params.similarity().equals(submitted.params.similarity());
        return shows ? Optional.of(explain) : Optional.empty();
    }

    private boolean scans(long id) {
        return scan != null && scanId == id && scan.running();
    }

    /** The job of scan {@code id} has started. Returns the job to cancel if it was replaced or stopped. */
    public OptionalLong storedScanStarted(long id, long jobId) {
        if (!scans(id)) {
            return OptionalLong.of(jobId);
        }
        scan.started(jobId);
        return OptionalLong.empty();
    }

    public void storedScanProgress(long id, GetStoredScanResult result) {
        if (scans(id)) {
            scan.progress(result);
        }
    }

    /** Shows why the regex scan could not start or failed. */
    public void storedScanFailed(long id, String error) {
        if (scans(id)) {
            scan.failed(error);
        }
    }

    private boolean checksRecall(long id) {
        return recall != null && recallId == id && recall.running();
    }

    /** The job of recall check {@code id} has started. Returns the job to cancel if it was replaced or stopped. */
    public OptionalLong knnRecallStarted(long id, long jobId) {
        if (!checksRecall(id)) {
            return OptionalLong.of(jobId);
        }
        recall.started(jobId);
        return OptionalLong.empty();
    }

    public void knnRecallProgress(long id, GetKnnRecallResult result) {
        if (checksRecall(id)) {
            recall.progress(result);
        }
    }

    /** Shows why the recall check could not start or failed. */
    public void knnRecallFailed(long id, String error) {
        if (checksRecall(id)) {
            recall.failed(error);
        }
    }

    /**
     * {@code r} in the KNN mode (ADR 0086): checks the recall of the line's target, or with a blank
     * line of {@value #RECALL_SAMPLE} sampled documents, with the k and filter of the mode, and stops
     * what runs. The check is of the approximate search, even while {@code e} is on.
     */
    private Optional<Request> startRecall() {
        Optional<IndexField> vectors = vectorField();
        if (vectors.isEmpty()) {
            return Optional.empty();
        }
        Optional<ClassicQuery> filter = knnFilter.text().isBlank() || field >= fields.size()
                ? Optional.empty()
                : Optional.of(classic(knnFilter.text(), fields.get(field)));
        KnnQuery query;
        OptionalInt sample = OptionalInt.empty();
        if (input.text().isBlank()) {
            query = new KnnQuery(
                    vectors.get().name(), OptionalInt.empty(), Optional.empty(), OptionalInt.of(k), false, filter);
            sample = OptionalInt.of(RECALL_SAMPLE);
        } else {
            KnnSyntax.Target target;
            try {
                target = KnnSyntax.target(
                        input.text(), vectors.get().vectorEncoding().orElse(VectorEncoding.FLOAT32));
            } catch (IllegalArgumentException e) {
                List<Long> cancel = jobs();
                forgetHits();
                recall = null;
                lineError = e.getMessage();
                return cancel.isEmpty() ? Optional.empty() : Optional.of(new Request.Cancel(cancel));
            }
            query = new KnnQuery(
                    vectors.get().name(),
                    target instanceof KnnSyntax.Target.Doc doc ? OptionalInt.of(doc.docId()) : OptionalInt.empty(),
                    target instanceof KnnSyntax.Target.Vector vector ? Optional.of(vector.vector()) : Optional.empty(),
                    OptionalInt.of(k),
                    false,
                    filter);
        }
        lineError = null;
        StartKnnRecallParams params = new StartKnnRecallParams(query, sample);
        List<Long> cancel = jobs();
        if (running != null) {
            fail(running, CANCELLED);
        }
        recall = new KnnRecall(params);
        recallId = nextId++;
        focus = Focus.HITS;
        detailsScroll = 0;
        return Optional.of(new Request.Recall(recallId, params, cancel));
    }

    /**
     * Enter on the recall check's rows: a missed document opens on the documents screen, a sampled
     * target is searched for its nearest.
     */
    private Optional<Request> openRecallRow() {
        Optional<KnnRecallTarget> target = recall.selectedTarget();
        if (target.isPresent() && target.get().docId().isPresent()) {
            input.set(Integer.toString(target.get().docId().getAsInt()));
            binary = false;
            return submit();
        }
        return recall.selectedMiss().map(miss -> new Request.Document(miss.docId()));
    }

    /**
     * Selects the previous or next hit and shows its document, for {@code [}/{@code ]} on the
     * documents screen. Past the last hit, the next page is fetched first, if there is one.
     */
    public Optional<Request> step(boolean forward) {
        if (mode == Mode.REGEX) {
            if (scan == null) {
                return Optional.empty();
            }
            OptionalInt docId = scan.step(forward);
            return docId.isPresent() ? Optional.of(new Request.Document(docId.getAsInt())) : Optional.empty();
        }
        Optional<HitList> list = hitList();
        if (list.isEmpty()) {
            return Optional.empty();
        }
        int current = Math.max(selected, 0);
        int row = forward ? current + 1 : current - 1;
        if (row < 0) {
            return Optional.empty();
        }
        if (row < list.get().hits.size()) {
            selected = row;
            detailsScroll = 0;
            return selectedHit().map(hit -> new Request.Document(hit.docId()));
        }
        Optional<Request> request = nextPage();
        if (request.isPresent()) {
            stepPending = true;
        }
        return request;
    }

    /**
     * Edits the query line while it is open (Enter searches, Esc closes it), or a popup (Enter applies
     * and searches again, Esc cancels). Otherwise {@code /} opens the line, {@code m} cycles classic
     * syntax, standard syntax, a term, doc values, More-Like-This for a document or a text, and a
     * regex over stored values, {@code p} opens the parser options or the More-Like-This settings,
     * {@code s} the similarity and the sort, {@code S} reverses the first sort level, {@code l} the stored fields per hit, {@code f}/{@code F}
     * step the field, {@code n} the encoding of a doc values number field, {@code h} switches its bytes
     * between text and hex, {@code a} switches between OR and AND, {@code t} between an exact and an
     * approximate count, {@code x} explains the selected hit, Enter shows its document, {@code
     * b}/{@code B} browse the documents that match without or with the deleted ones (ADR 0031, ADR
     * 0032 for the regex), and Tab switches between the hits and the details.
     */
    public Optional<Request> handleKey(KeyEvent key) {
        if (form != null) {
            return formKey(key);
        }
        if (editing) {
            return editKey(key);
        }
        Optional<String> unsupported = unsupported(key);
        if (unsupported.isPresent()) {
            return unsupported.map(Request.Unsupported::new);
        }
        if (mode == Mode.REGEX) {
            Optional<Optional<Request>> handled = regexKey(key);
            if (handled.isPresent()) {
                return handled.get();
            }
        }
        KeyCode code = key.code();
        boolean recalls = mode == Mode.KNN && recall != null;
        if (code == KeyCode.ENTER) {
            return enter(recalls);
        }
        if (code == KeyCode.TAB) {
            focus = focus == Focus.HITS ? Focus.DETAILS : Focus.HITS;
            return Optional.empty();
        }
        if (code == KeyCode.CHAR) {
            Optional<Optional<Request>> handled = charKey(key.codePoint());
            if (handled.isPresent()) {
                return handled.get();
            }
        }
        return moveKey(key, recalls);
    }

    /** Enter opens the selected hit, or the selected row of a recall check. */
    private Optional<Request> enter(boolean recalls) {
        return recalls ? openRecallRow() : selectedHit().map(hit -> new Request.Document(hit.docId()));
    }

    /** A key while a line of the query is edited: Enter submits, Esc stops. */
    private Optional<Request> editKey(KeyEvent key) {
        switch (key.code()) {
            case ENTER -> {
                editing = false;
                if (editLine == EditLine.K) {
                    k = parseK(kInput.text());
                }
                return submit();
            }
            case ESCAPE -> editing = false;
            default -> typeKey(key);
        }
        return Optional.empty();
    }

    /** Edits the line being edited. */
    private void typeKey(KeyEvent key) {
        String typed = LineInput.typed(key);
        // A document id and k have digits only.
        boolean nonDigit = typed != null && !typed.chars().allMatch(c -> c >= '0' && c <= '9');
        switch (editLine) {
            case FILTER -> knnFilter.handleKey(key, LineInput::wordStart);
            case K -> {
                if (!nonDigit) {
                    kInput.handleKey(key, LineInput::wordStart);
                }
            }
            case QUERY -> {
                if (!(mode == Mode.LIKE && nonDigit)
                        && input.handleKey(key, LineInput::wordStart) == LineInput.Edit.CHANGED) {
                    binary = false;
                }
            }
        }
    }

    private Optional<Request> moveKey(KeyEvent key, boolean recalls) {
        if (focus == Focus.DETAILS) {
            scrollDetails(key);
            return Optional.empty();
        }
        if (recalls) {
            recall.moveSelection(key);
            return Optional.empty();
        }
        Optional<HitList> list = hitList();
        if (list.isEmpty()) {
            return Optional.empty();
        }
        OptionalInt row = Selection.moved(Math.max(selected, 0), list.get().hits.size(), key);
        if (row.isEmpty()) {
            return Optional.empty();
        }
        if (row.getAsInt() != selected) {
            selected = row.getAsInt();
            detailsScroll = 0;
        }
        return nextPage();
    }

    /** {@code text} as a k: from 1 to {@link KnnQuery#MAX_K}, the nearest of those if out of range, {@link #k} if blank. */
    private int parseK(String text) {
        if (text.isBlank()) {
            return k;
        }
        try {
            return Math.clamp(Long.parseLong(text.strip()), 1, KnnQuery.MAX_K);
        } catch (NumberFormatException _) {
            // More digits than a long has.
            return KnnQuery.MAX_K;
        }
    }

    /** The character keys of every mode but the regex one; empty for a key they don't take. */
    private Optional<Optional<Request>> charKey(int c) {
        Optional<Request> none = Optional.empty();
        boolean knn = mode == Mode.KNN;
        switch (c) {
            case '/' -> {
                edit(hasFields(), EditLine.QUERY);
                return Optional.of(none);
            }
            case 'w' -> {
                edit(knn && hasFields(), EditLine.FILTER);
                return Optional.of(none);
            }
            case 'k' -> {
                return editK();
            }
            case 'e' -> {
                return toggleKnnExact();
            }
            case 'K' -> {
                return knn ? Optional.of(startRecall()) : Optional.empty();
            }
            case 'm' -> {
                List<Mode> modes = modes();
                return Optional.of(switchMode(modes.get((modes.indexOf(mode) + 1) % modes.size())));
            }
            case 'p' -> {
                openParserForm();
                return Optional.of(none);
            }
            case 's' -> {
                form = new ScoringForm(scoring);
                return Optional.of(none);
            }
            case 'S' -> {
                scoring = scoring.reversed();
                return Optional.of(resubmit());
            }
            case 'l' -> {
                form = new FieldsForm(hitFields);
                return Optional.of(none);
            }
            case 'f', 'F' -> {
                return Optional.of(cycleField(c == 'f'));
            }
            case 'n' -> {
                return nextNumberFormat();
            }
            case 'h' -> {
                return toggleHex();
            }
            case 'a' -> {
                return toggleOperator();
            }
            case 't' -> {
                exact = !exact;
                return Optional.of(resubmit());
            }
            case 'x' -> {
                return Optional.of(explainSelected());
            }
            case 'H' -> {
                return cycleHighlights();
            }
            case 'v' -> {
                return Optional.of(viewSelected());
            }
            case 'b', 'B' -> {
                return Optional.of(filter(c == 'B'));
            }
            default -> {
                return Optional.empty();
            }
        }
    }

    /** Starts editing {@code line}, if {@code allowed}. */
    private void edit(boolean allowed, EditLine line) {
        if (allowed) {
            editing = true;
            editLine = line;
        }
    }

    private Optional<Optional<Request>> editK() {
        if (mode == Mode.KNN && hasFields()) {
            editing = true;
            editLine = EditLine.K;
            kInput = new LineInput(Integer.toString(k));
            return Optional.of(Optional.empty());
        }
        return Optional.empty();
    }

    private Optional<Optional<Request>> toggleKnnExact() {
        if (mode == Mode.KNN) {
            knnExact = !knnExact;
            return Optional.of(resubmit());
        }
        return Optional.empty();
    }

    /** The More Like This options for its modes, the parser's for those that parse a query. */
    private void openParserForm() {
        // The KNN filter is a classic query, with the classic mode's parser options and operator.
        if (mode == Mode.LIKE || mode == Mode.LIKE_TEXT) {
            form = new LikeForm(like);
        } else if (parses()) {
            form = new OptionsForm(parser, mode != Mode.STANDARD);
        }
    }

    /** Whether the mode parses a query, with the parser's options and operator. */
    private boolean parses() {
        return mode == Mode.CLASSIC || mode == Mode.STANDARD || mode == Mode.KNN;
    }

    /** Steps to the next field, or the one before: of the doc values, the vectors or the query. */
    private Optional<Request> cycleField(boolean forward) {
        boolean knn = mode == Mode.KNN;
        if (mode == Mode.DOC_VALUES && !docValuesFields.isEmpty()) {
            docValuesField = step(docValuesField, docValuesFields.size(), forward);
            return resubmit();
        }
        if (knn && !vectorFields.isEmpty()) {
            vectorField = step(vectorField, vectorFields.size(), forward);
            Optional<Request> next = resubmit();
            if (next.isEmpty() && recall != null) {
                // A recall check belongs to its field: the next field doesn't show it.
                List<Long> cancel = jobs();
                recall = null;
                return cancel.isEmpty() ? Optional.empty() : Optional.of(new Request.Cancel(cancel));
            }
            return next;
        }
        boolean likes = mode == Mode.LIKE || mode == Mode.LIKE_TEXT;
        if (!fields.isEmpty() && !likes && mode != Mode.DOC_VALUES && !knn) {
            field = step(field, fields.size(), forward);
            return resubmit();
        }
        return Optional.empty();
    }

    /** The index after {@code index}, or before it, of {@code size}, round. */
    private static int step(int index, int size, boolean forward) {
        return forward ? (index + 1) % size : (index + size - 1) % size;
    }

    private Optional<Optional<Request>> nextNumberFormat() {
        if (mode == Mode.DOC_VALUES && numberField()) {
            number = nextNumber(number);
            return Optional.of(resubmit());
        }
        return Optional.empty();
    }

    private Optional<Optional<Request>> toggleHex() {
        if (mode == Mode.DOC_VALUES && !numberField()) {
            hex = !hex;
            return Optional.of(resubmit());
        }
        return Optional.empty();
    }

    private Optional<Optional<Request>> toggleOperator() {
        if (parses()) {
            operator = operator == QueryOperator.OR ? QueryOperator.AND : QueryOperator.OR;
            return Optional.of(resubmit());
        }
        return Optional.of(Optional.empty());
    }

    private Optional<Optional<Request>> cycleHighlights() {
        if (mode == Mode.DOC_VALUES || mode == Mode.KNN) {
            return Optional.empty();
        }
        highlights = switch (highlights) {
            case QUERY_FIELDS -> Highlights.ANY_FIELD;
            case ANY_FIELD -> Highlights.OFF;
            case OFF -> Highlights.QUERY_FIELDS;
        };
        return Optional.of(resubmit());
    }

    /** Passes a key to the open popup, and takes its outcome: a changed setting searches again. */
    private Optional<Request> formKey(KeyEvent key) {
        Form.Event<?> event = switch (form) {
            case OptionsForm options -> options.handleKey(key);
            case LikeForm likeForm -> likeForm.handleKey(key);
            case ScoringForm scoringForm -> scoringForm.handleKey(key);
            case FieldsForm fieldsForm -> fieldsForm.handleKey(key);
            default -> throw new IllegalStateException(form.toString());
        };
        if (event instanceof Form.Event.None<?>) {
            return Optional.empty();
        }
        form = null;
        if (!(event instanceof Form.Event.Apply<?> apply)) {
            return Optional.empty();
        }
        boolean changed = false;
        switch (apply.draft()) {
            case ParserSettings settings
            when !settings.equals(parser) -> {
                parser = settings;
                changed = true;
            }
            case LikeSettings settings
            when !settings.equals(like) -> {
                like = settings;
                changed = true;
            }
            case ScoringSettings settings
            when !settings.equals(scoring) -> {
                scoring = settings;
                changed = true;
            }
            case HitFields settings
            when !settings.equals(hitFields) -> {
                hitFields = settings;
                changed = true;
            }
            default -> {}
        }
        return changed ? resubmit() : Optional.empty();
    }

    /**
     * The keys of the regex mode: {@code /} edits the pattern, {@code i} switches case-insensitivity,
     * {@code d} whether deleted documents are scanned, Enter shows the selected hit's document, {@code
     * v} its value with the matches, and the navigation keys move through the hits. The parser,
     * scoring, field, operator, count and explain keys do nothing. Empty for the keys the other modes
     * share: {@code m}, {@code l}, {@code b}/{@code B} and Tab.
     */
    private Optional<Optional<Request>> regexKey(KeyEvent key) {
        Optional<Request> none = Optional.empty();
        if (key.code() == KeyCode.ENTER) {
            return Optional.of(scan == null ? none : scan.selected().map(hit -> new Request.Document(hit.docId())));
        }
        if (key.code() == KeyCode.CHAR) {
            switch (key.codePoint()) {
                case '/' -> {
                    editing = true;
                    return Optional.of(none);
                }
                case 'i' -> {
                    caseInsensitive = !caseInsensitive;
                    return Optional.of(resubmit());
                }
                case 'd' -> {
                    includeDeleted = !includeDeleted;
                    return Optional.of(resubmit());
                }
                case 'p', 's', 'S', 'f', 'F', 'a', 't', 'x' -> {
                    return Optional.of(none);
                }
                case 'v' -> {
                    if (scan == null) {
                        return Optional.of(none);
                    }
                    return Optional.of(scan.selected()
                            .map(hit -> new Request.View(
                                    hit.docId(), hit.field(), hit.valueIndex(), hit.spans(), hit.matchCount())));
                }
                default -> {}
            }
        }
        if (focus == Focus.HITS && key.code() != KeyCode.TAB && scan != null && scan.moveSelection(key)) {
            return Optional.of(none);
        }
        return Optional.empty();
    }

    private void scrollDetails(KeyEvent key) {
        switch (key.code()) {
            case DOWN -> detailsScroll = saturated(detailsScroll + 1L);
            case UP -> detailsScroll = Math.max(detailsScroll - 1, 0);
            case PAGE_DOWN -> detailsScroll = saturated(detailsScroll + (long) PAGE);
            case PAGE_UP -> detailsScroll = Math.max(detailsScroll - PAGE, 0);
            case HOME -> detailsScroll = 0;
            case END -> detailsScroll = Integer.MAX_VALUE;
            case CHAR -> {
                switch (key.codePoint()) {
                    case 'j' -> detailsScroll = saturated(detailsScroll + 1L);
                    case 'k' -> detailsScroll = Math.max(detailsScroll - 1, 0);
                    case 'g' -> detailsScroll = 0;
                    case 'G' -> detailsScroll = Integer.MAX_VALUE;
                    default -> {}
                }
            }
            default -> {}
        }
    }

    private static int saturated(long value) {
        return (int) Math.min(value, Integer.MAX_VALUE);
    }

    /** The keys of the query line or the popup while one takes the keys; empty otherwise. */
    public Optional<String> promptKeys() {
        switch (form) {
            case OptionsForm options -> {
                return Optional.of(options.keys());
            }
            case LikeForm likeForm -> {
                return Optional.of(likeForm.keys());
            }
            case ScoringForm _ -> {
                return Optional.of(ScoringForm.keys());
            }
            case FieldsForm _ -> {
                return Optional.of(FieldsForm.keys());
            }
            case null, default -> {}
        }
        if (editing && editLine == EditLine.K) {
            return Optional.of(" ⏎ set k and search   Esc cancel   digits only");
        }
        if (editing) {
            return Optional.of(" ⏎ search   Esc done   ←→ Ctrl+A/E/U/K/W edit");
        }
        return Optional.empty();
    }

    /** The keys of the mode (ADR 0120): those the index has the features for (ADR 0044). */
    public Keys keys() {
        boolean like = mode == Mode.LIKE || mode == Mode.LIKE_TEXT;
        boolean knn = mode == Mode.KNN;
        Keys.Builder keys = Keys.builder()
                .key(
                        FIND,
                        "/",
                        switch (mode) {
                            case REGEX -> "pattern";
                            case LIKE -> "doc id";
                            case LIKE_TEXT -> "text";
                            case DOC_VALUES -> "values";
                            case KNN -> "target";
                            case TERM -> "term";
                            default -> "query";
                        })
                .keyIf(modes().size() > 1, FIND, "m", "mode");
        if (mode == Mode.REGEX) {
            return keys.more(FIND, "i", "case")
                    .more(FIND, "d", "deleted")
                    .keyIf(supports(Feature.DOCUMENTS), SHOW, "⏎", "open doc")
                    .key(SHOW, "v", "view value")
                    .more(SHOW, "l", "stored fields")
                    .build();
        }
        boolean highlights = supports(Feature.HIGHLIGHT) && mode != Mode.DOC_VALUES && !knn;
        // Enter on a sampled target of a recall check searches its nearest (ADR 0086).
        boolean sampled = knn && recall != null && recall.params().sample().isPresent();
        return keys.moreIf(knn, FIND, "w", "filter")
                .moreIf(knn, FIND, "k", "k")
                .moreIf(knn, FIND, "e", "exact")
                .moreIf(!like, FIND, "f/F", "field")
                .moreIf(mode == Mode.DOC_VALUES, FIND, "n", "number")
                .moreIf(mode == Mode.DOC_VALUES, FIND, "h", "hex")
                .moreIf(mode == Mode.CLASSIC || mode == Mode.STANDARD || knn, FIND, "a", "AND/OR")
                .moreIf(like, FIND, "p", "More Like This settings")
                .moreIf(parses(), FIND, "p", "parser options")
                .keyIf(sampled, SHOW, "⏎", "nearest")
                .keyIf(!sampled && supports(Feature.DOCUMENTS), SHOW, "⏎", "open doc")
                .keyIf(highlights, SHOW, "v", "view")
                .moreIf(highlights, SHOW, "H", "highlight")
                .keyIf(supports(Feature.EXPLAIN), SHOW, "x", "explain")
                .more(SHOW, "t", "count")
                .more(SHOW, "l", "stored fields")
                .keyIf(scores(), SORT, "s/S", "sort")
                .moreIf(knn, DO, "K", "recall")
                .keyIf(supports(Feature.FILTER), DO, "b/B", "browse")
                .build();
    }

    /** The jumps of the {@code g} menu (ADR 0120): the selected hit's document. */
    public List<GoMenu.Target> jumps() {
        Optional<Integer> doc = mode == Mode.REGEX
                ? Optional.ofNullable(scan).flatMap(StoredScan::selected).map(hit -> hit.docId())
                : selectedHit().map(Hit::docId);
        return supports(Feature.DOCUMENTS) && doc.isPresent()
                ? List.of(new GoMenu.Target(Jump.DOCUMENT, "document " + doc.get()))
                : List.of();
    }

    /** Where {@code g} and {@code jump} go: {@code g d} shows the selected hit's document, as Enter does. */
    public Optional<Request> jump(Jump jump) {
        if (jump != Jump.DOCUMENT) {
            return Optional.empty();
        }
        if (!supports(Feature.DOCUMENTS)) {
            return Optional.of(new Request.Unsupported("Open doc"));
        }
        if (mode == Mode.REGEX) {
            return scan == null ? Optional.empty() : scan.selected().map(hit -> new Request.Document(hit.docId()));
        }
        return selectedHit().map(hit -> new Request.Document(hit.docId()));
    }

    public void render(Frame frame, Rect area) {
        if (!hasFields()) {
            String text = switch (mode) {
                case DOC_VALUES -> "No field has doc values, so there is nothing to search. m switches the mode.";
                case KNN -> "No field has KNN vectors, so there is nothing to search. m switches the mode.";
                default -> "No field has postings, so there is nothing to search.";
            };
            frame.renderWidget(Panels.wrapped(text, panel(" Query ", true), area), area);
            return;
        }
        List<Rect> parts = Layout.vertical()
                // The KNN mode has a second line, for the filter.
                .constraints(Constraint.length(mode == Mode.KNN ? 4 : 3), Constraint.fill(3), Constraint.fill(2))
                .split(area);
        renderQuery(frame, parts.get(0));
        renderHits(frame, parts.get(1));
        renderDetails(frame, parts.get(2));
        switch (form) {
            case OptionsForm options -> options.render(frame, area);
            case LikeForm likeForm -> likeForm.render(frame, area);
            case ScoringForm scoringForm -> scoringForm.render(frame, area);
            case FieldsForm fieldsForm -> fieldsForm.render(frame, area);
            case null, default -> {}
        }
    }

    private void renderQuery(Frame frame, Rect area) {
        String fieldName = field < fields.size() ? TextFormat.escapeControl(fields.get(field)) : "";
        String title = queryTitle(fieldName);
        Block block = panel(title, editing);
        Rect inner = block.inner(area);
        frame.renderWidget(block, area);
        if (mode == Mode.KNN && inner.height() > 1) {
            renderKnnSecondLine(frame, new Rect(inner.x(), inner.y() + 1, inner.width(), 1), fieldName);
            inner = new Rect(inner.x(), inner.y(), inner.width(), 1);
        }
        if (editing && editLine == EditLine.QUERY) {
            LineInput.Visible visible = input.visible(inner.width());
            frame.renderWidget(Paragraph.from(Line.from(visible.text())), inner);
            frame.setCursorPosition(inner.x() + visible.cursorColumn(), inner.y());
        } else if (input.text().isEmpty()) {
            String hint = switch (mode) {
                case LIKE -> "Press / to type a document id, or g m on the documents screen.";
                case LIKE_TEXT -> "Press / to type a text to find documents like it.";
                case REGEX -> "Press / to type a regular expression.";
                case DOC_VALUES -> "Press / to type a value, several values, a range, or a prefix.";
                case KNN -> "Press / to type a document id or a vector in brackets, or g n on the documents screen.";
                default -> "Press / to type a query.";
            };
            frame.renderWidget(Paragraph.from(Line.styled(hint, Form.dim())), inner);
        } else {
            frame.renderWidget(Paragraph.from(Line.from(TextFormat.escapeControl(input.text()))), inner);
        }
    }

    private String queryTitle(String fieldName) {
        String count = exact ? "exact count" : "count to 1000";
        return switch (mode) {
            case CLASSIC ->
                " Classic query · default field " + fieldName + " · " + operator + " · " + count + highlightsTitle()
                        + " ";
            case STANDARD ->
                " Standard query · default field " + fieldName + " · " + operator + " · " + count + highlightsTitle()
                        + " ";
            case TERM ->
                " Term · field " + fieldName + (binary ? " · hex" : "") + " · " + count + highlightsTitle() + " ";
            case LIKE -> " More like this document · " + like.fieldsText() + " · " + count + highlightsTitle() + " ";
            case LIKE_TEXT -> " More like this text · " + like.fieldsText() + " · " + count + highlightsTitle() + " ";
            case REGEX ->
                " Regex over stored values · fields " + hitFields.text().orElse("all") + " · "
                        + (caseInsensitive ? "ignore case" : "match case")
                        + (includeDeleted ? " · deleted docs too" : "") + " ";
            case DOC_VALUES -> docValuesTitle(count);
            case KNN ->
                " KNN · " + vectorField().map(Search::vectorText).orElse("") + " · "
                        + (knnExact ? "exact" : "k " + k + " · approximate") + " ";
        };
    }

    private String docValuesTitle(String count) {
        String name =
                docValuesField().map(f -> TextFormat.escapeControl(f.name())).orElse("");
        DocValuesType type = docValuesField().map(IndexField::docValuesType).orElse(DocValuesType.NONE);
        String encoding = hex ? "hex" : "text";
        if (numberField()) {
            encoding = numberLabel(number);
        }
        return " Doc values · field " + name + " (" + (type == DocValuesType.NONE ? "none" : type.name()) + ") · "
                + encoding + " · " + count + " ";
    }

    /** The KNN mode's second line: the filter, or the k while it is typed. */
    private void renderKnnSecondLine(Frame frame, Rect area, String defaultField) {
        String label = editing && editLine == EditLine.K ? "k      " : "where  ";
        LineInput line = editing && editLine == EditLine.K ? kInput : knnFilter;
        Span labelSpan = Span.styled(label, Style.EMPTY.bold());
        Rect rest = new Rect(area.x() + label.length(), area.y(), Math.max(area.width() - label.length(), 0), 1);
        if (editing && editLine != EditLine.QUERY) {
            LineInput.Visible visible = line.visible(rest.width());
            frame.renderWidget(Paragraph.from(Line.from(labelSpan, Span.raw(visible.text()))), area);
            frame.setCursorPosition(rest.x() + visible.cursorColumn(), rest.y());
        } else if (knnFilter.text().isEmpty()) {
            frame.renderWidget(
                    Paragraph.from(Line.from(
                            labelSpan,
                            Span.styled(
                                    "w types a filter in classic syntax, default field " + defaultField, Form.dim()))),
                    area);
        } else {
            frame.renderWidget(
                    Paragraph.from(Line.from(labelSpan, Span.raw(TextFormat.escapeControl(knnFilter.text())))), area);
        }
    }

    /** A vector field for a title: {@code embedding (8d float, cosine)}. */
    private static String vectorText(IndexField field) {
        return TextFormat.escapeControl(field.name()) + " (" + field.vectorDimension() + "d"
                + field.vectorEncoding()
                        .map(e -> " " + Overview.vectorEncoding(e))
                        .orElse("")
                + field.vectorSimilarity()
                        .map(s -> ", " + Overview.vectorSimilarity(s))
                        .orElse("") + ")";
    }

    private void renderHits(Frame frame, Rect area) {
        boolean focused = focus == Focus.HITS && !editing;
        if (mode == Mode.KNN && recall != null) {
            recall.render(frame, area, focused);
            return;
        }
        if (mode == Mode.REGEX) {
            if (scan != null) {
                scan.render(frame, area, focused, maxDoc);
            } else {
                frame.renderWidget(panel(" Hits ", focused), area);
            }
            return;
        }
        String searching = searchingText();
        if (lineError != null) {
            String what = mode == Mode.KNN ? " Cannot read the target " : " Cannot read the values ";
            message(
                    frame,
                    area,
                    what,
                    focused,
                    Line.styled(lineError, Palette.current().error()));
            return;
        }
        if (submitted == null) {
            frame.renderWidget(panel(" Hits ", focused), area);
            return;
        }
        if (submitted.error != null) {
            message(
                    frame,
                    area,
                    " Search failed ",
                    focused,
                    Line.styled(submitted.error, Palette.current().error()));
            return;
        }
        if (submitted.hits == null) {
            frame.renderWidget(panel(" " + (searching == null ? "" : searching) + " ", focused), area);
            return;
        }
        renderHitList(frame, area, submitted.hits, hitsTitle(submitted.hits, searching), focused);
    }

    /** How far the running search is, if one runs. */
    private String searchingText() {
        if (running == null) {
            return null;
        }
        long percent = maxDoc > 0 ? running.docsSearched() * 100 / maxDoc : 0;
        return "searching… " + percent + "%";
    }

    private String hitsTitle(HitList list, String searching) {
        String title = " " + list.total() + " in " + list.tookMillis + " ms ";
        Optional<String> sort = scoring.sortText();
        if (sort.isPresent()) {
            title += "· sorted by " + sort.get() + " ";
        }
        if (searching != null) {
            title += "· " + searching + " ";
        } else if (highlighting != null && highlighting.error == null) {
            title += "· finding the matches… ";
        } else if (list.error != null) {
            title += "· cannot read more ";
        } else if (!list.more && list.hits.size() >= StartSearchParams.MAX_WINDOW) {
            title += "· first " + StartSearchParams.MAX_WINDOW + " only ";
        }
        return title;
    }

    private void renderHitList(Frame frame, Rect area, HitList list, String title, boolean focused) {
        if (list.hits.isEmpty()) {
            message(frame, area, title, focused, Line.from("No document matches."));
            return;
        }
        int rankWidth = Math.max(Integer.toString(list.hits.size()).length(), 1);
        int docWidth = Selection.numberWidth(list.hits.stream().mapToLong(Hit::docId));
        List<String> sortTexts = list.hits.stream().map(Search::sortText).toList();
        int sortWidth = Math.min(
                sortTexts.stream()
                        .mapToInt(text -> text.codePointCount(0, text.length()))
                        .max()
                        .orElse(0),
                MAX_SORT_WIDTH);
        boolean sorted = sortWidth > 0;
        OptionalInt source = submitted
                .params
                .query()
                .moreLikeThis()
                .map(MoreLikeThisQuery::docId)
                .orElse(OptionalInt.empty());
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < list.hits.size(); i++) {
            Hit hit = list.hits.get(i);
            List<Cell> cells = new ArrayList<>();
            cells.add(right(Integer.toString(i + 1), rankWidth));
            cells.add(right(Integer.toString(hit.docId()), docWidth));
            cells.add(right(TextFormat.score(hit.score(), 9), 9));
            if (sorted) {
                cells.add(Cell.from(sortTexts.get(i)));
            }
            boolean isSource = source.isPresent() && source.getAsInt() == hit.docId();
            cells.add(Cell.from(Line.from(fieldSpans(hit, isSource))));
            rows.add(Row.from(cells.toArray(Cell[]::new)));
        }
        tailRow(list.more, Optional.ofNullable(list.error)).ifPresent(rows::add);
        List<Cell> header = new ArrayList<>(List.of(Cell.from("#"), Cell.from("Doc"), Cell.from("    Score")));
        List<Constraint> widths = new ArrayList<>(
                List.of(Constraint.length(rankWidth), Constraint.length(docWidth), Constraint.length(9)));
        if (sorted) {
            header.add(Cell.from("Sorted by"));
            widths.add(Constraint.length(Math.max(sortWidth, 9)));
        }
        header.add(Cell.from("Fields"));
        widths.add(Constraint.fill());
        table.select(Math.clamp(selected, 0, list.hits.size() - 1));
        Table widget = Table.builder()
                .rows(rows)
                .header(Row.from(header.toArray(Cell[]::new)).style(Style.EMPTY.bold()))
                .widths(widths.toArray(Constraint[]::new))
                .columnSpacing(COLUMN_SPACING)
                .highlightStyle(highlight(focused))
                .highlightSymbol("")
                .highlightSpacing(Table.HighlightSpacing.NEVER)
                .block(panel(title, focused))
                .build();
        frame.renderStatefulWidget(widget, area, table);
    }

    /** A hit's fields: the first passage of each highlighted field, or else its stored fields. */
    private List<Span> fieldSpans(Hit hit, boolean isSource) {
        List<Span> fieldSpans = new ArrayList<>();
        if (isSource) {
            fieldSpans.add(Span.raw(SOURCE_MARK + " "));
        }
        if (hit.highlights().isEmpty()) {
            fieldSpans.add(Span.raw(fieldsText(hit)));
            return fieldSpans;
        }
        // The first passage of each field, as the stored fields would be.
        for (HitHighlight highlight : hit.highlights()) {
            if (highlight != hit.highlights().getFirst()) {
                fieldSpans.add(Span.raw("  "));
            }
            fieldSpans.add(Span.styled(TextFormat.escapeControl(highlight.field()) + ": ", Form.dim()));
            fieldSpans.addAll(passageSpans(hit, highlight, highlight.passages().getFirst()));
        }
        return fieldSpans;
    }

    private static void message(Frame frame, Rect area, String title, boolean focused, Line line) {
        frame.renderWidget(Panels.wrapped(List.of(line), panel(title, focused), area), area);
    }

    private void renderDetails(Frame frame, Rect area) {
        boolean focused = focus == Focus.DETAILS && !editing;
        String title;
        List<Line> lines;
        Optional<Explain> shown = shownExplanation();
        if (shown.isPresent()) {
            Explain explained = shown.get();
            title = " Explanation of doc " + explained.params.docId() + " ";
            lines = new ArrayList<>();
            if (explained.error != null) {
                lines.add(Line.styled(explained.error, Palette.current().error()));
            } else if (explained.explanation != null) {
                explanationLines(explained.explanation, 0, lines);
            } else {
                lines.add(Line.styled("Explaining…", Form.dim()));
            }
        } else if (mode == Mode.REGEX) {
            title = " Scan ";
            lines = scanLines();
        } else if (mode == Mode.KNN && recall != null) {
            title = " Recall ";
            lines = recall.detailLines();
        } else {
            title = " Query ";
            lines = queryLines();
        }
        // TamboUI scrolls no further than to show the last line at the bottom, so the scroll stops
        // there too (ADR 0034 §10, the commits screen).
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

    /** The regex scan, or how it works. */
    private List<Line> scanLines() {
        if (scan != null) {
            return scan.detailLines();
        }
        return dimmed(List.of(
                "A Java regular expression, found anywhere in each stored text value and in each",
                "binary value that is UTF-8, for example:",
                "  sku\\d{4}  (?i)lamp  \"name\":\\s*\"[^\"]*\"  (?s)begin.*end  ^$",
                "Every document is read, which is slow on a large index: Esc stops the scan.",
                "i ignores case · d scans deleted documents too · l chooses the fields"));
    }

    private static List<Line> dimmed(List<String> texts) {
        return new ArrayList<>(
                texts.stream().map(text -> Line.styled(text, Form.dim())).toList());
    }

    /** The parsed and rewritten query, or how to write one. */
    private List<Line> queryLines() {
        Optional<HitList> found = hitList();
        if (found.isEmpty()) {
            List<Line> lines = dimmed(
                    switch (mode) {
                        case CLASSIC ->
                            List.of(
                                    "Classic syntax, for example:",
                                    "  fox  title:fox  +fox -dog  fox AND (red OR blue)",
                                    "  \"quick fox\"  \"quick fox\"~2  qu*  f?x  fox~  /f[ao]x/",
                                    "  title:[a TO m]  fox^2");
                        case STANDARD ->
                            List.of(
                                    "Standard syntax: the classic one, plus point fields, for example:",
                                    "  price:[10 TO 20]  quantity>=5  rating:{2.5 TO *]  quantity:7",
                                    "Point fields need the type of their values: p sets it.");
                        case TERM ->
                            List.of("The line is one term of the field, as it is: nothing is analyzed or split.");
                        case DOC_VALUES ->
                            List.of(
                                    "Doc values: one value, several values, a range, or a prefix, for example:",
                                    "  42   -1 0 1   [10 TO 20]   {0 TO *]   \"new york\" boston   kit*",
                                    "A prefix is for bytes and stands alone; * matches any value, \"kit*\" is a value.",
                                    "Numbers are read as n says, as the documents screen's x shows them;",
                                    "h reads bytes as hex. A document matches if any of its values does.",
                                    "Every value of the field is read, and a BINARY field is scanned: Esc stops it.");
                        case KNN ->
                            List.of(
                                    "KNN: the documents whose vectors are nearest to a target, for example:",
                                    "  42   [0.5, -1, 2.25]   [12 -7 0 3]",
                                    "A document id takes that document's vector; a vector in brackets has the field's",
                                    "dimension, floats or bytes from -128 to 127. k sets how many the graph search",
                                    "finds; e scores every document with a vector instead, exactly, best first.",
                                    "w filters in classic syntax; p and a set its parser options and operator.",
                                    "K checks how many of the exact k nearest the graph search finds: for the line's",
                                    "target, or with a blank line for " + RECALL_SAMPLE
                                            + " documents spread over the segments.");
                        case REGEX -> List.of();
                        case LIKE ->
                            List.of(
                                    "More like this: the line is a document id. Its terms come from each",
                                    "field's term vector, or else from its stored text, analyzed with the",
                                    "analyzer of the analysis screen (6). p chooses the fields and the limits.");
                        case LIKE_TEXT ->
                            List.of(
                                    "More like this: the line is a text. Each chosen field analyzes it with the",
                                    "analyzer of the analysis screen (6), and the best terms of all fields make",
                                    "the query. p chooses the fields and the limits.");
                    });
            lines.addAll(parserLines());
            lines.add(Line.empty());
            lines.add(settingsLine());
            return lines;
        }
        HitList list = found.get();
        List<Line> lines = highlightLines();
        lines.add(Line.from("Parsed     " + TextFormat.escapeControl(list.query)));
        lines.add(Line.from("Rewritten  " + TextFormat.escapeControl(list.rewrittenQuery)));
        lines.addAll(parserLines());
        lines.add(Line.empty());
        Optional<MoreLikeThisQuery> source = submitted.params.query().moreLikeThis();
        if (source.isPresent()) {
            if (list.moreLikeThisTerms.isPresent()
                    && !list.moreLikeThisTerms.get().isEmpty()) {
                lines.addAll(likeTermLines(list.moreLikeThisTerms.get()));
                lines.add(Line.empty());
            }
            OptionalInt docId = source.get().docId();
            if (list.rewrittenQuery.startsWith("MatchNoDocsQuery")) {
                lines.add(Line.styled(
                        docId.isPresent()
                                ? "No term of the document passed the limits (p), or its fields have neither a term "
                                        + "vector nor stored text."
                                : "No term of the text passed the limits (p), or none is in the chosen fields.",
                        Form.dim()));
            } else if (docId.isPresent() && list.hits.stream().anyMatch(hit -> hit.docId() == docId.getAsInt())) {
                lines.add(
                        Line.styled(SOURCE_MARK + " marks document " + docId.getAsInt() + ", the source.", Form.dim()));
            }
        }
        if (!list.hits.isEmpty() && supports(Feature.EXPLAIN)) {
            lines.add(Line.styled("x explains the selected hit.", Form.dim()));
        }
        lines.add(settingsLine());
        return lines;
    }

    /** How the hits are highlighted (ADR 0042), for the query line's title: nothing for the default. */
    private String highlightsTitle() {
        return switch (highlights) {
            case QUERY_FIELDS -> "";
            case ANY_FIELD -> " · highlight any field's terms";
            case OFF -> " · no highlights";
        };
    }

    /**
     * What the settings popups set, for the footer's {@code p/s/l settings}; {@code s} only if the
     * index has the features for it (ADR 0044).
     */
    private Line settingsLine() {
        String popups = switch (mode) {
            case TERM, DOC_VALUES -> "s similarity and sort · l stored fields per hit";
            case REGEX -> "l fields to scan";
            case LIKE, LIKE_TEXT -> "p more like this · s similarity and sort · l stored fields per hit";
            case CLASSIC, STANDARD -> "p parser options · s similarity and sort · l stored fields per hit";
            case KNN -> "p filter's parser options · s similarity and sort · l stored fields per hit";
        };
        if (!scores()) {
            popups = popups.replace("s similarity and sort · ", "");
        }
        return Line.styled(popups, Form.dim());
    }

    /**
     * The parser options that differ from Lucene's defaults, the point types of the standard parser or
     * the More-Like-This limits, a similarity other than Lucene's default, an analyzer other than the
     * standard one, and the stored fields per hit unless they are all of them.
     */
    private List<Line> parserLines() {
        List<Line> lines = new ArrayList<>();
        scoring.similarityText().ifPresent(text -> lines.add(Line.from("Similarity " + text)));
        hitFields.text().ifPresent(text -> lines.add(Line.from("Fields     " + text)));
        if (mode != Mode.TERM && mode != Mode.DOC_VALUES && !analyzer.isStandard()) {
            lines.add(Line.from("Analyzer   " + analyzer.text()));
        }
        switch (mode) {
            case TERM, REGEX, DOC_VALUES -> {
                return lines;
            }
            case LIKE, LIKE_TEXT -> {
                lines.add(Line.from("Like       " + like.limitsText()));
                return lines;
            }
            default -> {}
        }
        List<String> changed = parser.changed(mode != Mode.STANDARD);
        if (!changed.isEmpty()) {
            lines.add(Line.from("Options    " + String.join(" · ", changed)));
        }
        if (mode == Mode.STANDARD) {
            lines.add(Line.from("Points     " + parser.pointsText()));
        }
        return lines;
    }

    /** The next encoding {@code n} steps to, in the order of the documents screen's {@code x}. */
    static DocValuesNumber nextNumber(DocValuesNumber number) {
        return switch (number) {
            case LONG -> DocValuesNumber.DOUBLE;
            case DOUBLE -> DocValuesNumber.SORTABLE_DOUBLE;
            case SORTABLE_DOUBLE -> DocValuesNumber.FLOAT;
            case FLOAT -> DocValuesNumber.SORTABLE_FLOAT;
            case SORTABLE_FLOAT -> DocValuesNumber.LONG;
        };
    }

    /** How the title names an encoding, as the documents screen's {@code x} does. */
    static String numberLabel(DocValuesNumber number) {
        return switch (number) {
            case LONG -> "long";
            case DOUBLE -> "double bits";
            case SORTABLE_DOUBLE -> "sortable double";
            case FLOAT -> "float bits";
            case SORTABLE_FLOAT -> "sortable float";
        };
    }

    /**
     * The terms a More-Like-This query chose, best first, as a table: score, term frequency in the
     * source, document frequency, field and term (ADR 0028).
     */
    static List<Line> likeTermLines(List<LikeTerm> terms) {
        int tfWidth = Selection.numberWidth(terms.stream().mapToLong(LikeTerm::tf));
        int dfWidth = Selection.numberWidth(terms.stream().mapToLong(LikeTerm::docFreq));
        List<String> names = terms.stream()
                .map(term -> TextFormat.escapeControl(term.field()))
                .toList();
        int fieldWidth = Math.max(
                names.stream()
                        .mapToInt(name -> name.codePointCount(0, name.length()))
                        .max()
                        .orElse(0),
                "field".length());
        List<Line> lines = new ArrayList<>();
        String header = "Terms      %9s  %" + tfWidth + "s  %" + dfWidth + "s  %s  term";
        String row = "           %9s  %" + tfWidth + "d  %" + dfWidth + "d  %s  %s";
        lines.add(Line.styled(
                String.format(Locale.ROOT, header, "score", "tf", "df", Form.padded("field", fieldWidth)), Form.dim()));
        for (int i = 0; i < terms.size(); i++) {
            LikeTerm term = terms.get(i);
            lines.add(Line.from(String.format(
                    Locale.ROOT,
                    row,
                    TextFormat.score(term.score(), 9),
                    term.tf(),
                    term.docFreq(),
                    Form.padded(names.get(i), fieldWidth),
                    TextFormat.escapeControl(term.term()))));
        }
        return lines;
    }

    /** Whether hits may follow a page: it was full, the window is not used up, and the count says so. */
    static boolean moreHits(boolean full, int fetched, long total, TotalHitsRelation relation) {
        return full
                && fetched < StartSearchParams.MAX_WINDOW
                && (relation == TotalHitsRelation.GREATER_THAN_OR_EQUAL_TO || fetched < total);
    }

    /** The values a hit was sorted by, one after the other; {@code –} for a missing value. */
    static String sortText(Hit hit) {
        return hit.sortValues().stream()
                .map((SortValue value) -> value.number()
                        .orElseGet(() -> value.term()
                                .map(term -> TextFormat.bytesText(term.value(), term.binary()))
                                .orElse("–")))
                .collect(Collectors.joining(" · "));
    }

    /**
     * A passage of a hit, with its matches marked, and {@code …} where its value goes on (ADR 0042).
     */
    static List<Span> passageSpans(Hit hit, HitHighlight highlight, HighlightPassage passage) {
        return StoredScan.snippetSpans(
                passage.snippet(), passage.spans(), valueLength(hit, highlight.field(), passage.valueIndex()));
    }

    /**
     * The length in bytes of a stored text value of a hit, from its cut text or its full length; -1
     * if the hit doesn't have it.
     */
    private static long valueLength(Hit hit, String field, int index) {
        for (HitField hitField : hit.fields()) {
            if (hitField.name().equals(field) && index < hitField.values().size()) {
                StoredValue value = hitField.values().get(index);
                if (value.fullLength().isPresent()) {
                    return value.fullLength().getAsLong();
                }
                return value.binary() ? -1 : value.value().getBytes(StandardCharsets.UTF_8).length;
            }
        }
        return -1;
    }

    /**
     * The passages of the selected hit, per field with the source of its offsets (ADR 0042), and how
     * the search for every match of {@code v} goes.
     */
    private List<Line> highlightLines() {
        List<Line> lines = new ArrayList<>();
        Optional<Hit> hit = selectedHit();
        if (hit.isEmpty() || hit.get().highlights().isEmpty()) {
            return lines;
        }
        lines.add(Line.from(
                Span.styled("Highlights of doc " + hit.get().docId(), Style.EMPTY.bold()),
                Span.styled(" · " + highlights.text, Form.dim())));
        for (HitHighlight highlight : hit.get().highlights()) {
            lines.add(Line.from(
                    Span.styled(
                            TextFormat.escapeControl(highlight.field()),
                            Palette.current().accent()),
                    Span.styled(" · offsets from " + offsetSourceText(highlight.offsetSource()), Form.dim())));
            for (HighlightPassage passage : highlight.passages()) {
                List<Span> spans = new ArrayList<>();
                spans.add(Span.styled("  [" + passage.valueIndex() + "] ", Form.dim()));
                spans.addAll(passageSpans(hit.get(), highlight, passage));
                lines.add(Line.from(spans));
            }
        }
        if (highlighting != null && highlighting.params.docId() == hit.get().docId()) {
            if (highlighting.error != null) {
                lines.add(Line.styled(
                        "Cannot view the matches: " + highlighting.error,
                        Palette.current().error()));
            } else {
                lines.add(Line.styled(
                        "Finding every match in "
                                + TextFormat.escapeControl(
                                        highlighting.params.field().orElseThrow()) + "[" + highlighting.valueIndex
                                + "]…",
                        Form.dim()));
            }
        } else {
            HitHighlight first = hit.get().highlights().getFirst();
            lines.add(Line.styled(
                    "v views " + TextFormat.escapeControl(first.field()) + "["
                            + first.passages().getFirst().valueIndex() + "] with every match marked.",
                    Form.dim()));
        }
        lines.add(Line.empty());
        return lines;
    }

    public static String offsetSourceText(OffsetSource source) {
        return switch (source) {
            case POSTINGS -> "the postings";
            case TERM_VECTORS -> "the term vector";
            case POSTINGS_WITH_TERM_VECTORS -> "the postings and the term vector";
            case ANALYSIS -> "analysis";
            case NONE -> "nowhere: no terms";
        };
    }

    /**
     * The stored fields of a hit on one line: {@code name=value, value  name=value}. A value that the
     * backend cut ends with {@code …}.
     */
    static String fieldsText(Hit hit) {
        return hit.fields().stream()
                .map((HitField f) -> TextFormat.escapeControl(f.name()) + "="
                        + f.values().stream().map(Search::valueText).collect(Collectors.joining(", ")))
                .collect(Collectors.joining("  "));
    }

    private static String valueText(StoredValue value) {
        String text = TextFormat.bytesText(value.value(), value.binary());
        return value.fullLength().isPresent() ? text + "…" : text;
    }

    /** The explanation as an indented tree: each node's value, then what it is. Parts that don't match are dimmed. */
    static void explanationLines(Explanation explanation, int depth, List<Line> lines) {
        String text =
                "  ".repeat(depth) + explanation.value() + "  " + TextFormat.escapeControl(explanation.description());
        lines.add(explanation.match() ? Line.from(text) : Line.styled(text, Form.dim()));
        for (Explanation detail : explanation.details()) {
            explanationLines(detail, depth + 1, lines);
        }
    }

    // For the tests.

    Mode mode() {
        return mode;
    }

    String inputText() {
        return input.text();
    }

    boolean hasForm() {
        return form != null;
    }

    boolean hasScan() {
        return scan != null;
    }

    DocValuesNumber number() {
        return number;
    }

    void setNumber(DocValuesNumber value) {
        number = value;
    }

    boolean hex() {
        return hex;
    }

    Optional<String> docValuesFieldName() {
        return docValuesField().map(IndexField::name);
    }
}
