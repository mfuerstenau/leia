package io.github.mfuerstenau.leia.protocol;

import io.github.mfuerstenau.leia.protocol.commits.GetCommitParams;
import io.github.mfuerstenau.leia.protocol.commits.GetCommitResult;
import io.github.mfuerstenau.leia.protocol.commits.GetCommitsParams;
import io.github.mfuerstenau.leia.protocol.commits.GetCommitsResult;
import io.github.mfuerstenau.leia.protocol.document.GetDocumentParams;
import io.github.mfuerstenau.leia.protocol.document.GetDocumentResult;
import io.github.mfuerstenau.leia.protocol.document.GetStoredValueParams;
import io.github.mfuerstenau.leia.protocol.document.GetStoredValueResult;
import io.github.mfuerstenau.leia.protocol.document.GetTermVectorParams;
import io.github.mfuerstenau.leia.protocol.document.GetTermVectorResult;
import io.github.mfuerstenau.leia.protocol.index.CloseIndexParams;
import io.github.mfuerstenau.leia.protocol.index.CloseIndexResult;
import io.github.mfuerstenau.leia.protocol.index.GetIndexStatsParams;
import io.github.mfuerstenau.leia.protocol.index.GetIndexStatsResult;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexParams;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexResult;
import io.github.mfuerstenau.leia.protocol.job.CancelJobResult;
import io.github.mfuerstenau.leia.protocol.job.JobParams;
import io.github.mfuerstenau.leia.protocol.job.JobStarted;
import io.github.mfuerstenau.leia.protocol.search.GetExplainResult;
import io.github.mfuerstenau.leia.protocol.search.GetSearchResult;
import io.github.mfuerstenau.leia.protocol.search.StartExplainParams;
import io.github.mfuerstenau.leia.protocol.search.StartSearchParams;
import io.github.mfuerstenau.leia.protocol.terms.GetFieldTermScanResult;
import io.github.mfuerstenau.leia.protocol.terms.GetPositionsParams;
import io.github.mfuerstenau.leia.protocol.terms.GetPositionsResult;
import io.github.mfuerstenau.leia.protocol.terms.GetPostingsParams;
import io.github.mfuerstenau.leia.protocol.terms.GetPostingsResult;
import io.github.mfuerstenau.leia.protocol.terms.GetTermsParams;
import io.github.mfuerstenau.leia.protocol.terms.GetTermsResult;
import io.github.mfuerstenau.leia.protocol.terms.StartFieldTermScanParams;
import io.github.mfuerstenau.leia.protocol.version.VersionParams;
import io.github.mfuerstenau.leia.protocol.version.VersionResult;
import java.util.List;
import java.util.Optional;

/** The registry of all JSON-RPC methods. Each one needs fixtures in {@code protocol/fixtures/}. */
public final class Methods {

    public static final RpcMethod<VersionParams, VersionResult> VERSION =
            new RpcMethod<>("version", VersionParams.class, VersionResult.class);

    /**
     * Opens the latest commit of an index, read-only, and makes it the open index. An index that was
     * open before is closed, and its jobs stopped, once the new one has opened; if opening fails, it
     * stays open.
     */
    public static final RpcMethod<OpenIndexParams, OpenIndexResult> OPEN_INDEX =
            new RpcMethod<>("openIndex", OpenIndexParams.class, OpenIndexResult.class);

    /**
     * Statistics of the open index that are cheap to compute: the commit and every field, without
     * iterating over terms. Fails with {@code NO_INDEX_OPEN} if no index is open.
     */
    public static final RpcMethod<GetIndexStatsParams, GetIndexStatsResult> GET_INDEX_STATS =
            new RpcMethod<>("getIndexStats", GetIndexStatsParams.class, GetIndexStatsResult.class);

    /**
     * Closes the open index, if any, and stops its jobs. Closing when no index is open is not an
     * error; the result then has no path.
     */
    public static final RpcMethod<CloseIndexParams, CloseIndexResult> CLOSE_INDEX =
            new RpcMethod<>("closeIndex", CloseIndexParams.class, CloseIndexResult.class);

    /**
     * Starts a job that scans the terms of a field of the open index: counts them and finds the
     * {@code topN} with the highest document frequency (ADR 0005). Fails with {@code NO_INDEX_OPEN},
     * {@code FIELD_NOT_FOUND}, or {@code INVALID_PARAMS} for a {@code topN} out of range.
     */
    public static final RpcMethod<StartFieldTermScanParams, JobStarted> START_FIELD_TERM_SCAN =
            new RpcMethod<>("startFieldTermScan", StartFieldTermScanParams.class, JobStarted.class);

    /**
     * The progress of a field term scan, and its outcome once it is done. The outcome is handed out
     * once: a scan that failed answers with its error, and afterwards the job is gone ({@code
     * JOB_NOT_FOUND}).
     */
    public static final RpcMethod<JobParams, GetFieldTermScanResult> GET_FIELD_TERM_SCAN =
            new RpcMethod<>("getFieldTermScan", JobParams.class, GetFieldTermScanResult.class);

    /**
     * Stops a job of any kind and forgets it. An unknown or finished job is not an error; the result
     * says whether a running job was stopped.
     */
    public static final RpcMethod<JobParams, CancelJobResult> CANCEL_JOB =
            new RpcMethod<>("cancelJob", JobParams.class, CancelJobResult.class);

    /**
     * What a document of the open index holds in every field: norm, stored values, doc values, and
     * whether it has a term vector (ADR 0006). A deleted document is read too. Stored values longer
     * than {@link GetDocumentResult#MAX_VALUE_LENGTH} bytes are cut (ADR 0008). Fails with {@code
     * NO_INDEX_OPEN}, or {@code DOC_NOT_FOUND} for an id outside 0 to maxDoc - 1.
     */
    public static final RpcMethod<GetDocumentParams, GetDocumentResult> GET_DOCUMENT =
            new RpcMethod<>("getDocument", GetDocumentParams.class, GetDocumentResult.class);

    /**
     * One stored value of a document, whole, however long it is (ADR 0008). Fails with {@code
     * NO_INDEX_OPEN}, {@code DOC_NOT_FOUND}, {@code FIELD_NOT_FOUND}, or {@code VALUE_NOT_FOUND} if
     * the document has no stored value with that index in the field.
     */
    public static final RpcMethod<GetStoredValueParams, GetStoredValueResult> GET_STORED_VALUE =
            new RpcMethod<>("getStoredValue", GetStoredValueParams.class, GetStoredValueResult.class);

    /**
     * The term vector of a document for a field, with positions, offsets and payloads as far as they
     * were stored. Empty if the document has none for the field. Fails with {@code NO_INDEX_OPEN},
     * {@code DOC_NOT_FOUND} or {@code FIELD_NOT_FOUND}.
     */
    public static final RpcMethod<GetTermVectorParams, GetTermVectorResult> GET_TERM_VECTOR =
            new RpcMethod<>("getTermVector", GetTermVectorParams.class, GetTermVectorResult.class);

    /**
     * A page of the terms of a field, in term order, from a given term on (ADR 0007). A field without
     * postings has none. Fails with {@code NO_INDEX_OPEN}, {@code FIELD_NOT_FOUND}, or {@code
     * INVALID_PARAMS} for a {@code limit} out of range or a {@code from} that is not valid hex.
     */
    public static final RpcMethod<GetTermsParams, GetTermsResult> GET_TERMS =
            new RpcMethod<>("getTerms", GetTermsParams.class, GetTermsResult.class);

    /**
     * A page of the postings of a term: the documents that contain it, from a given document id on,
     * deleted documents included (ADR 0007). Fails with {@code NO_INDEX_OPEN}, {@code
     * FIELD_NOT_FOUND}, {@code TERM_NOT_FOUND}, or {@code INVALID_PARAMS} for a {@code limit} out of
     * range, a negative {@code fromDoc} or a term that is not valid hex.
     */
    public static final RpcMethod<GetPostingsParams, GetPostingsResult> GET_POSTINGS =
            new RpcMethod<>("getPostings", GetPostingsParams.class, GetPostingsResult.class);

    /**
     * Where a term occurs in a document, from the postings, with offsets and payloads as far as they
     * were indexed (ADR 0007). Fails with {@code NO_INDEX_OPEN}, {@code DOC_NOT_FOUND}, {@code
     * FIELD_NOT_FOUND}, {@code TERM_NOT_FOUND} if the document does not contain the term, or {@code
     * INVALID_PARAMS} for a term that is not valid hex.
     */
    public static final RpcMethod<GetPositionsParams, GetPositionsResult> GET_POSITIONS =
            new RpcMethod<>("getPositions", GetPositionsParams.class, GetPositionsResult.class);

    /**
     * The commits of the open index's directory, newest first, read from the directory on every call
     * (ADR 0009). Commits that can't be read are left out. Fails with {@code NO_INDEX_OPEN}.
     */
    public static final RpcMethod<GetCommitsParams, GetCommitsResult> GET_COMMITS =
            new RpcMethod<>("getCommits", GetCommitsParams.class, GetCommitsResult.class);

    /**
     * One commit of the open index's directory whole: its segments with their details, and its files
     * (ADR 0009). Fails with {@code NO_INDEX_OPEN}, {@code COMMIT_NOT_FOUND} if the directory has no
     * {@code segments_N} of that generation, or {@code UNSUPPORTED_INDEX_FORMAT}, {@code CORRUPT_INDEX}
     * or {@code INDEX_NOT_READABLE} if the commit can't be read.
     */
    public static final RpcMethod<GetCommitParams, GetCommitResult> GET_COMMIT =
            new RpcMethod<>("getCommit", GetCommitParams.class, GetCommitResult.class);

    /**
     * Starts a job that searches the open index and returns one page of hits with their stored fields
     * (ADR 0010). Fails with {@code NO_INDEX_OPEN}, {@code INVALID_QUERY} if the expression doesn't
     * parse, {@code FIELD_NOT_FOUND} for an unknown default field, term field or field to send, or
     * {@code INVALID_PARAMS} for a page out of range or a query without exactly one alternative.
     */
    public static final RpcMethod<StartSearchParams, JobStarted> START_SEARCH =
            new RpcMethod<>("startSearch", StartSearchParams.class, JobStarted.class);

    /**
     * The progress of a search, and its outcome once it is done. The outcome is handed out once: a
     * search that failed answers with its error ({@code INVALID_QUERY} for too many clauses), and
     * afterwards the job is gone ({@code JOB_NOT_FOUND}).
     */
    public static final RpcMethod<JobParams, GetSearchResult> GET_SEARCH =
            new RpcMethod<>("getSearch", JobParams.class, GetSearchResult.class);

    /**
     * Starts a job that explains how a document scores for a query, or why it doesn't match (ADR
     * 0010). Fails as {@code startSearch} does, and with {@code DOC_NOT_FOUND} for an id outside 0
     * to maxDoc - 1.
     */
    public static final RpcMethod<StartExplainParams, JobStarted> START_EXPLAIN =
            new RpcMethod<>("startExplain", StartExplainParams.class, JobStarted.class);

    /**
     * The outcome of an explanation once it is done, handed out once as for {@code getSearch}.
     */
    public static final RpcMethod<JobParams, GetExplainResult> GET_EXPLAIN =
            new RpcMethod<>("getExplain", JobParams.class, GetExplainResult.class);

    public static final List<RpcMethod<?, ?>> ALL = List.of(
            VERSION,
            OPEN_INDEX,
            GET_INDEX_STATS,
            CLOSE_INDEX,
            START_FIELD_TERM_SCAN,
            GET_FIELD_TERM_SCAN,
            CANCEL_JOB,
            GET_DOCUMENT,
            GET_STORED_VALUE,
            GET_TERM_VECTOR,
            GET_TERMS,
            GET_POSTINGS,
            GET_POSITIONS,
            GET_COMMITS,
            GET_COMMIT,
            START_SEARCH,
            GET_SEARCH,
            START_EXPLAIN,
            GET_EXPLAIN);

    private Methods() {}

    public static Optional<RpcMethod<?, ?>> byName(String name) {
        return ALL.stream().filter(method -> method.name().equals(name)).findFirst();
    }
}
