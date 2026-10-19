package io.github.mfuerstenau.leia.server.runtime;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.search.FieldSort;
import io.github.mfuerstenau.leia.protocol.search.Similarity;
import io.github.mfuerstenau.leia.protocol.search.SortKey;
import io.github.mfuerstenau.leia.protocol.search.SortValue;
import io.github.mfuerstenau.leia.runtime.RuntimeIndex;
import io.github.mfuerstenau.leia.runtime.Scoring;
import io.github.mfuerstenau.leia.server.ByteStrings;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.search.SearchQueries;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.lucene.util.BytesRef;

/**
 * The similarity and sort of {@code startSearch} and {@code startExplain} on a runtime with a {@link
 * Scoring} group (ADR 0064). They are checked here, with the errors and messages that Lucene 10 gives
 * ({@link SearchQueries}, leia-core's {@code SortType}), before the runtime gets them.
 */
final class RuntimeScoring {

    private RuntimeScoring() {}

    /**
     * The runtime's similarity for {@code similarity}.
     *
     * @throws RpcException {@code INVALID_PARAMS} unless exactly one alternative is present, or for
     *     BM25 parameters out of range
     */
    static Scoring.Similarity similarity(Similarity similarity) throws RpcException {
        // Checks it as Lucene 10 does.
        SearchQueries.similarity(similarity);
        if (similarity.classic().isPresent()) {
            return new Scoring.Similarity.Classic();
        }
        if (similarity.booleanSimilarity().isPresent()) {
            return new Scoring.Similarity.Bool();
        }
        var bm25 = similarity.bm25().orElseThrow();
        return new Scoring.Similarity.Bm25(bm25.k1(), bm25.b());
    }

    /** How a job names {@code similarity}, as Lucene 10's {@code toString} does. */
    static String describe(Similarity similarity) throws RpcException {
        return SearchQueries.similarity(similarity).toString();
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
                    .filter(alternative -> alternative.isPresent())
                    .count();
            if (present != 1) {
                throw new RpcException(
                        ErrorCode.INVALID_PARAMS,
                        "Invalid params: a sort key needs exactly one of field, score and doc");
            }
            if (key.score().isPresent()) {
                sort.add(new Scoring.SortKey.Score(key.score().get().reverse()));
            } else if (key.doc().isPresent()) {
                sort.add(new Scoring.SortKey.Doc(key.doc().get().reverse()));
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
                    case Scoring.SortKey.Score score -> "score" + (score.reverse() ? " reversed" : "");
                    case Scoring.SortKey.Doc doc -> "doc" + (doc.reverse() ? " reversed" : "");
                    case Scoring.SortKey.Field field ->
                        field.field() + " " + field.type() + (field.reverse() ? " reversed" : "")
                                + (field.selector() == Scoring.SortSelector.MIN ? "" : " " + field.selector())
                                + (field.missing() == Scoring.SortMissing.DEFAULT ? "" : " missing " + field.missing());
                })
                .collect(Collectors.joining(", "));
    }

    /** {@code value} as the protocol carries it, as {@code StartSearchHandler} carries core's. */
    static SortValue sortValue(Scoring.SortValue value) {
        return new SortValue(
                value.number().map(Number::toString),
                value.term().map(term -> ByteStrings.bytesValue(new BytesRef(term))));
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
                        case NONE -> "the field '" + field.name() + "' has no doc values to sort by";
                        case BINARY ->
                            "the field '" + field.name() + "' has BINARY doc values, which can't be sorted by";
                        default ->
                            "the field '" + field.name() + "' has " + docValues
                                    + " doc values, which can't be sorted by as " + type;
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
        throw invalidParams("the field '" + field.name() + "' has " + kind + " " + field.docValuesType()
                + " doc values, which can't select " + selector);
    }

    private static RpcException invalidParams(String message) {
        return new RpcException(ErrorCode.INVALID_PARAMS, "Invalid params: " + message);
    }
}
