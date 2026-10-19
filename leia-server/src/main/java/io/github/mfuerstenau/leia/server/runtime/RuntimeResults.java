package io.github.mfuerstenau.leia.server.runtime;

import io.github.mfuerstenau.leia.core.AutoStopWords;
import io.github.mfuerstenau.leia.core.DocFilter;
import io.github.mfuerstenau.leia.core.IndexDocument;
import io.github.mfuerstenau.leia.core.PostingsPage;
import io.github.mfuerstenau.leia.core.ScanProgress;
import io.github.mfuerstenau.leia.core.StoredScan;
import io.github.mfuerstenau.leia.core.TermExport;
import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.analysis.StartAutoStopWordsParams;
import io.github.mfuerstenau.leia.protocol.check.IndexCheck;
import io.github.mfuerstenau.leia.protocol.check.SegmentCheck;
import io.github.mfuerstenau.leia.protocol.commits.CodecFormats;
import io.github.mfuerstenau.leia.protocol.commits.Commit;
import io.github.mfuerstenau.leia.protocol.commits.CommitFile;
import io.github.mfuerstenau.leia.protocol.commits.FieldFormats;
import io.github.mfuerstenau.leia.protocol.commits.GetCommitResult;
import io.github.mfuerstenau.leia.protocol.commits.GetCommitsResult;
import io.github.mfuerstenau.leia.protocol.commits.Segment;
import io.github.mfuerstenau.leia.protocol.commits.UnreadableCommit;
import io.github.mfuerstenau.leia.protocol.document.BytesValue;
import io.github.mfuerstenau.leia.protocol.document.DocValues;
import io.github.mfuerstenau.leia.protocol.document.DocumentField;
import io.github.mfuerstenau.leia.protocol.document.GetDocumentParams;
import io.github.mfuerstenau.leia.protocol.document.GetDocumentResult;
import io.github.mfuerstenau.leia.protocol.document.GetStoredValueParams;
import io.github.mfuerstenau.leia.protocol.document.GetStoredValueResult;
import io.github.mfuerstenau.leia.protocol.document.GetTermVectorParams;
import io.github.mfuerstenau.leia.protocol.document.GetTermVectorResult;
import io.github.mfuerstenau.leia.protocol.document.KnnVector;
import io.github.mfuerstenau.leia.protocol.document.ReconstructResult;
import io.github.mfuerstenau.leia.protocol.document.ReconstructSource;
import io.github.mfuerstenau.leia.protocol.document.ReconstructedField;
import io.github.mfuerstenau.leia.protocol.document.StartReconstructParams;
import io.github.mfuerstenau.leia.protocol.document.StoredValue;
import io.github.mfuerstenau.leia.protocol.document.TermPosition;
import io.github.mfuerstenau.leia.protocol.document.TermVectorTerm;
import io.github.mfuerstenau.leia.protocol.filter.FilterPattern;
import io.github.mfuerstenau.leia.protocol.filter.StartFilterParams;
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
import io.github.mfuerstenau.leia.protocol.index.ReaderSegment;
import io.github.mfuerstenau.leia.protocol.index.ReopenIndexResult;
import io.github.mfuerstenau.leia.protocol.index.SkippedIndex;
import io.github.mfuerstenau.leia.protocol.index.TermStats;
import io.github.mfuerstenau.leia.protocol.index.VectorEncoding;
import io.github.mfuerstenau.leia.protocol.index.VectorSimilarity;
import io.github.mfuerstenau.leia.protocol.job.JobStarted;
import io.github.mfuerstenau.leia.protocol.points.GetPointCellParams;
import io.github.mfuerstenau.leia.protocol.points.GetPointCellResult;
import io.github.mfuerstenau.leia.protocol.points.GetPointTreeParams;
import io.github.mfuerstenau.leia.protocol.points.GetPointTreeResult;
import io.github.mfuerstenau.leia.protocol.points.LeafPoint;
import io.github.mfuerstenau.leia.protocol.points.PointCell;
import io.github.mfuerstenau.leia.protocol.points.PointSegment;
import io.github.mfuerstenau.leia.protocol.search.ClassicQuery;
import io.github.mfuerstenau.leia.protocol.search.Explanation;
import io.github.mfuerstenau.leia.protocol.search.Hit;
import io.github.mfuerstenau.leia.protocol.search.HitField;
import io.github.mfuerstenau.leia.protocol.search.KnnQuery;
import io.github.mfuerstenau.leia.protocol.search.QueryOperator;
import io.github.mfuerstenau.leia.protocol.search.SearchQuery;
import io.github.mfuerstenau.leia.protocol.search.SearchResults;
import io.github.mfuerstenau.leia.protocol.search.SortKey;
import io.github.mfuerstenau.leia.protocol.search.StartExplainParams;
import io.github.mfuerstenau.leia.protocol.search.StartSearchParams;
import io.github.mfuerstenau.leia.protocol.search.TermQuery;
import io.github.mfuerstenau.leia.protocol.search.TotalHitsRelation;
import io.github.mfuerstenau.leia.protocol.terms.FieldTerms;
import io.github.mfuerstenau.leia.protocol.terms.GetPositionsParams;
import io.github.mfuerstenau.leia.protocol.terms.GetPositionsResult;
import io.github.mfuerstenau.leia.protocol.terms.GetPostingsParams;
import io.github.mfuerstenau.leia.protocol.terms.GetPostingsResult;
import io.github.mfuerstenau.leia.protocol.terms.GetTermsParams;
import io.github.mfuerstenau.leia.protocol.terms.GetTermsResult;
import io.github.mfuerstenau.leia.protocol.terms.Posting;
import io.github.mfuerstenau.leia.protocol.terms.TermEntry;
import io.github.mfuerstenau.leia.protocol.terms.TopTerm;
import io.github.mfuerstenau.leia.runtime.Check;
import io.github.mfuerstenau.leia.runtime.Commits;
import io.github.mfuerstenau.leia.runtime.DirectoryKind;
import io.github.mfuerstenau.leia.runtime.Documents;
import io.github.mfuerstenau.leia.runtime.Knn;
import io.github.mfuerstenau.leia.runtime.LuceneRuntime;
import io.github.mfuerstenau.leia.runtime.Points;
import io.github.mfuerstenau.leia.runtime.Rebuild;
import io.github.mfuerstenau.leia.runtime.RuntimeIndex;
import io.github.mfuerstenau.leia.runtime.Search;
import io.github.mfuerstenau.leia.runtime.Terms;
import io.github.mfuerstenau.leia.server.ByteStrings;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.analysis.StartAutoStopWordsHandler;
import io.github.mfuerstenau.leia.server.document.GetDocumentHandler;
import io.github.mfuerstenau.leia.server.document.StartReconstructHandler;
import io.github.mfuerstenau.leia.server.index.CurrentIndex;
import io.github.mfuerstenau.leia.server.index.IndexDirectories;
import io.github.mfuerstenau.leia.server.job.Jobs;
import io.github.mfuerstenau.leia.server.points.PointMapper;
import io.github.mfuerstenau.leia.server.search.KnnChecks;
import io.github.mfuerstenau.leia.server.search.StartStoredScanHandler;
import io.github.mfuerstenau.leia.server.terms.TermParams;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.util.BytesRef;

/**
 * The methods that work on an index that a runtime of {@code leia-runtime} reads, such as one written
 * by Lucene 2.x or 3.x (ADR 0044): each maps the runtime's records to the protocol's. The handlers of
 * these methods call them when {@link CurrentIndex#runtime()} is present; every other method refuses
 * with {@code NOT_SUPPORTED}, and so do these when the runtime lacks their group.
 */
public final class RuntimeResults {

    private static final Logger LOG = Logger.getLogger(RuntimeResults.class.getName());

    private RuntimeResults() {}

    /**
     * What works on {@code open}: the statistics, the features of the groups its index has, and the
     * commits and the check if its runtime has them (ADR 0045, ADR 0046, ADR 0048): doc values with
     * the documents and points and KNN vectors if a field has them (ADR 0049), and the check's levels
     * if the runtime's check takes them. Reopen works on every runtime (ADR 0060), and auto stop
     * words wherever there are terms (ADR 0061).
     */
    public static Set<Feature> features(OpenRuntime open) {
        RuntimeIndex index = open.index();
        // Every runtime reopens (ADR 0060).
        Set<Feature> features = EnumSet.of(Feature.INDEX_STATS, Feature.REOPEN);
        if (index.documents().isPresent()) {
            features.addAll(
                    List.of(Feature.DOCUMENTS, Feature.STORED_VALUES, Feature.TERM_VECTORS, Feature.STORED_SCAN));
            if (index.fields().stream().anyMatch(field -> field.docValuesType() != RuntimeIndex.DocValuesType.NONE)) {
                features.add(Feature.DOC_VALUES);
            }
        }
        if (index.terms().isPresent()) {
            features.addAll(List.of(
                    Feature.TERMS,
                    Feature.POSTINGS,
                    Feature.TOP_TERMS,
                    Feature.POSITIONS,
                    Feature.EXPORT_TERMS,
                    Feature.AUTO_STOP_WORDS));
        }
        if (index.search().isPresent()) {
            features.addAll(List.of(Feature.CLASSIC_SEARCH, Feature.TERM_SEARCH, Feature.FILTER, Feature.EXPLAIN));
        }
        if (index.points().isPresent() && index.fields().stream().anyMatch(field -> field.pointDimensionCount() > 0)) {
            features.add(Feature.POINTS);
        }
        if (index.rebuild().isPresent()) {
            features.add(Feature.RECONSTRUCT);
        }
        if (index.knn().isPresent()
                && index.fields().stream().anyMatch(field -> field.vectors().isPresent())) {
            features.add(Feature.KNN_VECTORS);
        }
        if (open.runtime().commits().isPresent()) {
            features.add(Feature.COMMITS);
        }
        if (open.runtime().check().isPresent()) {
            features.add(Feature.CHECK_INDEX);
            if (open.runtime().check().get().hasLevels()) {
                features.add(Feature.CHECK_LEVELS);
            }
        }
        return features;
    }

    /** {@code openIndex}: the result for {@code open}, just opened. */
    public static OpenIndexResult opened(OpenRuntime open) throws RpcException {
        RuntimeIndex.Stats stats = stats(open.index());
        return new OpenIndexResult(
                open.path().toString(),
                OpenMode.READER,
                OptionalInt.of(stats.numDocs()),
                OptionalInt.of(stats.maxDoc()),
                entries(stats),
                skipped(open),
                features(open),
                open.runtime().luceneVersion());
    }

    /**
     * {@code reopenIndex}: the result for {@code open}, reopened if {@code changed}, else as it was
     * (ADR 0060).
     */
    public static ReopenIndexResult reopened(boolean changed, OpenRuntime open) throws RpcException {
        RuntimeIndex.Stats stats = stats(open.index());
        return new ReopenIndexResult(
                changed,
                open.path().toString(),
                OpenMode.READER,
                OptionalInt.of(stats.numDocs()),
                OptionalInt.of(stats.maxDoc()),
                entries(stats),
                skipped(open),
                features(open));
    }

    private static List<IndexEntry> entries(RuntimeIndex.Stats stats) {
        return stats.indexes().stream()
                .map(index -> new IndexEntry(
                        index.path().toString(),
                        Optional.of(new ReaderInfo(
                                index.docBase(),
                                index.segmentsFile(),
                                index.numDocs(),
                                index.maxDoc(),
                                index.createdMajor(),
                                index.oldestSegmentVersion()))))
                .toList();
    }

    private static List<SkippedIndex> skipped(OpenRuntime open) {
        return open.skipped().stream()
                .map(skipped -> new SkippedIndex(skipped.path().toString(), skipped.message()))
                .toList();
    }

    /** What the log says about {@code open}. */
    public static String describe(OpenRuntime open) {
        String version = open.runtime().luceneVersion();
        try {
            RuntimeIndex.Stats stats = open.index().stats();
            if (stats.indexes().size() == 1 && open.skipped().isEmpty()) {
                RuntimeIndex.IndexStats only = stats.indexes().getFirst();
                return only.segmentsFile() + ", " + only.maxDoc() + " docs, segments by Lucene "
                        + String.join(", ", only.segmentVersions()) + ", read with Lucene " + version;
            }
            return stats.indexes().size() + " indexes, " + stats.maxDoc() + " docs, "
                    + open.skipped().size() + " skipped, read with Lucene " + version;
        } catch (IOException e) {
            return "read with Lucene " + version;
        }
    }

    private static RuntimeIndex.Stats stats(RuntimeIndex index) throws RpcException {
        try {
            return index.stats();
        } catch (IOException e) {
            throw new RpcException(
                    ErrorCode.INDEX_NOT_READABLE, "Cannot read the index statistics: " + e.getMessage(), e);
        }
    }

    /** {@code getIndexStats}. A runtime that records no term statistics per field leaves them absent. */
    public static GetIndexStatsResult indexStats(OpenRuntime open) throws RpcException {
        RuntimeIndex index = open.index();
        RuntimeIndex.Stats stats = stats(index);
        List<IndexCommitStats> commits = stats.indexes().stream()
                .map(commit -> new IndexCommitStats(
                        commit.path().toString(),
                        commit.docBase(),
                        commit.segmentsFile(),
                        commit.generation(),
                        commit.version(),
                        commit.segmentVersions().size(),
                        commit.numDocs(),
                        commit.maxDoc(),
                        // How the norms are encoded (ADR 0048).
                        commit.createdMajor(),
                        // Before 5.3, the newest segment stands in for the version that wrote the commit.
                        commit.commitVersion(open.runtime().luceneVersion()),
                        commit.oldestSegmentVersion(),
                        Map.copyOf(commit.userData()),
                        commit.segments().stream()
                                .map(segment -> new ReaderSegment(
                                        segment.name(),
                                        commit.docBase() + segment.docBase(),
                                        segment.maxDoc(),
                                        segment.numDocs()))
                                .toList()))
                .toList();
        return new GetIndexStatsResult(
                open.path().toString(),
                index.directory().name(),
                commits.stream().mapToInt(IndexCommitStats::segmentCount).sum(),
                stats.numDocs(),
                stats.maxDoc(),
                commits,
                stats.fields().stream().map(RuntimeResults::field).toList());
    }

    /**
     * {@code getCommits} of a directory that {@code runtime} reads, read anew (ADR 0045). The commit
     * opened in {@code dir}, if any, is the current one.
     */
    public static GetCommitsResult commits(LuceneRuntime runtime, IndexDirectories.IndexDirectory dir)
            throws RpcException {
        Commits commits = runtime.commits().orElseThrow(() -> OpenRuntime.notSupported(runtime));
        Commits.CommitList list;
        try {
            list = commits.commits(dir.path(), dir.kind());
        } catch (IOException e) {
            throw new RpcException(ErrorCode.INDEX_NOT_READABLE, "Cannot list the commits: " + e.getMessage(), e);
        }
        list.unreadable()
                .forEach(unreadable -> LOG.warning(() ->
                        "Skipped the unreadable commit " + unreadable.segmentsFile() + ": " + unreadable.message()));
        return new GetCommitsResult(
                list.commits().stream().map(commit -> commit(commit, dir)).toList(),
                list.unreadable().stream()
                        .map(unreadable -> new UnreadableCommit(unreadable.segmentsFile(), unreadable.message()))
                        .toList());
    }

    /** {@code getCommit} of a directory that {@code runtime} reads, read anew (ADR 0045). */
    public static GetCommitResult commit(LuceneRuntime runtime, IndexDirectories.IndexDirectory dir, long generation)
            throws RpcException {
        Commits commits = runtime.commits().orElseThrow(() -> OpenRuntime.notSupported(runtime));
        Commits.CommitDetails details;
        try {
            details = commits.commit(dir.path(), dir.kind(), generation)
                    .orElseThrow(() -> new RpcException(
                            ErrorCode.COMMIT_NOT_FOUND, "No commit of generation " + generation + " in the index"));
        } catch (IOException | RuntimeException e) {
            throw new RpcException(
                    ErrorCode.INDEX_NOT_READABLE,
                    "Cannot read the commit of generation " + generation + ": " + e.getMessage(),
                    e);
        }
        return new GetCommitResult(
                commit(details.commit(), dir),
                details.segments().stream().map(RuntimeResults::segment).toList(),
                details.files().stream()
                        .map(file -> new CommitFile(file.name(), file.segment(), file.size()))
                        .toList());
    }

    private static Commit commit(Commits.Commit commit, IndexDirectories.IndexDirectory dir) {
        return new Commit(
                commit.generation(),
                commit.segmentsFile(),
                dir.generation().isPresent() && dir.generation().getAsLong() == commit.generation(),
                commit.version(),
                commit.segmentCount(),
                commit.maxDoc(),
                commit.delCount(),
                commit.luceneVersion(),
                Map.copyOf(commit.userData()));
    }

    /**
     * A segment of a runtime: before Lucene 4 without id, codec or formats (ADR 0045), before 6.2
     * without an index sort, before 7.0 without a minimum version, before 7.4 without soft deletes
     * (ADR 0048), and never with document blocks, which came with 9.9.
     */
    private static Segment segment(Commits.Segment segment) {
        return new Segment(
                segment.name(),
                segment.id(),
                segment.maxDoc(),
                segment.docBase(),
                segment.delCount(),
                segment.softDelCount(),
                segment.delGen(),
                segment.fieldInfosGen(),
                segment.docValuesGen(),
                segment.luceneVersion(),
                segment.minLuceneVersion(),
                segment.codec(),
                segment.formats()
                        .map(formats -> new CodecFormats(
                                formats.postings(),
                                formats.docValues(),
                                formats.storedFields(),
                                formats.termVectors(),
                                formats.norms(),
                                formats.points(),
                                formats.knnVectors(),
                                formats.fieldInfos(),
                                formats.segmentInfo(),
                                formats.liveDocs(),
                                formats.compound())),
                segment.fieldFormats().stream()
                        .map(field -> new FieldFormats(
                                field.field(), field.postings(), field.docValues(), field.knnVectors()))
                        .toList(),
                segment.fieldInfosError(),
                segment.compound(),
                false,
                segment.indexSort(),
                segment.sizeInBytes(),
                Map.copyOf(segment.diagnostics()),
                Map.copyOf(segment.attributes()));
    }

    private static IndexField field(RuntimeIndex.Field field) {
        return new IndexField(
                field.name(),
                field.number(),
                indexOptions(field.indexOptions()),
                field.hasNorms(),
                field.hasPayloads(),
                field.hasTermVectors(),
                docValuesType(field.docValuesType()),
                field.pointDimensionCount(),
                field.pointNumBytes(),
                field.vectors().map(RuntimeIndex.VectorFormat::dimension).orElse(0),
                field.vectors().map(vectors -> vectorEncoding(vectors.encoding())),
                field.vectors().map(vectors -> vectorSimilarity(vectors.similarity())),
                field.terms()
                        .map(terms -> new TermStats(terms.docCount(), terms.sumDocFreq(), terms.sumTotalTermFreq())));
    }

    private static IndexOptions indexOptions(RuntimeIndex.IndexOptions options) {
        return switch (options) {
            case NONE -> IndexOptions.NONE;
            case DOCS -> IndexOptions.DOCS;
            case DOCS_AND_FREQS -> IndexOptions.DOCS_AND_FREQS;
            case DOCS_AND_FREQS_AND_POSITIONS -> IndexOptions.DOCS_AND_FREQS_AND_POSITIONS;
            case DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS -> IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS;
        };
    }

    private static VectorEncoding vectorEncoding(RuntimeIndex.VectorEncoding encoding) {
        return switch (encoding) {
            case BYTE -> VectorEncoding.BYTE;
            case FLOAT32 -> VectorEncoding.FLOAT32;
        };
    }

    private static VectorSimilarity vectorSimilarity(RuntimeIndex.VectorSimilarity similarity) {
        return switch (similarity) {
            case EUCLIDEAN -> VectorSimilarity.EUCLIDEAN;
            case DOT_PRODUCT -> VectorSimilarity.DOT_PRODUCT;
            case COSINE -> VectorSimilarity.COSINE;
            case MAXIMUM_INNER_PRODUCT -> VectorSimilarity.MAXIMUM_INNER_PRODUCT;
        };
    }

    /** {@code vector} as the protocol carries it. */
    private static KnnVector knnVector(RuntimeIndex.Vector vector) {
        return switch (vector) {
            case RuntimeIndex.Vector.Floats(float[] values) -> {
                List<Float> floats = new ArrayList<>(values.length);
                for (float value : values) {
                    floats.add(value);
                }
                yield new KnnVector(VectorEncoding.FLOAT32, List.copyOf(floats), List.of());
            }
            case RuntimeIndex.Vector.Bytes(byte[] values) -> {
                List<Byte> bytes = new ArrayList<>(values.length);
                for (byte value : values) {
                    bytes.add(value);
                }
                yield new KnnVector(VectorEncoding.BYTE, List.of(), List.copyOf(bytes));
            }
        };
    }

    /** {@code vector} as the runtimes take it. */
    private static RuntimeIndex.Vector runtimeVector(KnnVector vector) {
        return switch (vector.encoding()) {
            case FLOAT32 -> {
                float[] values = new float[vector.floats().size()];
                for (int i = 0; i < values.length; i++) {
                    values[i] = vector.floats().get(i);
                }
                yield new RuntimeIndex.Vector.Floats(values);
            }
            case BYTE -> {
                byte[] values = new byte[vector.bytes().size()];
                for (int i = 0; i < values.length; i++) {
                    values[i] = vector.bytes().get(i);
                }
                yield new RuntimeIndex.Vector.Bytes(values);
            }
        };
    }

    private static DocValuesType docValuesType(RuntimeIndex.DocValuesType type) {
        return switch (type) {
            case NONE -> DocValuesType.NONE;
            case NUMERIC -> DocValuesType.NUMERIC;
            case BINARY -> DocValuesType.BINARY;
            case SORTED -> DocValuesType.SORTED;
            case SORTED_NUMERIC -> DocValuesType.SORTED_NUMERIC;
            case SORTED_SET -> DocValuesType.SORTED_SET;
        };
    }

    /** {@code getDocument}. */
    public static GetDocumentResult document(OpenRuntime open, GetDocumentParams params) throws RpcException {
        Documents documents = open.index().documents().orElseThrow(open::notSupported);
        int docId = params.docId();
        checkDocId(open.index(), docId);
        Documents.IndexDocument doc;
        try {
            doc = documents.document(docId);
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
                                field.docValues()
                                        .map(values -> new DocValues(
                                                docValuesType(values.type()),
                                                values.numbers(),
                                                values.values().stream()
                                                        .map(RuntimeResults::bytesValue)
                                                        .toList())),
                                field.hasTermVector(),
                                field.vector().map(RuntimeResults::knnVector)))
                        .toList());
    }

    /** {@code getStoredValue}. */
    public static GetStoredValueResult storedValue(OpenRuntime open, GetStoredValueParams params) throws RpcException {
        Documents documents = open.index().documents().orElseThrow(open::notSupported);
        checkDocId(open.index(), params.docId());
        checkField(open.index(), params.field());
        Documents.IndexDocument whole;
        try {
            whole = documents.document(params.docId());
        } catch (IOException e) {
            throw new RpcException(
                    ErrorCode.INDEX_NOT_READABLE, "Cannot read document " + params.docId() + ": " + e.getMessage(), e);
        }
        List<RuntimeIndex.StoredValue> values = whole.fields().stream()
                .filter(field -> field.name().equals(params.field()))
                .findFirst()
                .map(Documents.DocumentField::stored)
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
    public static GetTermVectorResult termVector(OpenRuntime open, GetTermVectorParams params) throws RpcException {
        Documents documents = open.index().documents().orElseThrow(open::notSupported);
        checkDocId(open.index(), params.docId());
        checkField(open.index(), params.field());
        try {
            return new GetTermVectorResult(documents.termVector(params.docId(), params.field()).stream()
                    .map(RuntimeResults::termVectorTerm)
                    .toList());
        } catch (IOException e) {
            throw new RpcException(ErrorCode.INDEX_NOT_READABLE, "Cannot read the term vector: " + e.getMessage(), e);
        }
    }

    private static TermVectorTerm termVectorTerm(Documents.VectorTerm term) {
        ByteStrings.Encoded encoded = ByteStrings.encode(new BytesRef(term.term()));
        return new TermVectorTerm(
                encoded.value(),
                encoded.binary(),
                term.freq(),
                term.positions().stream().map(RuntimeResults::position).toList());
    }

    /** {@code getTerms}. Terms are text if they are UTF-8, as Lucene 10's are (ADR 0006). */
    public static GetTermsResult terms(OpenRuntime open, GetTermsParams params) throws RpcException {
        Terms terms = open.index().terms().orElseThrow(open::notSupported);
        TermParams.checkLimit(params.limit(), GetTermsParams.MAX_LIMIT);
        Optional<byte[]> from =
                params.from().isPresent() ? Optional.of(bytes(params.from().get())) : Optional.empty();
        checkField(open.index(), params.field());
        try {
            Terms.TermsPage page = terms.terms(params.field(), from, params.limit());
            return new GetTermsResult(
                    page.terms().stream()
                            .map(term -> {
                                ByteStrings.Encoded encoded = ByteStrings.encode(new BytesRef(term.term()));
                                return new TermEntry(
                                        encoded.value(), encoded.binary(), term.docFreq(), term.totalTermFreq());
                            })
                            .toList(),
                    page.next().map(RuntimeResults::bytesValue));
        } catch (IOException e) {
            throw new RpcException(
                    ErrorCode.INDEX_NOT_READABLE,
                    "Cannot read the terms of field '" + params.field() + "': " + e.getMessage(),
                    e);
        }
    }

    /**
     * {@code getPostings}, through {@code filter} if present (ADR 0056). A runtime marks deleted
     * documents if its Lucene reads their postings, and otherwise gives live documents only, though
     * {@code docFreq} counts them.
     */
    public static GetPostingsResult postings(
            OpenRuntime open, GetPostingsParams params, Optional<PostingsPage.Filter> filter) throws RpcException {
        Terms terms = open.index().terms().orElseThrow(open::notSupported);
        TermParams.checkLimit(params.limit(), GetPostingsParams.MAX_LIMIT);
        if (params.fromDoc() < 0) {
            throw new RpcException(
                    ErrorCode.INVALID_PARAMS, "Invalid params: fromDoc must not be negative, not " + params.fromDoc());
        }
        checkField(open.index(), params.field());
        String term = TermParams.text(params.term());
        try {
            Terms.PostingsPage page = filter.isPresent() && filter.get().onlyMatches()
                    ? matchingPostings(
                            terms,
                            params.field(),
                            bytes(params.term()),
                            params.fromDoc(),
                            params.limit(),
                            filter.get().filter())
                    : terms.postings(params.field(), bytes(params.term()), params.fromDoc(), params.limit());
            if (page.docFreq() == 0) {
                throw new RpcException(
                        ErrorCode.TERM_NOT_FOUND, "No term '" + term + "' in field '" + params.field() + "'");
            }
            return new GetPostingsResult(
                    page.docFreq(),
                    page.totalTermFreq(),
                    page.postings().stream()
                            .map(posting -> new Posting(
                                    posting.docId(),
                                    posting.deleted(),
                                    OptionalInt.of(posting.freq()),
                                    filter.map(through -> through.filter().contains(posting.docId()))))
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
     * Up to {@code limit} postings of {@code term} from {@code fromDoc} on that are in {@code filter},
     * as core's {@code TermReader} shows them: the filter and the runtime's pages of postings leapfrog
     * to the next document they share. {@code nextDoc} is the next such document.
     */
    static Terms.PostingsPage matchingPostings(
            Terms terms, String field, byte[] term, int fromDoc, int limit, DocFilter filter) throws IOException {
        List<Terms.Posting> shown = new ArrayList<>();
        int first = filter.next(fromDoc);
        boolean any = first != DocIdSetIterator.NO_MORE_DOCS;
        // Read even if the filter has no match left, for the term's statistics.
        Terms.PostingsPage page = terms.postings(field, term, any ? first : fromDoc, any ? limit + 1 : 0);
        int docFreq = page.docFreq();
        long totalTermFreq = page.totalTermFreq();
        while (true) {
            for (Terms.Posting posting : page.postings()) {
                if (filter.contains(posting.docId()) && shown.size() <= limit) {
                    shown.add(posting);
                }
            }
            if (shown.size() > limit || page.nextDoc().isEmpty()) {
                break;
            }
            int from = filter.next(page.nextDoc().getAsInt());
            if (from == DocIdSetIterator.NO_MORE_DOCS) {
                break;
            }
            page = terms.postings(field, term, from, limit + 1 - shown.size());
        }
        OptionalInt nextDoc =
                shown.size() > limit ? OptionalInt.of(shown.get(limit).docId()) : OptionalInt.empty();
        return new Terms.PostingsPage(
                docFreq, totalTermFreq, List.copyOf(shown.subList(0, Math.min(limit, shown.size()))), nextDoc);
    }

    /** {@code getPositions}. */
    public static GetPositionsResult positions(OpenRuntime open, GetPositionsParams params) throws RpcException {
        Terms terms = open.index().terms().orElseThrow(open::notSupported);
        byte[] term = bytes(params.term());
        int docId = params.docId();
        String field = params.field();
        checkDocId(open.index(), docId);
        checkField(open.index(), field);
        String termText = TermParams.text(params.term());
        try {
            return new GetPositionsResult(terms
                    .positions(docId, field, term)
                    .orElseThrow(() -> new RpcException(
                            ErrorCode.TERM_NOT_FOUND,
                            "Document " + docId + " does not contain '" + termText + "' in field '" + field + "'"))
                    .stream()
                    .map(RuntimeResults::position)
                    .toList());
        } catch (IOException e) {
            throw new RpcException(
                    ErrorCode.INDEX_NOT_READABLE,
                    "Cannot read the positions of '" + termText + "' in document " + docId + ", field '" + field + "': "
                            + e.getMessage(),
                    e);
        }
    }

    private static TermPosition position(Documents.Position position) {
        return new TermPosition(
                position.position(),
                position.startOffset(),
                position.endOffset(),
                position.payload().map(payload -> ByteStrings.hex(new BytesRef(payload))));
    }

    /** {@code getPointTree}: a point field's BKD tree, segment by segment (ADR 0041, ADR 0048). */
    public static GetPointTreeResult pointTree(OpenRuntime open, GetPointTreeParams params) throws RpcException {
        Points points = open.index().points().orElseThrow(open::notSupported);
        String field = params.field();
        int bytes = checkPointField(open.index(), field);
        Points.Tree tree;
        List<Integer> docBases;
        try {
            tree = points.tree(field);
            docBases = stats(open.index()).indexes().stream()
                    .map(RuntimeIndex.IndexStats::docBase)
                    .toList();
        } catch (IOException e) {
            throw PointMapper.notReadable(field, e);
        }
        return new GetPointTreeResult(
                tree.field(),
                tree.dimensions(),
                tree.indexDimensions(),
                tree.bytesPerDimension(),
                tree.size(),
                tree.docCount(),
                tree.min().map(min -> PointMapper.pointValue(min, bytes)),
                tree.max().map(max -> PointMapper.pointValue(max, bytes)),
                tree.segments().stream()
                        .map(segment -> new PointSegment(
                                segment.segment(),
                                PointMapper.indexOf(docBases, segment.docBase()),
                                segment.name(),
                                segment.docBase(),
                                segment.maxDoc(),
                                segment.size(),
                                segment.docCount(),
                                PointMapper.pointValue(segment.min(), bytes),
                                PointMapper.pointValue(segment.max(), bytes)))
                        .toList());
    }

    /** {@code getPointCell}: a cell of a point field's BKD tree in one segment (ADR 0041, ADR 0048). */
    public static GetPointCellResult pointCell(OpenRuntime open, GetPointCellParams params) throws RpcException {
        Points points = open.index().points().orElseThrow(open::notSupported);
        String field = params.field();
        int bytes = checkPointField(open.index(), field);
        Points.CellContents contents;
        try {
            contents = points.cell(field, params.segment(), List.copyOf(params.path()));
        } catch (IllegalArgumentException e) {
            throw new RpcException(ErrorCode.INVALID_PARAMS, "Invalid params: " + e.getMessage(), e);
        } catch (IOException e) {
            throw PointMapper.notReadable(field, e);
        }
        return new GetPointCellResult(
                pointCell(contents.cell(), bytes),
                contents.children().stream().map(cell -> pointCell(cell, bytes)).toList(),
                contents.points().stream()
                        .map(point -> new LeafPoint(
                                point.docId(), point.deleted(), PointMapper.pointValue(point.value(), bytes)))
                        .toList(),
                contents.pointsCut());
    }

    private static PointCell pointCell(Points.Cell cell, int bytes) {
        return new PointCell(
                PointMapper.pointValue(cell.min(), bytes),
                PointMapper.pointValue(cell.max(), bytes),
                cell.size(),
                cell.leaf());
    }

    /**
     * Checks that {@code field} is a field with points, as {@code PointMapper.checkPointField} does.
     * Returns the bytes of each of its dimensions.
     */
    private static int checkPointField(RuntimeIndex index, String field) throws RpcException {
        RuntimeIndex.Field info = index.fields().stream()
                .filter(f -> f.name().equals(field))
                .findFirst()
                .orElseThrow(
                        () -> new RpcException(ErrorCode.FIELD_NOT_FOUND, "No field '" + field + "' in the index"));
        if (info.pointDimensionCount() == 0) {
            throw new RpcException(ErrorCode.INVALID_PARAMS, "Invalid params: the field '" + field + "' has no points");
        }
        return info.pointNumBytes();
    }

    /**
     * {@code startSearch}: a classic query, parsed with the analyzer of the params as the runtime has
     * it ({@link RuntimeAnalyzers}), a term query (ADR 0058), or a KNN query if the runtime reads
     * vectors (ADR 0049), scored by its default similarity, best score first. Highlighting is left out; another query, sort or
     * analyzer is not supported.
     */
    public static JobStarted startSearch(OpenRuntime open, StartSearchParams params, Jobs jobs) throws RpcException {
        Search search = open.index().search().orElseThrow(open::notSupported);
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
        if (!bestScoreFirst(params.sort())) {
            throw open.notSupported();
        }
        if (params.fields().isPresent()) {
            for (String field : params.fields().get()) {
                checkField(open.index(), field);
            }
        }
        Search.ParsedQuery query = parsedQuery(open, search, params.query());
        Optional<List<String>> fields = params.fields().map(List::copyOf);
        long jobId = jobs.start(
                "search " + query + " with Lucene " + open.runtime().luceneVersion() + ", hits " + offset + " to "
                        + (offset + limit),
                SearchResults.class,
                progress -> {
                    try {
                        return results(search.search(query, offset, limit, fields, progress::scanned));
                    } catch (IOException e) {
                        throw new RpcException(
                                ErrorCode.INDEX_NOT_READABLE, "Cannot search the index: " + e.getMessage(), e);
                    }
                });
        return new JobStarted(jobId);
    }

    /**
     * {@code query} as the runtime runs it: a classic query parsed by it, a term query (ADR 0058), or
     * a KNN query if it reads vectors. Another query is not supported.
     */
    private static Search.ParsedQuery parsedQuery(OpenRuntime open, Search search, SearchQuery query)
            throws RpcException {
        if (query.classic().isPresent()) {
            return parse(open, search, query.classic().get());
        }
        if (query.term().isPresent()) {
            TermQuery term = query.term().get();
            checkField(open.index(), term.field());
            return search.term(term.field(), bytes(term.term()));
        }
        if (query.knn().isPresent()) {
            return knn(open, search, query.knn().get());
        }
        throw open.notSupported();
    }

    /**
     * {@code startFilter} (ADR 0056): the documents that match a query, as {@link #parsedQuery} runs
     * it, found by the runtime's {@link Search#matches}, or those with a stored value in which a
     * pattern is found, scanned through {@link #storedSource}. A query with deleted documents is not
     * supported by a runtime that can't match them.
     */
    public static JobStarted startFilter(OpenRuntime open, StartFilterParams params, Jobs jobs) throws RpcException {
        boolean includeDeleted = params.includeDeleted();
        String lucene = " with Lucene " + open.runtime().luceneVersion();
        String deleted = includeDeleted ? ", deleted documents too" : "";
        int maxDoc = open.index().maxDoc();
        if (params.pattern().isPresent()) {
            FilterPattern filterPattern = params.pattern().get();
            Optional<Set<String>> fields = filterPattern.fields().map(Set::copyOf);
            StoredScan.StoredSource source = storedSource(open, fields, includeDeleted);
            Pattern pattern = StartStoredScanHandler.pattern(filterPattern.pattern(), filterPattern.caseInsensitive());
            return new JobStarted(jobs.start(
                    "filter by /" + pattern + "/" + (filterPattern.caseInsensitive() ? "i" : "")
                            + fields.map(names -> " in " + names).orElse("") + deleted + lucene,
                    DocFilter.class,
                    progress -> {
                        try {
                            return StoredScan.matches(
                                    source, pattern, StartStoredScanHandler.VALUE_TIME_LIMIT, progress);
                        } catch (IOException e) {
                            throw new RpcException(
                                    ErrorCode.INDEX_NOT_READABLE,
                                    "Cannot read the stored values: " + e.getMessage(),
                                    e);
                        }
                    }));
        }
        Search search = open.index().search().orElseThrow(open::notSupported);
        if (includeDeleted && !search.matchesDeleted()) {
            throw new RpcException(
                    ErrorCode.NOT_SUPPORTED,
                    "Not supported with Lucene " + open.runtime().luceneVersion()
                            + ": it doesn't read the postings of deleted documents");
        }
        Search.ParsedQuery query = parsedQuery(open, search, params.query().orElseThrow());
        return new JobStarted(jobs.start("filter by " + query + deleted + lucene, DocFilter.class, progress -> {
            try {
                Search.Matches matches = search.matches(query, includeDeleted, progress::scanned);
                return DocFilter.of(
                        matches.docs(),
                        maxDoc,
                        matches.query(),
                        matches.rewrittenQuery(),
                        Duration.ofMillis(matches.tookMillis()));
            } catch (IOException e) {
                throw new RpcException(ErrorCode.INDEX_NOT_READABLE, "Cannot search the index: " + e.getMessage(), e);
            }
        }));
    }

    /**
     * {@code startExplain} (ADR 0057): how a query, as {@link #parsedQuery} runs it, scores a
     * document, explained by the runtime. The similarity of the params is ignored: the runtime scores
     * with its default one, as {@link #startSearch} does, so the explanation matches the hit's score.
     */
    public static JobStarted startExplain(OpenRuntime open, StartExplainParams params, Jobs jobs) throws RpcException {
        Search search = open.index().search().orElseThrow(open::notSupported);
        int docId = params.docId();
        checkDocId(open.index(), docId);
        Search.ParsedQuery query = parsedQuery(open, search, params.query());
        return new JobStarted(jobs.start(
                "explain " + query + " for document " + docId + " with Lucene "
                        + open.runtime().luceneVersion(),
                Explanation.class,
                progress -> {
                    try {
                        return explanation(search.explain(query, docId, progress::scanned));
                    } catch (IOException e) {
                        throw new RpcException(
                                ErrorCode.INDEX_NOT_READABLE,
                                "Cannot explain document " + docId + ": " + e.getMessage(),
                                e);
                    }
                }));
    }

    /**
     * {@code startReconstruct} (ADR 0059): checks the document and fields at once, as {@link
     * StartReconstructHandler} does for Lucene 10, counts the terms and points to go through, then
     * rebuilds the document with the runtime in a job. Without fields, every field with postings or
     * points is rebuilt, in field number order.
     */
    public static JobStarted startReconstruct(OpenRuntime open, StartReconstructParams params, Jobs jobs)
            throws RpcException {
        Rebuild rebuild = open.index().rebuild().orElseThrow(open::notSupported);
        int docId = params.docId();
        checkDocId(open.index(), docId);
        Map<String, RuntimeIndex.Field> byName = new LinkedHashMap<>();
        for (RuntimeIndex.Field field : open.index().fields()) {
            byName.put(field.name(), field);
        }
        List<RuntimeIndex.Field> fields = new ArrayList<>();
        if (params.fields().isPresent()) {
            for (String name : params.fields().get()) {
                RuntimeIndex.Field field = byName.get(name);
                if (field == null) {
                    throw new RpcException(ErrorCode.FIELD_NOT_FOUND, "No field '" + name + "' in the index");
                }
                if (!rebuildable(field)) {
                    throw new RpcException(
                            ErrorCode.INVALID_PARAMS,
                            "Invalid params: the field '" + name + "' has no terms or points to rebuild");
                }
                fields.add(field);
            }
        } else {
            open.index().fields().stream()
                    .filter(RuntimeResults::rebuildable)
                    .sorted(Comparator.comparingInt(RuntimeIndex.Field::number))
                    .forEach(fields::add);
        }
        List<String> names = fields.stream().map(RuntimeIndex.Field::name).toList();
        OptionalLong total;
        try {
            total = rebuild.count(docId, names);
        } catch (IOException e) {
            throw notRebuilt(docId, e);
        }
        return new JobStarted(jobs.start(
                "reconstruct document " + docId
                        + params.fields().map(given -> ", fields " + given).orElse("")
                        + " with Lucene " + open.runtime().luceneVersion(),
                ReconstructResult.class,
                new StartReconstructHandler.Count(total),
                progress -> {
                    long start = System.nanoTime();
                    try {
                        List<Rebuild.RebuiltField> rebuilt = rebuild.rebuild(docId, names, progress::scanned);
                        List<ReconstructedField> result = new ArrayList<>(rebuilt.size());
                        for (int i = 0; i < rebuilt.size(); i++) {
                            result.add(reconstructedField(rebuilt.get(i), fields.get(i)));
                        }
                        return new ReconstructResult(
                                List.copyOf(result),
                                Duration.ofNanos(System.nanoTime() - start).toMillis());
                    } catch (IOException e) {
                        throw notRebuilt(docId, e);
                    }
                }));
    }

    /** Whether a field has postings or points to rebuild. */
    private static boolean rebuildable(RuntimeIndex.Field field) {
        return field.indexOptions() != RuntimeIndex.IndexOptions.NONE || field.pointDimensionCount() > 0;
    }

    private static ReconstructedField reconstructedField(Rebuild.RebuiltField rebuilt, RuntimeIndex.Field field) {
        return new ReconstructedField(
                rebuilt.field(),
                indexOptions(rebuilt.indexOptions()),
                switch (rebuilt.source()) {
                    case TERM_VECTOR -> ReconstructSource.TERM_VECTOR;
                    case POSTINGS -> ReconstructSource.POSTINGS;
                },
                rebuilt.terms().stream().map(RuntimeResults::termVectorTerm).toList(),
                rebuilt.points().stream()
                        .map(point -> PointMapper.pointValue(point, field.pointNumBytes()))
                        .toList());
    }

    private static RpcException notRebuilt(int docId, IOException e) {
        return new RpcException(
                ErrorCode.INDEX_NOT_READABLE,
                "Cannot rebuild the terms and points of document " + docId + ": " + e.getMessage(),
                e);
    }

    private static Explanation explanation(Search.Explanation explanation) {
        return new Explanation(
                explanation.match(),
                explanation.value().toString(),
                explanation.description(),
                explanation.details().stream().map(RuntimeResults::explanation).toList());
    }

    /** {@code classic}, parsed by the runtime. */
    private static Search.ParsedQuery parse(OpenRuntime open, Search search, ClassicQuery classic) throws RpcException {
        Search.AnalyzerSpec analyzer = RuntimeAnalyzers.spec(classic.analyzer(), open.runtime(), search);
        try {
            return search.parse(
                    classic.expression(),
                    new Search.ParserOptions(
                            classic.defaultField(),
                            classic.defaultOperator() == QueryOperator.AND,
                            classic.options().allowLeadingWildcard(),
                            classic.options().phraseSlop(),
                            classic.options().fuzzyPrefixLength(),
                            classic.options().enablePositionIncrements(),
                            classic.autoGeneratePhraseQueries()),
                    analyzer);
        } catch (Search.InvalidQueryException e) {
            throw new RpcException(ErrorCode.INVALID_QUERY, "Invalid query: " + e.getMessage(), e);
        } catch (Search.UnsupportedAnalyzerException e) {
            throw new RpcException(
                    ErrorCode.NOT_SUPPORTED,
                    "Not supported with Lucene " + open.runtime().luceneVersion() + ": " + e.getMessage(),
                    e);
        }
    }

    /**
     * {@code knn}, checked as {@code SearchQueries} checks it for Lucene 10 ({@link KnnChecks}), and
     * built by the runtime's {@link Knn}; its filter is parsed by the runtime.
     */
    private static Search.ParsedQuery knn(OpenRuntime open, Search search, KnnQuery knn) throws RpcException {
        Knn group = open.index().knn().orElseThrow(open::notSupported);
        RuntimeIndex.Field field = open.index().fields().stream()
                .filter(f -> f.name().equals(knn.field()))
                .findFirst()
                .orElseThrow(() ->
                        new RpcException(ErrorCode.FIELD_NOT_FOUND, "No field '" + knn.field() + "' in the index"));
        KnnChecks.check(
                knn, field.vectors().map(RuntimeIndex.VectorFormat::dimension).orElse(0));
        RuntimeIndex.VectorFormat format = field.vectors().orElseThrow();
        KnnVector target = knn.vector().isPresent() ? knn.vector().get() : documentVector(open, group, knn);
        try {
            KnnChecks.checkTarget(
                    knn.field(),
                    format.dimension(),
                    vectorEncoding(format.encoding()),
                    vectorSimilarity(format.similarity()),
                    target);
        } catch (IllegalArgumentException e) {
            throw new RpcException(ErrorCode.INVALID_PARAMS, "Invalid params: " + e.getMessage(), e);
        }
        Optional<Search.ParsedQuery> filter = knn.filter().isPresent()
                ? Optional.of(parse(open, search, knn.filter().get()))
                : Optional.empty();
        return knn.exact()
                ? group.exact(knn.field(), runtimeVector(target), filter)
                : group.approximate(knn.field(), runtimeVector(target), knn.k().getAsInt(), filter);
    }

    /** The vector of the query's document, which may be deleted. */
    private static KnnVector documentVector(OpenRuntime open, Knn group, KnnQuery knn) throws RpcException {
        int docId = knn.docId().getAsInt();
        checkDocId(open.index(), docId);
        try {
            return group.vector(docId, knn.field())
                    .map(RuntimeResults::knnVector)
                    .orElseThrow(() -> new RpcException(
                            ErrorCode.INVALID_PARAMS,
                            "Invalid params: document " + docId + " has no vector in the field '" + knn.field() + "'"));
        } catch (IOException e) {
            throw new RpcException(
                    ErrorCode.INDEX_NOT_READABLE, "Cannot read document " + docId + ": " + e.getMessage(), e);
        }
    }

    /**
     * {@code startFieldTermScan}: the terms of a field counted and its top terms found in a job, as
     * {@code getFieldTermScan} reads it.
     */
    public static JobStarted startFieldTermScan(OpenRuntime open, String field, int topN, Jobs jobs)
            throws RpcException {
        Terms terms = open.index().terms().orElseThrow(open::notSupported);
        checkField(open.index(), field);
        long jobId = jobs.start(
                "term scan of field " + field + ", top " + topN + " with Lucene "
                        + open.runtime().luceneVersion(),
                FieldTerms.class,
                progress -> {
                    try {
                        Terms.FieldTerms scanned = terms.topTerms(field, topN, progress::scanned);
                        return new FieldTerms(
                                field,
                                scanned.uniqueTerms(),
                                scanned.topTerms().stream()
                                        .map(term -> {
                                            ByteStrings.Encoded encoded = ByteStrings.encode(new BytesRef(term.term()));
                                            return new TopTerm(
                                                    encoded.value(),
                                                    encoded.binary(),
                                                    term.docFreq(),
                                                    term.totalTermFreq());
                                        })
                                        .toList());
                    } catch (IOException e) {
                        throw new RpcException(
                                ErrorCode.INDEX_NOT_READABLE,
                                "Cannot read the terms of field '" + field + "': " + e.getMessage(),
                                e);
                    }
                });
        return new JobStarted(jobId);
    }

    /**
     * {@code startExportTerms}: the terms of a field for {@link TermExport#export}, read page by
     * page.
     */
    public static TermExport.TermSource exportTerms(OpenRuntime open, String field) throws RpcException {
        Terms terms = open.index().terms().orElseThrow(open::notSupported);
        checkField(open.index(), field);
        return pages(terms, field, TermExport.PROGRESS_INTERVAL);
    }

    /**
     * {@code startAutoStopWords}: the stop words of {@code fields}, or of every indexed field if
     * empty, found in a job over the terms read page by page (ADR 0061).
     *
     * @throws RpcException {@code FIELD_NOT_FOUND} for a field that is missing or not indexed
     */
    public static JobStarted startAutoStopWords(
            OpenRuntime open, List<String> fields, AutoStopWords.Threshold threshold, Jobs jobs) throws RpcException {
        Terms terms = open.index().terms().orElseThrow(open::notSupported);
        List<String> indexed = open.index().fields().stream()
                .filter(field -> field.indexOptions() != RuntimeIndex.IndexOptions.NONE)
                .map(RuntimeIndex.Field::name)
                .toList();
        for (String field : fields) {
            if (!indexed.contains(field)) {
                throw new RpcException(ErrorCode.FIELD_NOT_FOUND, "No indexed field '" + field + "' in the index");
            }
        }
        List<String> scanned = (fields.isEmpty() ? indexed : fields)
                .stream().distinct().sorted().toList();
        RuntimeIndex index = open.index();
        long jobId = jobs.start(
                "auto stop words of " + (fields.isEmpty() ? "every indexed field" : "fields " + List.copyOf(fields))
                        + " with Lucene " + open.runtime().luceneVersion(),
                io.github.mfuerstenau.leia.protocol.analysis.AutoStopWords.class,
                progress -> StartAutoStopWordsHandler.found(() -> {
                    // Inside the job, so the statistics aren't read on the backend thread.
                    int maxDocFreq = threshold.maxDocFreq(index.stats().numDocs());
                    Map<String, TermExport.TermSource> sources = new LinkedHashMap<>();
                    for (String field : scanned) {
                        sources.put(field, pages(terms, field, TermExport.PROGRESS_INTERVAL));
                    }
                    return AutoStopWords.find(sources, maxDocFreq, StartAutoStopWordsParams.MAX_WORDS, progress);
                }));
        return new JobStarted(jobId);
    }

    /** Every term of {@code field}, read {@code pageSize} at a time through {@link Terms#terms}. */
    static TermExport.TermSource pages(Terms terms, String field, int pageSize) {
        return sink -> {
            Optional<byte[]> from = Optional.empty();
            do {
                Terms.TermsPage page = terms.terms(field, from, pageSize);
                for (Terms.TermEntry entry : page.terms()) {
                    sink.term(entry.term(), 0, entry.term().length, entry.docFreq());
                }
                from = page.next();
            } while (from.isPresent());
        };
    }

    /**
     * {@code startStoredScan}: the stored values of the documents in {@code fields} (all if empty)
     * for {@link StoredScan#scan}, read a page at a time through {@link Documents#storedValues} (ADR
     * 0055).
     */
    public static StoredScan.StoredSource storedSource(
            OpenRuntime open, Optional<Set<String>> fields, boolean includeDeleted) throws RpcException {
        Documents documents = open.index().documents().orElseThrow(open::notSupported);
        if (fields.isPresent()) {
            for (String field : fields.get()) {
                checkField(open.index(), field);
            }
        }
        int maxDoc = open.index().maxDoc();
        return new StoredScan.StoredSource() {
            @Override
            public int maxDoc() {
                return maxDoc;
            }

            @Override
            public List<StoredScan.Document> read(int fromDoc, int toDoc) throws IOException {
                return documents.storedValues(fromDoc, toDoc, fields, includeDeleted).stream()
                        .map(RuntimeResults::storedDocument)
                        .toList();
            }
        };
    }

    /** A runtime's stored document as {@link StoredScan} reads it. */
    static StoredScan.Document storedDocument(Documents.StoredDocument document) {
        Map<String, List<IndexDocument.StoredValue>> values = new LinkedHashMap<>();
        document.values()
                .forEach((field, fieldValues) -> values.put(
                        field,
                        fieldValues.stream().map(RuntimeResults::coreValue).toList()));
        return new StoredScan.Document(document.docId(), document.deleted(), values);
    }

    /**
     * Checks {@code dir} with {@code runtime}'s {@code CheckIndex}, read-only, at {@code level} if it
     * takes levels, otherwise at its one level (ADR 0045, ADR 0046). The report goes to {@code line};
     * {@code progress} stops the check by throwing.
     */
    public static IndexCheck check(
            LuceneRuntime runtime,
            Path dir,
            DirectoryKind kind,
            int level,
            boolean skipped,
            Consumer<String> line,
            ScanProgress progress) {
        Optional<Check> check = runtime.check();
        if (check.isEmpty()) {
            return failedCheck(dir, skipped, "Lucene " + runtime.luceneVersion() + " can't check an index here");
        }
        Check.CheckResult result = check.get().check(dir, kind, OptionalInt.of(level), line, progress::scanned);
        return new IndexCheck(
                dir.toString(),
                skipped,
                result.clean(),
                result.error(),
                result.segmentsFile(),
                result.missingSegments(),
                result.toolOutOfDate(),
                result.segmentCount(),
                result.badSegments(),
                result.lostDocs(),
                result.validCounter(),
                result.segments().stream()
                        .map(segment -> new SegmentCheck(
                                segment.name(),
                                segment.maxDoc(),
                                segment.codec(),
                                segment.compound(),
                                segment.files(),
                                segment.sizeBytes(),
                                segment.hasDeletions(),
                                segment.openReaderPassed(),
                                segment.error()))
                        .toList());
    }

    /** The outcome for a directory that could not be checked at all. */
    public static IndexCheck failedCheck(Path dir, boolean skipped, String error) {
        return new IndexCheck(
                dir.toString(),
                skipped,
                false,
                Optional.of(error),
                Optional.empty(),
                false,
                false,
                0,
                0,
                0,
                false,
                List.of());
    }

    /** Whether {@code sort} is the best score first: no key, or the score not reversed alone. */
    private static boolean bestScoreFirst(List<SortKey> sort) {
        return sort.isEmpty()
                || sort.size() == 1
                        && sort.getFirst().score().isPresent()
                        && !sort.getFirst().score().get().reverse();
    }

    private static SearchResults results(Search.SearchResults results) {
        return new SearchResults(
                results.query(),
                results.rewrittenQuery(),
                results.totalHits(),
                TotalHitsRelation.EQUAL_TO,
                results.hits().stream().map(RuntimeResults::hit).toList(),
                results.tookMillis(),
                Optional.empty());
    }

    private static Hit hit(Search.Hit hit) {
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
    private static StoredValue storedValue(RuntimeIndex.StoredValue value, int maxLength) {
        return GetDocumentHandler.storedValue(coreValue(value), maxLength);
    }

    /** {@code value} as core has it: a number by its type. */
    private static IndexDocument.StoredValue coreValue(RuntimeIndex.StoredValue value) {
        return switch (value) {
            case RuntimeIndex.StoredValue.Text(String text) -> new IndexDocument.StoredValue.Text(text);
            case RuntimeIndex.StoredValue.Binary(byte[] bytes) ->
                new IndexDocument.StoredValue.Binary(new BytesRef(bytes));
            case RuntimeIndex.StoredValue.Numeric(Integer number) -> new IndexDocument.StoredValue.IntValue(number);
            case RuntimeIndex.StoredValue.Numeric(Long number) -> new IndexDocument.StoredValue.LongValue(number);
            case RuntimeIndex.StoredValue.Numeric(Float number) -> new IndexDocument.StoredValue.FloatValue(number);
            case RuntimeIndex.StoredValue.Numeric(Number number) ->
                new IndexDocument.StoredValue.DoubleValue(number.doubleValue());
        };
    }

    /** A term of the params as the runtimes take it. */
    private static byte[] bytes(BytesValue term) throws RpcException {
        BytesRef bytes = TermParams.term(term);
        return Arrays.copyOfRange(bytes.bytes, bytes.offset, bytes.offset + bytes.length);
    }

    private static BytesValue bytesValue(byte[] bytes) {
        return ByteStrings.bytesValue(new BytesRef(bytes));
    }

    private static void checkDocId(RuntimeIndex index, int docId) throws RpcException {
        if (docId < 0 || docId >= index.maxDoc()) {
            throw new RpcException(
                    ErrorCode.DOC_NOT_FOUND, "No document " + docId + " in the index: maxDoc is " + index.maxDoc());
        }
    }

    private static void checkField(RuntimeIndex index, String field) throws RpcException {
        if (index.fields().stream().noneMatch(f -> f.name().equals(field))) {
            throw new RpcException(ErrorCode.FIELD_NOT_FOUND, "No field '" + field + "' in the index");
        }
    }
}
