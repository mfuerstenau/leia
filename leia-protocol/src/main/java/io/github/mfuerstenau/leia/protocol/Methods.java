package io.github.mfuerstenau.leia.protocol;

import io.github.mfuerstenau.leia.protocol.analysis.AnalyzeParams;
import io.github.mfuerstenau.leia.protocol.analysis.AnalyzeResult;
import io.github.mfuerstenau.leia.protocol.analysis.GetAnalysisComponentsParams;
import io.github.mfuerstenau.leia.protocol.analysis.GetAnalysisComponentsResult;
import io.github.mfuerstenau.leia.protocol.analysis.GetAutoStopWordsResult;
import io.github.mfuerstenau.leia.protocol.analysis.StartAutoStopWordsParams;
import io.github.mfuerstenau.leia.protocol.check.GetCheckIndexParams;
import io.github.mfuerstenau.leia.protocol.check.GetCheckIndexResult;
import io.github.mfuerstenau.leia.protocol.check.GetCheckTargetsParams;
import io.github.mfuerstenau.leia.protocol.check.GetCheckTargetsResult;
import io.github.mfuerstenau.leia.protocol.check.StartCheckIndexParams;
import io.github.mfuerstenau.leia.protocol.commits.GetCommitParams;
import io.github.mfuerstenau.leia.protocol.commits.GetCommitResult;
import io.github.mfuerstenau.leia.protocol.commits.GetCommitsParams;
import io.github.mfuerstenau.leia.protocol.commits.GetCommitsResult;
import io.github.mfuerstenau.leia.protocol.document.GetDocumentParams;
import io.github.mfuerstenau.leia.protocol.document.GetDocumentResult;
import io.github.mfuerstenau.leia.protocol.document.GetReconstructResult;
import io.github.mfuerstenau.leia.protocol.document.GetStoredValueParams;
import io.github.mfuerstenau.leia.protocol.document.GetStoredValueResult;
import io.github.mfuerstenau.leia.protocol.document.GetTermVectorParams;
import io.github.mfuerstenau.leia.protocol.document.GetTermVectorResult;
import io.github.mfuerstenau.leia.protocol.document.StartReconstructParams;
import io.github.mfuerstenau.leia.protocol.filter.GetFilterResult;
import io.github.mfuerstenau.leia.protocol.filter.ReleaseFilterParams;
import io.github.mfuerstenau.leia.protocol.filter.ReleaseFilterResult;
import io.github.mfuerstenau.leia.protocol.filter.StartFilterParams;
import io.github.mfuerstenau.leia.protocol.filter.StepFilterParams;
import io.github.mfuerstenau.leia.protocol.filter.StepFilterResult;
import io.github.mfuerstenau.leia.protocol.index.CloseIndexParams;
import io.github.mfuerstenau.leia.protocol.index.CloseIndexResult;
import io.github.mfuerstenau.leia.protocol.index.GetIndexStatsParams;
import io.github.mfuerstenau.leia.protocol.index.GetIndexStatsResult;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexParams;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexResult;
import io.github.mfuerstenau.leia.protocol.index.ReopenIndexParams;
import io.github.mfuerstenau.leia.protocol.index.ReopenIndexResult;
import io.github.mfuerstenau.leia.protocol.jars.AddJarsParams;
import io.github.mfuerstenau.leia.protocol.jars.AddJarsResult;
import io.github.mfuerstenau.leia.protocol.jars.GetJarsParams;
import io.github.mfuerstenau.leia.protocol.jars.GetJarsResult;
import io.github.mfuerstenau.leia.protocol.jars.GetRuntimesParams;
import io.github.mfuerstenau.leia.protocol.jars.GetRuntimesResult;
import io.github.mfuerstenau.leia.protocol.jars.LoadRuntimesParams;
import io.github.mfuerstenau.leia.protocol.jars.LoadRuntimesResult;
import io.github.mfuerstenau.leia.protocol.job.CancelJobResult;
import io.github.mfuerstenau.leia.protocol.job.JobParams;
import io.github.mfuerstenau.leia.protocol.job.JobStarted;
import io.github.mfuerstenau.leia.protocol.knn.GetKnnGraphNodeParams;
import io.github.mfuerstenau.leia.protocol.knn.GetKnnGraphNodeResult;
import io.github.mfuerstenau.leia.protocol.knn.GetKnnGraphNodesParams;
import io.github.mfuerstenau.leia.protocol.knn.GetKnnGraphNodesResult;
import io.github.mfuerstenau.leia.protocol.knn.GetKnnGraphParams;
import io.github.mfuerstenau.leia.protocol.knn.GetKnnGraphResult;
import io.github.mfuerstenau.leia.protocol.knn.GetKnnRecallResult;
import io.github.mfuerstenau.leia.protocol.knn.StartKnnRecallParams;
import io.github.mfuerstenau.leia.protocol.points.GetPointCellParams;
import io.github.mfuerstenau.leia.protocol.points.GetPointCellResult;
import io.github.mfuerstenau.leia.protocol.points.GetPointTreeParams;
import io.github.mfuerstenau.leia.protocol.points.GetPointTreeResult;
import io.github.mfuerstenau.leia.protocol.search.GetExplainResult;
import io.github.mfuerstenau.leia.protocol.search.GetHighlightResult;
import io.github.mfuerstenau.leia.protocol.search.GetSearchResult;
import io.github.mfuerstenau.leia.protocol.search.GetStoredScanParams;
import io.github.mfuerstenau.leia.protocol.search.GetStoredScanResult;
import io.github.mfuerstenau.leia.protocol.search.StartExplainParams;
import io.github.mfuerstenau.leia.protocol.search.StartHighlightParams;
import io.github.mfuerstenau.leia.protocol.search.StartSearchParams;
import io.github.mfuerstenau.leia.protocol.search.StartStoredScanParams;
import io.github.mfuerstenau.leia.protocol.terms.GetExportTermsResult;
import io.github.mfuerstenau.leia.protocol.terms.GetFieldTermScanResult;
import io.github.mfuerstenau.leia.protocol.terms.GetPositionsParams;
import io.github.mfuerstenau.leia.protocol.terms.GetPositionsResult;
import io.github.mfuerstenau.leia.protocol.terms.GetPostingsParams;
import io.github.mfuerstenau.leia.protocol.terms.GetPostingsResult;
import io.github.mfuerstenau.leia.protocol.terms.GetTermsParams;
import io.github.mfuerstenau.leia.protocol.terms.GetTermsResult;
import io.github.mfuerstenau.leia.protocol.terms.StartExportTermsParams;
import io.github.mfuerstenau.leia.protocol.terms.StartFieldTermScanParams;
import io.github.mfuerstenau.leia.protocol.version.VersionParams;
import io.github.mfuerstenau.leia.protocol.version.VersionResult;
import java.util.List;
import java.util.Optional;

/** The registry of all methods. Each one needs a handler in leia-server (AGENTS.md, hard rule 3). */
public final class Methods {

    public static final RpcMethod<VersionParams, VersionResult> VERSION =
            new RpcMethod<>("version", VersionParams.class, VersionResult.class);

    /**
     * Opens the latest commit of an index, read-only, and makes it the open index. An index that was
     * open before is closed, and its jobs stopped, once the new one has opened; if opening fails, it
     * stays open. An index opens through the runtime that reads its version (ADR 0044, ADR 0071); with
     * none loaded, it fails with {@code MISSING_RUNTIME}.
     */
    public static final RpcMethod<OpenIndexParams, OpenIndexResult> OPEN_INDEX =
            new RpcMethod<>("openIndex", OpenIndexParams.class, OpenIndexResult.class);

    /**
     * Opens the latest commit of the open index again, if it differs from the open one (ADR 0019).
     * The segments that didn't change are shared. If it changed, the new commit becomes the open
     * index, and the jobs of the old one are stopped; if not, nothing happens. Fails with {@code
     * NO_INDEX_OPEN}, or with the errors of {@code openIndex}; the open index then stays open.
     */
    public static final RpcMethod<ReopenIndexParams, ReopenIndexResult> REOPEN_INDEX =
            new RpcMethod<>("reopenIndex", ReopenIndexParams.class, ReopenIndexResult.class);

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
     * Starts a job that rebuilds the terms and points a document was indexed with, per field (ADR
     * 0029, ADR 0041): the terms from its term vector where that holds all that the postings hold,
     * else from the postings of every term of the field in the document's segment; the points from
     * every point of the field in the segment. Fails with {@code NO_INDEX_OPEN}, {@code NO_READER},
     * {@code DOC_NOT_FOUND}, {@code FIELD_NOT_FOUND}, or {@code INVALID_PARAMS} for a field with
     * neither postings nor points.
     */
    public static final RpcMethod<StartReconstructParams, JobStarted> START_RECONSTRUCT =
            new RpcMethod<>("startReconstruct", StartReconstructParams.class, JobStarted.class);

    /**
     * The progress of a reconstruction, and its outcome once it is done (ADR 0029). The outcome is
     * handed out once; afterwards the job is gone ({@code JOB_NOT_FOUND}).
     */
    public static final RpcMethod<JobParams, GetReconstructResult> GET_RECONSTRUCT =
            new RpcMethod<>("getReconstruct", JobParams.class, GetReconstructResult.class);

    /**
     * The BKD tree of a point field (ADR 0041): its size and range over the index, and the root cell
     * of each segment that has points in it. Fails with {@code NO_INDEX_OPEN}, {@code NO_READER},
     * {@code FIELD_NOT_FOUND}, or {@code INVALID_PARAMS} for a field without points.
     */
    public static final RpcMethod<GetPointTreeParams, GetPointTreeResult> GET_POINT_TREE =
            new RpcMethod<>("getPointTree", GetPointTreeParams.class, GetPointTreeResult.class);

    /**
     * A cell of a point field's BKD tree in one segment (ADR 0041): its children, or, for a leaf, its
     * points. It walks the tree's index from the root to the cell and reads at most one leaf, so it is
     * no job. Fails with {@code NO_INDEX_OPEN}, {@code NO_READER}, {@code FIELD_NOT_FOUND}, or {@code
     * INVALID_PARAMS} for a field without points, a segment out of range or without points in the
     * field, or a path to no cell.
     */
    public static final RpcMethod<GetPointCellParams, GetPointCellResult> GET_POINT_CELL =
            new RpcMethod<>("getPointCell", GetPointCellParams.class, GetPointCellResult.class);

    /**
     * The HNSW graphs of a KNN vector field (ADR 0085): for each segment with vectors in it, the
     * nodes of each level of its graph and the entry node. Fails with {@code NO_INDEX_OPEN}, {@code
     * NO_READER}, {@code NOT_SUPPORTED}, {@code FIELD_NOT_FOUND}, {@code INDEX_NOT_READABLE}, or
     * {@code INVALID_PARAMS} for a field without vectors.
     */
    public static final RpcMethod<GetKnnGraphParams, GetKnnGraphResult> GET_KNN_GRAPH =
            new RpcMethod<>("getKnnGraph", GetKnnGraphParams.class, GetKnnGraphResult.class);

    /**
     * A page of the nodes of one level of a segment's HNSW graph (ADR 0085), in ordinal order. It reads
     * the node lists of the levels above 0, which are a fraction of the nodes, so it is no job. Fails
     * with {@code NO_INDEX_OPEN}, {@code NO_READER}, {@code NOT_SUPPORTED}, {@code FIELD_NOT_FOUND},
     * {@code INDEX_NOT_READABLE}, or {@code INVALID_PARAMS} for a field without vectors, a segment out
     * of range or without a graph of the field, a level out of range, or an offset or limit out of
     * range.
     */
    public static final RpcMethod<GetKnnGraphNodesParams, GetKnnGraphNodesResult> GET_KNN_GRAPH_NODES =
            new RpcMethod<>("getKnnGraphNodes", GetKnnGraphNodesParams.class, GetKnnGraphNodesResult.class);

    /**
     * A node of a segment's HNSW graph with its neighbours on each level it is on, scored by the
     * field's similarity (ADR 0085). Fails with {@code NO_INDEX_OPEN}, {@code NO_READER}, {@code
     * NOT_SUPPORTED}, {@code FIELD_NOT_FOUND}, {@code DOC_NOT_FOUND}, {@code INDEX_NOT_READABLE}, or
     * {@code INVALID_PARAMS} for a field without vectors, neither a document nor a segment and
     * ordinal, a segment out of range or without a graph of the field, an ordinal out of range, or a
     * document without a vector in the field.
     */
    public static final RpcMethod<GetKnnGraphNodeParams, GetKnnGraphNodeResult> GET_KNN_GRAPH_NODE =
            new RpcMethod<>("getKnnGraphNode", GetKnnGraphNodeParams.class, GetKnnGraphNodeResult.class);

    /**
     * Checks the recall of approximate KNN search against exact in a job (ADR 0086): for the
     * query's target, or for a sample of the field's documents, the approximate top k against the
     * exact top k, through the same search as {@code startSearch}. Fails with {@code NO_INDEX_OPEN},
     * {@code NO_READER}, {@code NOT_SUPPORTED}, {@code FIELD_NOT_FOUND}, {@code DOC_NOT_FOUND}, {@code
     * INVALID_QUERY} for a filter that doesn't parse, or {@code INVALID_PARAMS} for an exact query, a
     * missing or out-of-range {@code k}, a target that doesn't fit the field, a sample with a target,
     * no target without a sample, or a sample out of range.
     */
    public static final RpcMethod<StartKnnRecallParams, JobStarted> START_KNN_RECALL =
            new RpcMethod<>("startKnnRecall", StartKnnRecallParams.class, JobStarted.class);

    /**
     * The progress of a recall check, and its outcome once it is done (ADR 0086). The outcome is
     * handed out once; afterwards the job is gone ({@code JOB_NOT_FOUND}).
     */
    public static final RpcMethod<JobParams, GetKnnRecallResult> GET_KNN_RECALL =
            new RpcMethod<>("getKnnRecall", JobParams.class, GetKnnRecallResult.class);

    /**
     * A page of the terms of a field, in term order, from a given term on (ADR 0007). A field without
     * postings has none. Fails with {@code NO_INDEX_OPEN}, {@code FIELD_NOT_FOUND}, or {@code
     * INVALID_PARAMS} for a {@code limit} out of range or a {@code from} that is not valid hex.
     */
    public static final RpcMethod<GetTermsParams, GetTermsResult> GET_TERMS =
            new RpcMethod<>("getTerms", GetTermsParams.class, GetTermsResult.class);

    /**
     * A page of the postings of a term: the documents that contain it, from a given document id on,
     * deleted documents included (ADR 0007), marked with a filter or narrowed to it (ADR 0032).
     * Fails with {@code NO_INDEX_OPEN}, {@code FIELD_NOT_FOUND}, {@code TERM_NOT_FOUND}, {@code
     * FILTER_NOT_FOUND}, or {@code INVALID_PARAMS} for a {@code limit} out of range, a negative
     * {@code fromDoc} or a term that is not valid hex.
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
     * {@code segments_N} of that generation, {@code MISSING_CODEC} if it names a codec that isn't
     * loaded, or {@code UNSUPPORTED_INDEX_FORMAT}, {@code CORRUPT_INDEX} or {@code INDEX_NOT_READABLE}
     * if the commit can't be read.
     */
    public static final RpcMethod<GetCommitParams, GetCommitResult> GET_COMMIT =
            new RpcMethod<>("getCommit", GetCommitParams.class, GetCommitResult.class);

    /**
     * Starts a job that searches the open index and returns one page of hits with their stored fields
     * (ADR 0010, ADR 0011, ADR 0012, ADR 0013, ADR 0014). Fails with {@code NO_INDEX_OPEN}, {@code
     * INVALID_QUERY} if the expression doesn't parse, {@code INVALID_ANALYZER} if the query's analyzer
     * can't be built, {@code FIELD_NOT_FOUND} for an unknown default field, term
     * field, point field, More-Like-This field, sort field or field to send, {@code DOC_NOT_FOUND} for
     * a More-Like-This document outside 0 to maxDoc - 1, or {@code INVALID_PARAMS} for a page out of
     * range, a query, analyzer or similarity without exactly one alternative, a parser option, More-Like-This
     * setting or BM25 parameter out of range, a point type that doesn't fit its field, a
     * More-Like-This field that isn't indexed, or a sort field without doc values of the sort type.
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
     * Starts a job that explains how a document scores for a query with a similarity, or why it
     * doesn't match (ADR 0010, ADR 0012, ADR 0013, ADR 0014). Fails as {@code startSearch} does, and with {@code
     * DOC_NOT_FOUND} for an id outside 0 to maxDoc - 1.
     */
    public static final RpcMethod<StartExplainParams, JobStarted> START_EXPLAIN =
            new RpcMethod<>("startExplain", StartExplainParams.class, JobStarted.class);

    /**
     * The outcome of an explanation once it is done, handed out once as for {@code getSearch}.
     */
    public static final RpcMethod<JobParams, GetExplainResult> GET_EXPLAIN =
            new RpcMethod<>("getExplain", JobParams.class, GetExplainResult.class);

    /**
     * Starts a job that finds the matches of a query in every stored value of one field of a document,
     * or of all its stored fields (ADR 0042, ADR 0043), for the viewer and the documents screen to mark
     * them. Fails as {@code startSearch} does, with {@code DOC_NOT_FOUND} for an id outside 0 to maxDoc
     * - 1, {@code FIELD_NOT_FOUND} for an unknown named field,
     * or {@code INVALID_ANALYZER} if the highlight analyzer can't be built.
     */
    public static final RpcMethod<StartHighlightParams, JobStarted> START_HIGHLIGHT =
            new RpcMethod<>("startHighlight", StartHighlightParams.class, JobStarted.class);

    /**
     * The outcome of a highlight once it is done, handed out once as for {@code getSearch}.
     */
    public static final RpcMethod<JobParams, GetHighlightResult> GET_HIGHLIGHT =
            new RpcMethod<>("getHighlight", JobParams.class, GetHighlightResult.class);

    /**
     * Starts a job that looks for a regular expression in the stored values of every document of the
     * open index (ADR 0027): text values, and binary values that are UTF-8. Fails with {@code
     * NO_INDEX_OPEN}, {@code NO_READER}, {@code INVALID_QUERY} if the pattern doesn't compile, or
     * {@code FIELD_NOT_FOUND} for an unknown field.
     */
    public static final RpcMethod<StartStoredScanParams, JobStarted> START_STORED_SCAN =
            new RpcMethod<>("startStoredScan", StartStoredScanParams.class, JobStarted.class);

    /**
     * The hits of a scan of the stored values from a given hit on, and its outcome once it is done
     * (ADR 0027). The outcome is handed out once, with the last hits; afterwards the job is gone
     * ({@code JOB_NOT_FOUND}). Fails with {@code INVALID_PARAMS} for a negative {@code fromHit}.
     */
    public static final RpcMethod<GetStoredScanParams, GetStoredScanResult> GET_STORED_SCAN =
            new RpcMethod<>("getStoredScan", GetStoredScanParams.class, GetStoredScanResult.class);

    /**
     * Starts a job that finds every document of the open index that matches a query, without scoring,
     * to browse the documents through (ADR 0031). Fails as {@code startSearch} does for the query.
     */
    public static final RpcMethod<StartFilterParams, JobStarted> START_FILTER =
            new RpcMethod<>("startFilter", StartFilterParams.class, JobStarted.class);

    /**
     * The progress of building a filter, and the filter once it is built (ADR 0031). The server
     * keeps the filter for {@code stepFilter}, in place of the one it kept before; the job is gone
     * as for every job ({@code JOB_NOT_FOUND}). A search that failed answers with its error ({@code
     * INVALID_QUERY} for too many clauses).
     */
    public static final RpcMethod<JobParams, GetFilterResult> GET_FILTER =
            new RpcMethod<>("getFilter", JobParams.class, GetFilterResult.class);

    /**
     * The first document of a filter from a document id on, or the last one up to it, and how many
     * matches come before it (ADR 0031). Fails with {@code FILTER_NOT_FOUND} if the server doesn't
     * keep the filter: it was released or replaced, or its index was closed or reopened.
     */
    public static final RpcMethod<StepFilterParams, StepFilterResult> STEP_FILTER =
            new RpcMethod<>("stepFilter", StepFilterParams.class, StepFilterResult.class);

    /**
     * Forgets a filter and frees its memory (ADR 0031). An unknown filter is not an error; the result
     * says whether the server kept it.
     */
    public static final RpcMethod<ReleaseFilterParams, ReleaseFilterResult> RELEASE_FILTER =
            new RpcMethod<>("releaseFilter", ReleaseFilterParams.class, ReleaseFilterResult.class);

    /**
     * The char filters, tokenizers and token filters that a custom analyzer can be built from (ADR
     * 0014). Needs no open index.
     */
    public static final RpcMethod<GetAnalysisComponentsParams, GetAnalysisComponentsResult> GET_ANALYSIS_COMPONENTS =
            new RpcMethod<>(
                    "getAnalysisComponents", GetAnalysisComponentsParams.class, GetAnalysisComponentsResult.class);

    /**
     * What an analyzer makes of a text: the text after each char filter, and the tokens with their
     * attributes after the tokenizer and each token filter (ADR 0014), for a field if one is given
     * (ADR 0039). Needs no open index. Fails with {@code INVALID_ANALYZER} if the analyzer can't be
     * built, or {@code INVALID_PARAMS} for an analyzer without exactly one alternative, with fields
     * named twice or nested, or a text longer than {@code AnalyzeParams.MAX_TEXT_LENGTH}.
     */
    public static final RpcMethod<AnalyzeParams, AnalyzeResult> ANALYZE =
            new RpcMethod<>("analyze", AnalyzeParams.class, AnalyzeResult.class);

    /**
     * Starts a job that finds the stop words of fields of the open index as Lucene's {@code
     * QueryAutoStopWordAnalyzer} does: the terms in more documents than a threshold (ADR 0039). An
     * {@code Analyzer} then carries them in {@code autoStopWords}. Fails with {@code NO_INDEX_OPEN},
     * {@code NO_READER}, {@code FIELD_NOT_FOUND} for a field that is missing or not indexed, or {@code
     * INVALID_PARAMS} unless exactly one threshold is given, in range.
     */
    public static final RpcMethod<StartAutoStopWordsParams, JobStarted> START_AUTO_STOP_WORDS =
            new RpcMethod<>("startAutoStopWords", StartAutoStopWordsParams.class, JobStarted.class);

    /**
     * The progress of a search for stop words, and its outcome once it is done. A field with more
     * than {@code StartAutoStopWordsParams.MAX_WORDS} words fails the job with {@code INVALID_PARAMS}.
     */
    public static final RpcMethod<JobParams, GetAutoStopWordsResult> GET_AUTO_STOP_WORDS =
            new RpcMethod<>("getAutoStopWords", JobParams.class, GetAutoStopWordsResult.class);

    /**
     * The indexes of the open path with the segments of their latest commit, which {@code
     * startCheckIndex} can be told to check (ADR 0093): the opened indexes, then the skipped ones,
     * read from the directories on every call, with or without a reader. An index whose commit can't
     * be listed has an {@code error} instead of segments. Fails with {@code NO_INDEX_OPEN}.
     */
    public static final RpcMethod<GetCheckTargetsParams, GetCheckTargetsResult> GET_CHECK_TARGETS =
            new RpcMethod<>("getCheckTargets", GetCheckTargetsParams.class, GetCheckTargetsResult.class);

    /**
     * Starts a job that checks the indexes of the open path with Lucene's {@code CheckIndex}, with or
     * without a reader (ADR 0022): every one, or those of {@code only} (ADR 0093), the opened indexes
     * first, then the skipped ones. Nothing is written. Fails with {@code NO_INDEX_OPEN}, or {@code
     * INVALID_PARAMS} for a level out of range, {@code threads} outside 1 to the server's processors,
     * an {@code only} path that isn't an index of the open path, a path or a segment twice, or an
     * empty list of segments.
     */
    public static final RpcMethod<StartCheckIndexParams, JobStarted> START_CHECK_INDEX =
            new RpcMethod<>("startCheckIndex", StartCheckIndexParams.class, JobStarted.class);

    /**
     * The output of a check from a given line on, and its outcome once it is done (ADR 0022). The
     * outcome is handed out once, with the last lines; afterwards the job is gone ({@code
     * JOB_NOT_FOUND}). Fails with {@code INVALID_PARAMS} for a negative {@code fromLine}.
     */
    public static final RpcMethod<GetCheckIndexParams, GetCheckIndexResult> GET_CHECK_INDEX =
            new RpcMethod<>("getCheckIndex", GetCheckIndexParams.class, GetCheckIndexResult.class);

    /**
     * Starts a job that writes the terms of a field of the open index to a file, one {@code
     * term<delimiter>docFreq} per line (ADR 0022). Fails with {@code NO_INDEX_OPEN}, {@code
     * NO_READER}, {@code FIELD_NOT_FOUND}, {@code INVALID_PARAMS} for a path that isn't absolute, or
     * {@code OUTPUT_NOT_WRITABLE} for a file in an index directory, in a directory that doesn't
     * exist, or that exists without {@code overwrite}.
     */
    public static final RpcMethod<StartExportTermsParams, JobStarted> START_EXPORT_TERMS =
            new RpcMethod<>("startExportTerms", StartExportTermsParams.class, JobStarted.class);

    /**
     * The progress of an export of terms, and its outcome once it is done. The outcome is handed out
     * once: an export that failed answers with its error ({@code OUTPUT_NOT_WRITABLE} if writing
     * failed), and afterwards the job is gone ({@code JOB_NOT_FOUND}).
     */
    public static final RpcMethod<JobParams, GetExportTermsResult> GET_EXPORT_TERMS =
            new RpcMethod<>("getExportTerms", JobParams.class, GetExportTermsResult.class);

    /**
     * Loads more jars with analysis components, codecs or formats, as {@code --jar} does at startup,
     * and says what they added (ADR 0024). A jar that is already loaded isn't loaded again. Needs no
     * open index. Fails with {@code INVALID_PARAMS} for no paths, or {@code INVALID_JAR} if a path is
     * neither a jar nor a directory with jars, or the jars' factories, codecs or formats can't be
     * loaded; nothing is loaded then.
     */
    public static final RpcMethod<AddJarsParams, AddJarsResult> ADD_JARS =
            new RpcMethod<>("addJars", AddJarsParams.class, AddJarsResult.class);

    /** The loaded jars, in the order they were loaded (ADR 0024). Needs no open index. */
    public static final RpcMethod<GetJarsParams, GetJarsResult> GET_JARS =
            new RpcMethod<>("getJars", GetJarsParams.class, GetJarsResult.class);

    /**
     * Every runtime leia knows, newest first, with the version of Lucene of the loaded runtime that
     * reads its indexes, or why none is loaded: its folder in {@code runtimes/} missing, incomplete or
     * refused (ADR 0072, ADR 0073). Needs no open index.
     */
    public static final RpcMethod<GetRuntimesParams, GetRuntimesResult> GET_RUNTIMES =
            new RpcMethod<>("getRuntimes", GetRuntimesParams.class, GetRuntimesResult.class);

    /**
     * Loads the folders of {@code runtimes/} whose runtime isn't loaded yet, such as those just
     * downloaded, and says which it loaded and every runtime's state afterwards (ADR 0080). Lucene 10's
     * folder loads only while no jars are loaded; otherwise its state is {@code RESTART}. Needs no
     * open index; an open index keeps the runtime it was opened with.
     */
    public static final RpcMethod<LoadRuntimesParams, LoadRuntimesResult> LOAD_RUNTIMES =
            new RpcMethod<>("loadRuntimes", LoadRuntimesParams.class, LoadRuntimesResult.class);

    public static final List<RpcMethod<?, ?>> ALL = List.of(
            VERSION,
            OPEN_INDEX,
            REOPEN_INDEX,
            GET_INDEX_STATS,
            CLOSE_INDEX,
            START_FIELD_TERM_SCAN,
            GET_FIELD_TERM_SCAN,
            CANCEL_JOB,
            GET_DOCUMENT,
            GET_STORED_VALUE,
            GET_TERM_VECTOR,
            START_RECONSTRUCT,
            GET_RECONSTRUCT,
            GET_TERMS,
            GET_POSTINGS,
            GET_POSITIONS,
            GET_POINT_TREE,
            GET_POINT_CELL,
            GET_KNN_GRAPH,
            GET_KNN_GRAPH_NODES,
            GET_KNN_GRAPH_NODE,
            START_KNN_RECALL,
            GET_KNN_RECALL,
            GET_COMMITS,
            GET_COMMIT,
            START_SEARCH,
            GET_SEARCH,
            START_EXPLAIN,
            GET_EXPLAIN,
            START_HIGHLIGHT,
            GET_HIGHLIGHT,
            START_STORED_SCAN,
            GET_STORED_SCAN,
            START_FILTER,
            GET_FILTER,
            STEP_FILTER,
            RELEASE_FILTER,
            GET_ANALYSIS_COMPONENTS,
            ANALYZE,
            START_AUTO_STOP_WORDS,
            GET_AUTO_STOP_WORDS,
            GET_CHECK_TARGETS,
            START_CHECK_INDEX,
            GET_CHECK_INDEX,
            START_EXPORT_TERMS,
            GET_EXPORT_TERMS,
            ADD_JARS,
            GET_JARS,
            GET_RUNTIMES,
            LOAD_RUNTIMES);

    private Methods() {}

    public static Optional<RpcMethod<?, ?>> byName(String name) {
        return ALL.stream().filter(method -> method.name().equals(name)).findFirst();
    }
}
