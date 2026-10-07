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

    /**
     * Sorts by {@code field} with the sort field class of its doc values type, Lucene's default
     * selector ({@code MIN}) and Lucene's default missing value.
     *
     * <p>The sort reads every match's doc value and never prunes: Lucene skips documents through
     * the points, terms or doc values skipper of the same field, assuming they hold the same values
     * in the type sorted by. It throws if the points' width differs, and skips the wrong documents
     * if only their encoding does, while the type here is the user's guess (ADR 0012).
     *
     * @param reverse whether the largest value comes first
     * @throws IllegalArgumentException if the field has no doc values of a type that this type can
     *     sort
     */
    public SortField sortField(FieldInfo field, boolean reverse) {
        DocValuesType docValues = field.getDocValuesType();
        boolean string = this == STRING;
        if ((docValues == DocValuesType.NUMERIC && !string) || (docValues == DocValuesType.SORTED && string)) {
            return new SortField(field.name, type, reverse) {
                @Override
                public FieldComparator<?> getComparator(int numHits, Pruning pruning) {
                    return super.getComparator(numHits, Pruning.NONE);
                }
            };
        }
        if (docValues == DocValuesType.SORTED_NUMERIC && !string) {
            return new SortedNumericSortField(field.name, type, reverse) {
                @Override
                public FieldComparator<?> getComparator(int numHits, Pruning pruning) {
                    return super.getComparator(numHits, Pruning.NONE);
                }
            };
        }
        if (docValues == DocValuesType.SORTED_SET && string) {
            return new SortedSetSortField(field.name, reverse) {
                @Override
                public FieldComparator<?> getComparator(int numHits, Pruning pruning) {
                    return super.getComparator(numHits, Pruning.NONE);
                }
            };
        }
        throw new IllegalArgumentException(
                switch (docValues) {
                    case NONE -> "the field '" + field.name + "' has no doc values to sort by";
                    case BINARY -> "the field '" + field.name + "' has BINARY doc values, which can't be sorted by";
                    default ->
                        "the field '" + field.name + "' has " + docValues + " doc values, which can't be sorted by as "
                                + this;
                });
    }
}
