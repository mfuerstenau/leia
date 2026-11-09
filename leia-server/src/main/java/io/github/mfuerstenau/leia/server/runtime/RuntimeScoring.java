package io.github.mfuerstenau.leia.server.runtime;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.search.DocSort;
import io.github.mfuerstenau.leia.protocol.search.FieldSort;
import io.github.mfuerstenau.leia.protocol.search.ScoreSort;
import io.github.mfuerstenau.leia.protocol.search.Similarity;
import io.github.mfuerstenau.leia.protocol.search.SortKey;
import io.github.mfuerstenau.leia.protocol.search.SortValue;
import io.github.mfuerstenau.leia.runtime.RuntimeIndex;
import io.github.mfuerstenau.leia.runtime.Scoring;
import io.github.mfuerstenau.leia.server.ByteStrings;
import io.github.mfuerstenau.leia.server.RpcException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The similarity and sort of {@code startSearch} and {@code startExplain} on a runtime with a {@link
 * Scoring} group (ADR 0064). They are checked here, with the errors and messages that Lucene 10 gives
 * (Lucene's {@code BM25Similarity}, leia-core's {@code SortType}), before the runtime gets them.
 */
final class RuntimeScoring {

    private static final String REVERSED = " reversed";

    private RuntimeScoring() {}

    /**
     * The runtime's similarity for {@code similarity}, checked as Lucene 10 checks it.
     *
     * @throws RpcException {@code INVALID_PARAMS} unless exactly one alternative is present, or for
     *     BM25 parameters out of range
     */
    static Scoring.Similarity similarity(Similarity similarity) throws RpcException {
        long present = Stream.of(similarity.bm25(), similarity.classic(), similarity.booleanSimilarity())
                .filter(Optional::isPresent)
                .count();
        if (present != 1) {
            throw new RpcException(
                    ErrorCode.INVALID_PARAMS,
                    "Invalid params: the similarity needs exactly one of bm25, classic and boolean");
        }
        if (similarity.classic().isPresent()) {
            return new Scoring.Similarity.Classic();
        }
        if (similarity.booleanSimilarity().isPresent()) {
            return new Scoring.Similarity.Bool();
        }
        var bm25 = similarity.bm25().orElseThrow();
        // In the words of Lucene's BM25Similarity.
        if (!Float.isFinite(bm25.k1()) || bm25.k1() < 0) {
            throw new RpcException(
                    ErrorCode.INVALID_PARAMS,
                    "Invalid params: illegal k1 value: " + bm25.k1() + ", must be a non-negative finite value");
        }
        if (Float.isNaN(bm25.b()) || bm25.b() < 0 || bm25.b() > 1) {
            throw new RpcException(
                    ErrorCode.INVALID_PARAMS,
                    "Invalid params: illegal b value: " + bm25.b() + ", must be between 0 and 1");
        }
        return new Scoring.Similarity.Bm25(bm25.k1(), bm25.b());
    }

    /** How a job names {@code similarity}, as Lucene's {@code toString} does. */
    static String describe(Similarity similarity) throws RpcException {
        return switch (similarity(similarity)) {
            case Scoring.Similarity.Bm25 bm25 -> "BM25(k1=" + bm25.k1() + ",b=" + bm25.b() + ")";
            case Scoring.Similarity.Classic _ -> "ClassicSimilarity";
            case Scoring.Similarity.Bool _ -> "BooleanSimilarity";
        };
    }

    /**
     * The runtime's sort for {@code keys}; empty for no keys, which sorts by score.
     *
     * @throws RpcException {@code INVALID_PARAMS} unless each key has exactly one alternative, or for
     *     a field without doc values that sort as the key's type or with the key's selector; {@code
     *     FIELD_NOT_FOUND} for an unknown field
     */
    static List<Scoring.SortKey> sort(RuntimeIndex index, List<SortKey> keys) throws RpcException {
        List<Scoring.SortKey> sort = new ArrayList<>(keys.size());
        for (SortKey key : keys) {
            long present = Stream.of(key.field(), key.score(), key.doc())
                    .filter(Optional::isPresent)
                    .count();
            if (present != 1) {
                throw new RpcException(
                        ErrorCode.INVALID_PARAMS,
                        "Invalid params: a sort key needs exactly one of field, score and doc");
            }
            Optional<ScoreSort> score = key.score();
            Optional<DocSort> doc = key.doc();
            if (score.isPresent()) {
                sort.add(new Scoring.SortKey.Score(score.get().reverse()));
            } else if (doc.isPresent()) {
                sort.add(new Scoring.SortKey.Doc(doc.get().reverse()));
            } else {
                sort.add(fieldSort(index, key.field().orElseThrow()));
            }
        }
        return List.copyOf(sort);
    }

    /** How a job names {@code sort}, such as {@code score, dv_num INT reversed}. */
    static String describe(List<Scoring.SortKey> sort) {
        return sort.stream()
                .map(key -> switch (key) {
                    case Scoring.SortKey.Score score -> "score" + (score.reverse() ? REVERSED : "");
                    case Scoring.SortKey.Doc doc -> "doc" + (doc.reverse() ? REVERSED : "");
                    case Scoring.SortKey.Field field ->
                        field.field() + " " + field.type() + (field.reverse() ? REVERSED : "")
                                + (field.selector() == Scoring.SortSelector.MIN ? "" : " " + field.selector())
                                + (field.missing() == Scoring.SortMissing.DEFAULT ? "" : " missing " + field.missing());
                })
                .collect(Collectors.joining(", "));
    }

    /** {@code value} as the protocol carries it, as {@code StartSearchHandler} carries core's. */
    static SortValue sortValue(Scoring.SortValue value) {
        return new SortValue(
                Optional.ofNullable(value.number()).map(Number::toString),
                Optional.ofNullable(value.term()).map(ByteStrings::bytesValue));
    }

    /**
     * A field key, checked against the field's doc values with the rules and messages of leia-core's
     * {@code SortType}: numeric doc values sort as a number with {@code MIN}, sorted ones as {@code
     * STRING} with {@code MIN}, sorted numeric ones as a number with {@code MIN} or {@code MAX}, and
     * sorted set ones as {@code STRING} with any selector.
     */
    private static Scoring.SortKey fieldSort(RuntimeIndex index, FieldSort sort) throws RpcException {
        RuntimeIndex.Field field = index.fields().stream()
                .filter(f -> f.name().equals(sort.field()))
                .findFirst()
                .orElseThrow(() ->
                        new RpcException(ErrorCode.FIELD_NOT_FOUND, "No field '" + sort.field() + "' in the index"));
        RuntimeIndex.DocValuesType docValues = field.docValuesType();
        Scoring.SortType type = Scoring.SortType.valueOf(sort.type().name());
        Scoring.SortSelector selector =
                Scoring.SortSelector.valueOf(sort.selector().name());
        boolean string = type == Scoring.SortType.STRING;
        if ((docValues == RuntimeIndex.DocValuesType.NUMERIC && !string)
                || (docValues == RuntimeIndex.DocValuesType.SORTED && string)) {
            checkSelector(field, selector, Scoring.SortSelector.MIN);
        } else if (docValues == RuntimeIndex.DocValuesType.SORTED_NUMERIC && !string) {
            checkSelector(field, selector, Scoring.SortSelector.MIN, Scoring.SortSelector.MAX);
        } else if (docValues != RuntimeIndex.DocValuesType.SORTED_SET || !string) {
            throw invalidParams(
                    switch (docValues) {
                        case NONE -> theField(field.name()) + " has no doc values to sort by";
                        case BINARY -> theField(field.name()) + " has BINARY doc values, which can't be sorted by";
                        default ->
                            theField(field.name()) + " has " + docValues + " doc values, which can't be sorted by as "
                                    + type;
                    });
        }
        return new Scoring.SortKey.Field(
                field.name(),
                type,
                sort.reverse(),
                selector,
                Scoring.SortMissing.valueOf(sort.missing().name()));
    }

    private static void checkSelector(
            RuntimeIndex.Field field, Scoring.SortSelector selector, Scoring.SortSelector... allowed)
            throws RpcException {
        for (Scoring.SortSelector one : allowed) {
            if (one == selector) {
                return;
            }
        }
        String kind = allowed.length == 1 ? "single-valued" : "multi-valued numeric";
        throw invalidParams(theField(field.name()) + " has " + kind + " " + field.docValuesType()
                + " doc values, which can't select " + selector);
    }

    private static RpcException invalidParams(String message) {
        return new RpcException(ErrorCode.INVALID_PARAMS, "Invalid params: " + message);
    }

    private static String theField(String name) {
        return "the field '" + name + "'";
    }
}
