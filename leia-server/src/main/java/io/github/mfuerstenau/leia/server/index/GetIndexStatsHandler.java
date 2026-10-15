package io.github.mfuerstenau.leia.server.index;

import io.github.mfuerstenau.leia.core.FieldStats;
import io.github.mfuerstenau.leia.core.IndexStats;
import io.github.mfuerstenau.leia.core.IndexSummary;
import io.github.mfuerstenau.leia.core.ReadOnlyIndex;
import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.index.DocValuesType;
import io.github.mfuerstenau.leia.protocol.index.GetIndexStatsParams;
import io.github.mfuerstenau.leia.protocol.index.GetIndexStatsResult;
import io.github.mfuerstenau.leia.protocol.index.IndexCommitStats;
import io.github.mfuerstenau.leia.protocol.index.IndexField;
import io.github.mfuerstenau.leia.protocol.index.IndexOptions;
import io.github.mfuerstenau.leia.protocol.index.TermStats;
import io.github.mfuerstenau.leia.protocol.index.VectorEncoding;
import io.github.mfuerstenau.leia.protocol.index.VectorSimilarity;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.legacy.Lucene3Results;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.Optional;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.util.Version;

/** {@code getIndexStats}: the cheap statistics of the {@link CurrentIndex}. */
public final class GetIndexStatsHandler implements MethodHandler<GetIndexStatsParams, GetIndexStatsResult> {

    private final CurrentIndex currentIndex;

    @Inject
    GetIndexStatsHandler(CurrentIndex currentIndex) {
        this.currentIndex = currentIndex;
    }

    @Override
    public RpcMethod<GetIndexStatsParams, GetIndexStatsResult> method() {
        return Methods.GET_INDEX_STATS;
    }

    @Override
    public GetIndexStatsResult handle(GetIndexStatsParams params) throws RpcException {
        if (currentIndex.legacy().isPresent()) {
            return Lucene3Results.indexStats(currentIndex.legacy().get());
        }
        ReadOnlyIndex index = currentIndex.readable();
        try {
            return toResult(index.stats());
        } catch (IOException e) {
            throw new RpcException(
                    ErrorCode.INDEX_NOT_READABLE, "Cannot read the index statistics: " + e.getMessage(), e);
        }
    }

    private static GetIndexStatsResult toResult(IndexStats stats) {
        return new GetIndexStatsResult(
                stats.path().toString(),
                stats.directoryImpl(),
                stats.segmentCount(),
                stats.numDocs(),
                stats.maxDoc(),
                stats.indexes().stream().map(GetIndexStatsHandler::toCommit).toList(),
                stats.fields().stream().map(GetIndexStatsHandler::toField).toList());
    }

    private static IndexCommitStats toCommit(IndexStats.CommitStats commit) {
        IndexSummary summary = commit.summary();
        return new IndexCommitStats(
                summary.path().toString(),
                summary.docBase(),
                summary.segmentsFile(),
                commit.generation(),
                commit.version(),
                commit.segmentCount(),
                summary.numDocs(),
                summary.maxDoc(),
                summary.createdVersionMajor(),
                commit.commitLuceneVersion().toString(),
                summary.minSegmentLuceneVersion().map(Version::toString),
                commit.userData());
    }

    private static IndexField toField(FieldStats field) {
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
                field.vectorDimension(),
                field.vectorDimension() == 0 ? Optional.empty() : Optional.of(vectorEncoding(field.vectorEncoding())),
                field.vectorDimension() == 0
                        ? Optional.empty()
                        : Optional.of(vectorSimilarity(field.vectorSimilarity())),
                field.terms()
                        .map(terms -> new TermStats(terms.docCount(), terms.sumDocFreq(), terms.sumTotalTermFreq())));
    }

    public static IndexOptions indexOptions(org.apache.lucene.index.IndexOptions options) {
        return switch (options) {
            case NONE -> IndexOptions.NONE;
            case DOCS -> IndexOptions.DOCS;
            case DOCS_AND_FREQS -> IndexOptions.DOCS_AND_FREQS;
            case DOCS_AND_FREQS_AND_POSITIONS -> IndexOptions.DOCS_AND_FREQS_AND_POSITIONS;
            case DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS -> IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS;
            case DOCS_AND_CUSTOM_FREQS -> IndexOptions.DOCS_AND_CUSTOM_FREQS;
        };
    }

    public static VectorEncoding vectorEncoding(org.apache.lucene.index.VectorEncoding encoding) {
        return switch (encoding) {
            case BYTE -> VectorEncoding.BYTE;
            case FLOAT32 -> VectorEncoding.FLOAT32;
        };
    }

    public static VectorSimilarity vectorSimilarity(VectorSimilarityFunction similarity) {
        return switch (similarity) {
            case EUCLIDEAN -> VectorSimilarity.EUCLIDEAN;
            case DOT_PRODUCT -> VectorSimilarity.DOT_PRODUCT;
            case COSINE -> VectorSimilarity.COSINE;
            case MAXIMUM_INNER_PRODUCT -> VectorSimilarity.MAXIMUM_INNER_PRODUCT;
        };
    }

    public static DocValuesType docValuesType(org.apache.lucene.index.DocValuesType type) {
        return switch (type) {
            case NONE -> DocValuesType.NONE;
            case NUMERIC -> DocValuesType.NUMERIC;
            case BINARY -> DocValuesType.BINARY;
            case SORTED -> DocValuesType.SORTED;
            case SORTED_NUMERIC -> DocValuesType.SORTED_NUMERIC;
            case SORTED_SET -> DocValuesType.SORTED_SET;
        };
    }
}
