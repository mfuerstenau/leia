package io.github.mfuerstenau.leia.server.legacy;

import io.github.mfuerstenau.leia.core.IndexDocument;
import io.github.mfuerstenau.leia.lucene3.Lucene3Index;
import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.document.BytesValue;
import io.github.mfuerstenau.leia.protocol.document.DocumentField;
import io.github.mfuerstenau.leia.protocol.document.GetDocumentParams;
import io.github.mfuerstenau.leia.protocol.document.GetDocumentResult;
import io.github.mfuerstenau.leia.protocol.document.GetStoredValueParams;
import io.github.mfuerstenau.leia.protocol.document.GetStoredValueResult;
import io.github.mfuerstenau.leia.protocol.document.GetTermVectorParams;
import io.github.mfuerstenau.leia.protocol.document.GetTermVectorResult;
import io.github.mfuerstenau.leia.protocol.document.StoredValue;
import io.github.mfuerstenau.leia.protocol.document.TermPosition;
import io.github.mfuerstenau.leia.protocol.document.TermVectorTerm;
import io.github.mfuerstenau.leia.protocol.index.DocValuesType;
import io.github.mfuerstenau.leia.protocol.index.Feature;
import io.github.mfuerstenau.leia.protocol.index.GetIndexStatsResult;
import io.github.mfuerstenau.leia.protocol.index.IndexCommitStats;
import io.github.mfuerstenau.leia.protocol.index.IndexEntry;
import io.github.mfuerstenau.leia.protocol.index.IndexField;
import io.github.mfuerstenau.leia.protocol.index.IndexOptions;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexResult;
import io.github.mfuerstenau.leia.protocol.index.OpenMode;
import io.github.mfuerstenau.leia.protocol.index.ReaderInfo;
import io.github.mfuerstenau.leia.protocol.job.JobStarted;
import io.github.mfuerstenau.leia.protocol.search.ClassicQuery;
import io.github.mfuerstenau.leia.protocol.search.Hit;
import io.github.mfuerstenau.leia.protocol.search.HitField;
import io.github.mfuerstenau.leia.protocol.search.QueryOperator;
import io.github.mfuerstenau.leia.protocol.search.SearchResults;
import io.github.mfuerstenau.leia.protocol.search.SortKey;
import io.github.mfuerstenau.leia.protocol.search.StartSearchParams;
import io.github.mfuerstenau.leia.protocol.search.TotalHitsRelation;
import io.github.mfuerstenau.leia.protocol.terms.GetPostingsParams;
import io.github.mfuerstenau.leia.protocol.terms.GetPostingsResult;
import io.github.mfuerstenau.leia.protocol.terms.GetTermsParams;
import io.github.mfuerstenau.leia.protocol.terms.GetTermsResult;
import io.github.mfuerstenau.leia.protocol.terms.Posting;
import io.github.mfuerstenau.leia.protocol.terms.TermEntry;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.document.GetDocumentHandler;
import io.github.mfuerstenau.leia.server.index.CurrentIndex;
import io.github.mfuerstenau.leia.server.job.Jobs;
import io.github.mfuerstenau.leia.server.terms.TermParams;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import org.apache.lucene.util.BytesRef;

/**
 * The methods that work on an index written by Lucene 2.x or 3.x, which {@link Lucene3Index} reads
 * with Lucene 3.6 (ADR 0044): each maps the facade's records to the protocol's. The handlers of these
 * methods call them when {@link CurrentIndex#legacy()} is present; every other method refuses with
 * {@code NOT_SUPPORTED}.
 */
public final class Lucene3Results {

    /** What works on such an index. */
    public static final Set<Feature> FEATURES = EnumSet.of(
            Feature.INDEX_STATS,
            Feature.DOCUMENTS,
            Feature.STORED_VALUES,
            Feature.TERM_VECTORS,
            Feature.TERMS,
            Feature.POSTINGS,
            Feature.CLASSIC_SEARCH);

    private Lucene3Results() {}

    /** {@code openIndex}: the result for {@code index}, just opened. */
    public static OpenIndexResult opened(Lucene3Index index) throws RpcException {
        Lucene3Index.Stats stats = stats(index);
        ReaderInfo reader = new ReaderInfo(
                0,
                stats.segmentsFile(),
                stats.numDocs(),
                stats.maxDoc(),
                stats.oldestMajor(),
                stats.oldestSegmentVersion());
        return new OpenIndexResult(
                index.path().toString(),
                OpenMode.READER,
                OptionalInt.of(stats.numDocs()),
                OptionalInt.of(stats.maxDoc()),
                List.of(new IndexEntry(index.path().toString(), Optional.of(reader))),
                List.of(),
                FEATURES,
                Lucene3Index.luceneVersion());
    }

    /** What the log says about {@code index}. */
    public static String describe(Lucene3Index index) {
        try {
            Lucene3Index.Stats stats = index.stats();
            return stats.segmentsFile() + ", " + stats.maxDoc() + " docs, segments by Lucene "
                    + String.join(", ", stats.segmentVersions()) + ", read with Lucene "
                    + Lucene3Index.luceneVersion();
        } catch (IOException e) {
            return "read with Lucene " + Lucene3Index.luceneVersion();
        }
    }

    private static Lucene3Index.Stats stats(Lucene3Index index) throws RpcException {
        try {
            return index.stats();
        } catch (IOException e) {
            throw new RpcException(
                    ErrorCode.INDEX_NOT_READABLE, "Cannot read the index statistics: " + e.getMessage(), e);
        }
    }

    /** {@code getIndexStats}. Lucene 3 records no term statistics per field, so they are absent. */
    public static GetIndexStatsResult indexStats(Lucene3Index index) throws RpcException {
        Lucene3Index.Stats stats = stats(index);
        IndexCommitStats commit = new IndexCommitStats(
                index.path().toString(),
                0,
                stats.segmentsFile(),
                stats.generation(),
                stats.version(),
                stats.segmentVersions().size(),
                stats.numDocs(),
                stats.maxDoc(),
                stats.oldestMajor(),
                // Lucene 3 doesn't record which version wrote the commit; the newest segment did.
                stats.segmentVersions().stream().max(String::compareTo).orElse("3.x"),
                stats.oldestSegmentVersion(),
                Map.copyOf(stats.userData()));
        return new GetIndexStatsResult(
                index.path().toString(),
                index.directoryImpl(),
                stats.segmentVersions().size(),
                stats.numDocs(),
                stats.maxDoc(),
                List.of(commit),
                stats.fields().stream().map(Lucene3Results::field).toList());
    }

    private static IndexField field(Lucene3Index.Field field) {
        return new IndexField(
                field.name(),
                field.number(),
                switch (field.indexOptions()) {
                    case NONE -> IndexOptions.NONE;
                    case DOCS -> IndexOptions.DOCS;
                    case DOCS_AND_FREQS -> IndexOptions.DOCS_AND_FREQS;
                    case DOCS_AND_FREQS_AND_POSITIONS -> IndexOptions.DOCS_AND_FREQS_AND_POSITIONS;
                },
                field.hasNorms(),
                field.hasPayloads(),
                field.hasTermVectors(),
                DocValuesType.NONE,
                0,
                0,
                0,
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    /** {@code getDocument}. */
    public static GetDocumentResult document(Lucene3Index index, GetDocumentParams params) throws RpcException {
        int docId = params.docId();
        checkDocId(index, docId);
        Lucene3Index.IndexDocument doc;
        try {
            doc = index.document(docId);
        } catch (IOException e) {
            throw new RpcException(
                    ErrorCode.INDEX_NOT_READABLE, "Cannot read document " + docId + ": " + e.getMessage(), e);
        }
        return new GetDocumentResult(
                doc.docId(),
                doc.deleted(),
                doc.fields().stream()
                        .map(field -> new DocumentField(
                                field.name(),
                                field.norm(),
                                field.stored().stream()
                                        .map(value -> storedValue(value, GetDocumentResult.MAX_VALUE_LENGTH))
                                        .toList(),
                                Optional.empty(),
                                field.hasTermVector(),
                                Optional.empty()))
                        .toList());
    }

    /** {@code getStoredValue}. */
    public static GetStoredValueResult storedValue(Lucene3Index index, GetStoredValueParams params)
            throws RpcException {
        checkDocId(index, params.docId());
        checkField(index, params.field());
        Lucene3Index.IndexDocument whole;
        try {
            whole = index.document(params.docId());
        } catch (IOException e) {
            throw new RpcException(
                    ErrorCode.INDEX_NOT_READABLE, "Cannot read document " + params.docId() + ": " + e.getMessage(), e);
        }
        List<Lucene3Index.StoredValue> values = whole.fields().stream()
                .filter(field -> field.name().equals(params.field()))
                .findFirst()
                .map(Lucene3Index.DocumentField::stored)
                .orElse(List.of());
        int i = params.index();
        if (i < 0 || i >= values.size()) {
            throw new RpcException(
                    ErrorCode.VALUE_NOT_FOUND,
                    "No stored value " + i + " in field '" + params.field() + "' of document " + params.docId()
                            + ": it has " + values.size());
        }
        return new GetStoredValueResult(storedValue(values.get(i), Integer.MAX_VALUE));
    }

    /** {@code getTermVector}. */
    public static GetTermVectorResult termVector(Lucene3Index index, GetTermVectorParams params) throws RpcException {
        checkDocId(index, params.docId());
        checkField(index, params.field());
        try {
            return new GetTermVectorResult(index.termVector(params.docId(), params.field()).stream()
                    .map(term -> new TermVectorTerm(
                            term.term(),
                            false,
                            term.freq(),
                            term.positions().stream()
                                    .map(position -> new TermPosition(
                                            position.position(),
                                            position.startOffset(),
                                            position.endOffset(),
                                            Optional.empty()))
                                    .toList()))
                    .toList());
        } catch (IOException e) {
            throw new RpcException(ErrorCode.INDEX_NOT_READABLE, "Cannot read the term vector: " + e.getMessage(), e);
        }
    }

    /** {@code getTerms}. Terms of Lucene 3 are text, so they are never binary. */
    public static GetTermsResult terms(Lucene3Index index, GetTermsParams params) throws RpcException {
        TermParams.checkLimit(params.limit(), GetTermsParams.MAX_LIMIT);
        Optional<String> from =
                params.from().isPresent() ? Optional.of(text(params.from().get())) : Optional.empty();
        checkField(index, params.field());
        try {
            Lucene3Index.TermsPage page = index.terms(params.field(), from, params.limit());
            return new GetTermsResult(
                    page.terms().stream()
                            .map(term -> new TermEntry(term.term(), false, term.docFreq(), term.totalTermFreq()))
                            .toList(),
                    page.next().map(next -> new BytesValue(next, false)));
        } catch (IOException e) {
            throw new RpcException(
                    ErrorCode.INDEX_NOT_READABLE,
                    "Cannot read the terms of field '" + params.field() + "': " + e.getMessage(),
                    e);
        }
    }

    /**
     * {@code getPostings}. Lucene 3 skips deleted documents in the postings, so none is marked deleted,
     * though {@code docFreq} counts them; a filter isn't supported.
     */
    public static GetPostingsResult postings(Lucene3Index index, GetPostingsParams params) throws RpcException {
        TermParams.checkLimit(params.limit(), GetPostingsParams.MAX_LIMIT);
        if (params.fromDoc() < 0) {
            throw new RpcException(
                    ErrorCode.INVALID_PARAMS, "Invalid params: fromDoc must not be negative, not " + params.fromDoc());
        }
        if (params.filter().isPresent()) {
            throw CurrentIndex.notSupported();
        }
        checkField(index, params.field());
        String term = text(params.term());
        try {
            Lucene3Index.PostingsPage page = index.postings(params.field(), term, params.fromDoc(), params.limit());
            if (page.docFreq() == 0) {
                throw new RpcException(
                        ErrorCode.TERM_NOT_FOUND,
                        "No term '" + TermParams.text(params.term()) + "' in field '" + params.field() + "'");
            }
            return new GetPostingsResult(
                    page.docFreq(),
                    page.totalTermFreq(),
                    page.postings().stream()
                            .map(posting -> new Posting(
                                    posting.docId(), false, OptionalInt.of(posting.freq()), Optional.empty()))
                            .toList(),
                    page.nextDoc());
        } catch (IOException e) {
            throw new RpcException(
                    ErrorCode.INDEX_NOT_READABLE,
                    "Cannot read the postings of '" + term + "' in field '" + params.field() + "': " + e.getMessage(),
                    e);
        }
    }

    /**
     * {@code startSearch}: a classic query, parsed with Lucene 3.6's {@code StandardAnalyzer} whatever
     * analyzer the params name, scored by Lucene 3's default similarity, best score first. Highlighting
     * is left out; another query or sort is not supported.
     */
    public static JobStarted startSearch(Lucene3Index index, StartSearchParams params, Jobs jobs) throws RpcException {
        int offset = params.offset();
        int limit = params.limit();
        if (limit < 1 || limit > StartSearchParams.MAX_LIMIT) {
            throw new RpcException(
                    ErrorCode.INVALID_PARAMS,
                    "Invalid params: limit must be between 1 and " + StartSearchParams.MAX_LIMIT + ", not " + limit);
        }
        if (offset < 0 || offset > StartSearchParams.MAX_WINDOW - limit) {
            throw new RpcException(
                    ErrorCode.INVALID_PARAMS,
                    "Invalid params: offset must be between 0 and " + (StartSearchParams.MAX_WINDOW - limit)
                            + " for a limit of " + limit + ", not " + offset);
        }
        ClassicQuery classic = params.query().classic().orElseThrow(CurrentIndex::notSupported);
        if (!bestScoreFirst(params.sort())) {
            throw CurrentIndex.notSupported();
        }
        if (params.fields().isPresent()) {
            for (String field : params.fields().get()) {
                checkField(index, field);
            }
        }
        Lucene3Index.ParsedQuery query;
        try {
            query = index.parse(
                    classic.expression(),
                    new Lucene3Index.ParserOptions(
                            classic.defaultField(),
                            classic.defaultOperator() == QueryOperator.AND,
                            classic.options().allowLeadingWildcard(),
                            classic.options().phraseSlop(),
                            classic.options().fuzzyPrefixLength(),
                            classic.options().enablePositionIncrements(),
                            classic.autoGeneratePhraseQueries()));
        } catch (Lucene3Index.InvalidQueryException e) {
            throw new RpcException(ErrorCode.INVALID_QUERY, "Invalid query: " + e.getMessage(), e);
        }
        Optional<List<String>> fields = params.fields().map(List::copyOf);
        long jobId = jobs.start(
                "search " + query + " with Lucene " + Lucene3Index.luceneVersion() + ", hits " + offset + " to "
                        + (offset + limit),
                SearchResults.class,
                progress -> {
                    try {
                        return results(index.search(query, offset, limit, fields, progress::scanned));
                    } catch (IOException e) {
                        throw new RpcException(
                                ErrorCode.INDEX_NOT_READABLE, "Cannot search the index: " + e.getMessage(), e);
                    }
                });
        return new JobStarted(jobId);
    }

    /** Whether {@code sort} is the best score first: no key, or the score not reversed alone. */
    private static boolean bestScoreFirst(List<SortKey> sort) {
        return sort.isEmpty()
                || sort.size() == 1
                        && sort.getFirst().score().isPresent()
                        && !sort.getFirst().score().get().reverse();
    }

    private static SearchResults results(Lucene3Index.SearchResults results) {
        return new SearchResults(
                results.query(),
                results.rewrittenQuery(),
                results.totalHits(),
                TotalHitsRelation.EQUAL_TO,
                results.hits().stream().map(Lucene3Results::hit).toList(),
                results.tookMillis(),
                Optional.empty());
    }

    private static Hit hit(Lucene3Index.Hit hit) {
        return new Hit(
                hit.docId(),
                hit.score(),
                List.of(),
                hit.fields().entrySet().stream()
                        .map(field -> new HitField(
                                field.getKey(),
                                field.getValue().stream()
                                        .map(value -> storedValue(value, SearchResults.MAX_VALUE_LENGTH))
                                        .toList()))
                        .toList(),
                List.of());
    }

    /** {@code value} as the protocol carries it, cut as {@link GetDocumentHandler#storedValue} cuts. */
    private static StoredValue storedValue(Lucene3Index.StoredValue value, int maxLength) {
        IndexDocument.StoredValue core = switch (value) {
            case Lucene3Index.StoredValue.Text(String text) -> new IndexDocument.StoredValue.Text(text);
            case Lucene3Index.StoredValue.Binary(byte[] bytes) ->
                new IndexDocument.StoredValue.Binary(new BytesRef(bytes));
            case Lucene3Index.StoredValue.Numeric(Integer number) -> new IndexDocument.StoredValue.IntValue(number);
            case Lucene3Index.StoredValue.Numeric(Long number) -> new IndexDocument.StoredValue.LongValue(number);
            case Lucene3Index.StoredValue.Numeric(Float number) -> new IndexDocument.StoredValue.FloatValue(number);
            case Lucene3Index.StoredValue.Numeric(Number number) ->
                new IndexDocument.StoredValue.DoubleValue(number.doubleValue());
        };
        return GetDocumentHandler.storedValue(core, maxLength);
    }

    /** A term of the params as text: Lucene 3 terms are strings. */
    private static String text(BytesValue term) throws RpcException {
        BytesRef bytes = TermParams.term(term);
        return new String(bytes.bytes, bytes.offset, bytes.length, StandardCharsets.UTF_8);
    }

    private static void checkDocId(Lucene3Index index, int docId) throws RpcException {
        if (docId < 0 || docId >= index.maxDoc()) {
            throw new RpcException(
                    ErrorCode.DOC_NOT_FOUND, "No document " + docId + " in the index: maxDoc is " + index.maxDoc());
        }
    }

    private static void checkField(Lucene3Index index, String field) throws RpcException {
        if (index.fields().stream().noneMatch(f -> f.name().equals(field))) {
            throw new RpcException(ErrorCode.FIELD_NOT_FOUND, "No field '" + field + "' in the index");
        }
    }
}
