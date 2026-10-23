package io.github.mfuerstenau.leia.core;

import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.search.FieldComparator;
import org.apache.lucene.search.Pruning;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.SortedNumericSortField;
import org.apache.lucene.search.SortedSetSortField;

/**
 * How a field's doc values are read to sort by them (ADR 0012). Numeric doc values are longs in the
 * index, and the type says how to read them; the index doesn't record which one they hold.
 */
public enum SortType {
    INT(SortField.Type.INT),
    LONG(SortField.Type.LONG),
    FLOAT(SortField.Type.FLOAT),
    DOUBLE(SortField.Type.DOUBLE),
    STRING(SortField.Type.STRING);

    private final SortField.Type type;

    SortType(SortField.Type type) {
        this.type = type;
    }

    /** Sorts by the score, highest first unless {@code reverse} (ADR 0025). */
    public static SortField score(boolean reverse) {
        return new SortField(null, SortField.Type.SCORE, reverse);
    }

    /** Sorts by the document id, lowest first unless {@code reverse} (ADR 0025). */
    public static SortField doc(boolean reverse) {
        return new SortField(null, SortField.Type.DOC, reverse);
    }

    /**
     * Sorts by {@code field} with Lucene's default selector ({@code MIN}) and missing value.
     *
     * @see #sortField(FieldInfo, boolean, SortSelector, SortMissing)
     */
    public SortField sortField(FieldInfo field, boolean reverse) {
        return sortField(field, reverse, SortSelector.MIN, SortMissing.DEFAULT);
    }

    /**
     * Sorts by {@code field} with the sort field class of its doc values type (ADR 0012, ADR 0025).
     *
     * <p>The sort reads every match's doc value and never prunes: Lucene skips documents through
     * the points, terms or doc values skipper of the same field, assuming they hold the same values
     * in the type sorted by. It throws if the points' width differs, and skips the wrong documents
     * if only their encoding does, while the type here is the user's guess (ADR 0012).
     *
     * @param reverse whether the largest value comes first
     * @param selector which value of a multi-valued field a document sorts by
     * @param missing where documents without a value go, in the order shown
     * @throws IllegalArgumentException if the field has no doc values of a type that this type can
     *     sort, or the selector doesn't apply to them
     */
    public SortField sortField(FieldInfo field, boolean reverse, SortSelector selector, SortMissing missing) {
        DocValuesType docValues = field.getDocValuesType();
        boolean string = this == STRING;
        // Lucene orders the missing value before it reverses: the lowest value goes first in the
        // order shown unless the order is reversed.
        Object missingValue =
                missing == SortMissing.DEFAULT ? null : missingValue((missing == SortMissing.FIRST) != reverse);
        if ((docValues == DocValuesType.NUMERIC && !string) || (docValues == DocValuesType.SORTED && string)) {
            checkSelector(field, selector, SortSelector.MIN);
            return new SortField(field.name, type, reverse, missingValue) {
                @Override
                public FieldComparator<?> getComparator(int numHits, Pruning pruning) {
                    return super.getComparator(numHits, Pruning.NONE);
                }
            };
        }
        if (docValues == DocValuesType.SORTED_NUMERIC && !string) {
            checkSelector(field, selector, SortSelector.MIN, SortSelector.MAX);
            return new SortedNumericSortField(field.name, type, reverse, selector.numeric(), missingValue) {
                @Override
                public FieldComparator<?> getComparator(int numHits, Pruning pruning) {
                    return super.getComparator(numHits, Pruning.NONE);
                }
            };
        }
        if (docValues == DocValuesType.SORTED_SET && string) {
            return new SortedSetSortField(field.name, reverse, selector.set(), missingValue) {
                @Override
                public FieldComparator<?> getComparator(int numHits, Pruning pruning) {
                    return super.getComparator(numHits, Pruning.NONE);
                }
            };
        }
        throw new IllegalArgumentException(
                switch (docValues) {
                    case NONE -> theField(field.name) + " has no doc values to sort by";
                    case BINARY -> theField(field.name) + " has BINARY doc values, which can't be sorted by";
                    default ->
                        theField(field.name) + " has " + docValues + " doc values, which can't be sorted by as " + this;
                });
    }

    /** The value that sorts before ({@code low}) or after every value of this type. */
    private Object missingValue(boolean low) {
        return switch (this) {
            case INT -> low ? Integer.MIN_VALUE : Integer.MAX_VALUE;
            case LONG -> low ? Long.MIN_VALUE : Long.MAX_VALUE;
            case FLOAT -> low ? Float.NEGATIVE_INFINITY : Float.POSITIVE_INFINITY;
            case DOUBLE -> low ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
            case STRING -> low ? SortField.STRING_FIRST : SortField.STRING_LAST;
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
