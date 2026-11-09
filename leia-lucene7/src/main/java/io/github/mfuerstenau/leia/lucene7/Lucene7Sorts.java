package io.github.mfuerstenau.leia.lucene7;

import io.github.mfuerstenau.leia.runtime.Scoring.Similarity;
import io.github.mfuerstenau.leia.runtime.Scoring.SortKey;
import io.github.mfuerstenau.leia.runtime.Scoring.SortMissing;
import io.github.mfuerstenau.leia.runtime.Scoring.SortSelector;
import io.github.mfuerstenau.leia.runtime.Scoring.SortType;
import java.util.List;
import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.SortedNumericSelector;
import org.apache.lucene.search.SortedNumericSortField;
import org.apache.lucene.search.SortedSetSelector;
import org.apache.lucene.search.SortedSetSortField;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.search.similarities.BooleanSimilarity;
import org.apache.lucene.search.similarities.ClassicSimilarity;
import org.jspecify.annotations.Nullable;

/**
 * The similarities and sorts of {@link io.github.mfuerstenau.leia.runtime.Scoring} in Lucene 7.7: a
 * copy of leia-lucene9's {@code Lucene9Sorts}, with the same messages (ADR 0064, ADR 0076).
 */
final class Lucene7Sorts {

    private Lucene7Sorts() {}

    static org.apache.lucene.search.similarities.Similarity similarity(Similarity similarity) {
        return switch (similarity) {
            case Similarity.Bm25 bm25 -> new BM25Similarity(bm25.k1(), bm25.b());
            case Similarity.Classic _ -> new ClassicSimilarity();
            case Similarity.Bool _ -> new BooleanSimilarity();
        };
    }

    /**
     * The Lucene sort of {@code keys}, which aren't empty.
     *
     * @throws IllegalArgumentException for a field that isn't in {@code fieldInfos}, or has no doc
     *     values that sort as its key's type or with its key's selector
     */
    static Sort sort(FieldInfos fieldInfos, List<SortKey> keys) {
        return new Sort(keys.stream()
                .map(key -> switch (key) {
                    case SortKey.Score score -> new SortField(null, SortField.Type.SCORE, score.reverse());
                    case SortKey.Doc doc -> new SortField(null, SortField.Type.DOC, doc.reverse());
                    case SortKey.Field field -> sortField(fieldInfos, field);
                })
                .toArray(SortField[]::new));
    }

    /** Whether {@code sortField} sorts by numeric doc values, whose missing value Lucene fills in. */
    static boolean numeric(SortField sortField) {
        return sortField instanceof SortedNumericSortField
                || (sortField.getField() != null
                        && switch (sortField.getType()) {
                            case INT, LONG, FLOAT, DOUBLE -> true;
                            default -> false;
                        });
    }

    private static SortField withMissing(SortField sortField, @Nullable Object missingValue) {
        if (missingValue != null) {
            sortField.setMissingValue(missingValue);
        }
        return sortField;
    }

    /**
     * Sorts by a field with the sort field class of its doc values type. The sort reads every match's
     * doc value (ADR 0012): Lucene only skips documents through the points of the field sorted by
     * since 8.7, so unlike 9.12's this needs no comparator that never prunes (ADR 0076).
     */
    private static SortField sortField(FieldInfos fieldInfos, SortKey.Field key) {
        FieldInfo field = fieldInfos.fieldInfo(key.field());
        if (field == null) {
            throw new IllegalArgumentException("No field '" + key.field() + "' in the index");
        }
        DocValuesType docValues = field.getDocValuesType();
        boolean string = key.type() == SortType.STRING;
        boolean reverse = key.reverse();
        // Lucene orders the missing value before it reverses: the lowest value goes first in the
        // order shown unless the order is reversed.
        @Nullable
        Object missingValue = key.missing() == SortMissing.DEFAULT
                ? null
                : missingValue(key.type(), (key.missing() == SortMissing.FIRST) != reverse);
        if ((docValues == DocValuesType.NUMERIC && !string) || (docValues == DocValuesType.SORTED && string)) {
            checkSelector(field, key.selector(), SortSelector.MIN);
            return withMissing(new SortField(field.name, type(key.type()), reverse), missingValue);
        }
        if (docValues == DocValuesType.SORTED_NUMERIC && !string) {
            checkSelector(field, key.selector(), SortSelector.MIN, SortSelector.MAX);
            return withMissing(
                    new SortedNumericSortField(field.name, type(key.type()), reverse, numeric(key.selector())),
                    missingValue);
        }
        if (docValues == DocValuesType.SORTED_SET && string) {
            return withMissing(new SortedSetSortField(field.name, reverse, set(key.selector())), missingValue);
        }
        throw new IllegalArgumentException(
                switch (docValues) {
                    case NONE -> theField(field.name) + " has no doc values to sort by";
                    case BINARY -> theField(field.name) + " has BINARY doc values, which can't be sorted by";
                    default ->
                        theField(field.name) + " has " + docValues + " doc values, which can't be sorted by as "
                                + key.type();
                });
    }

    private static SortField.Type type(SortType type) {
        return switch (type) {
            case INT -> SortField.Type.INT;
            case LONG -> SortField.Type.LONG;
            case FLOAT -> SortField.Type.FLOAT;
            case DOUBLE -> SortField.Type.DOUBLE;
            case STRING -> SortField.Type.STRING;
        };
    }

    /** The value that sorts before ({@code low}) or after every value of {@code type}. */
    private static Object missingValue(SortType type, boolean low) {
        return switch (type) {
            case INT -> low ? Integer.MIN_VALUE : Integer.MAX_VALUE;
            case LONG -> low ? Long.MIN_VALUE : Long.MAX_VALUE;
            case FLOAT -> low ? Float.NEGATIVE_INFINITY : Float.POSITIVE_INFINITY;
            case DOUBLE -> low ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
            case STRING -> low ? SortField.STRING_FIRST : SortField.STRING_LAST;
        };
    }

    private static SortedNumericSelector.Type numeric(SortSelector selector) {
        return switch (selector) {
            case MIN -> SortedNumericSelector.Type.MIN;
            case MAX -> SortedNumericSelector.Type.MAX;
            case MIDDLE_MIN, MIDDLE_MAX -> throw new IllegalStateException("No numeric selector " + selector);
        };
    }

    private static SortedSetSelector.Type set(SortSelector selector) {
        return switch (selector) {
            case MIN -> SortedSetSelector.Type.MIN;
            case MAX -> SortedSetSelector.Type.MAX;
            case MIDDLE_MIN -> SortedSetSelector.Type.MIDDLE_MIN;
            case MIDDLE_MAX -> SortedSetSelector.Type.MIDDLE_MAX;
        };
    }

    private static void checkSelector(FieldInfo field, SortSelector selector, SortSelector... allowed) {
        for (SortSelector one : allowed) {
            if (one == selector) {
                return;
            }
        }
        String kind = allowed.length == 1 ? "single-valued" : "multi-valued numeric";
        throw new IllegalArgumentException(theField(field.name) + " has " + kind + " " + field.getDocValuesType()
                + " doc values, which can't select " + selector);
    }

    private static String theField(String name) {
        return "the field '" + name + "'";
    }
}
