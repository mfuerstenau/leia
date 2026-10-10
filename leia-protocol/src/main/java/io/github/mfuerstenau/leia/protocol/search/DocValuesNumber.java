package io.github.mfuerstenau.leia.protocol.search;

/**
 * How a number field's doc values encode their numbers, which the index doesn't record (ADR 0033);
 * serialized as its name.
 */
public enum DocValuesNumber {
    /** The long itself, as {@code NumericDocValuesField} and {@code LongField} write it. */
    LONG,
    /** The raw bits of a double, as {@code DoubleDocValuesField} writes them. */
    DOUBLE,
    /** {@code NumericUtils.doubleToSortableLong}, as {@code DoubleField} writes it. */
    SORTABLE_DOUBLE,
    /** The raw bits of a float, as {@code FloatDocValuesField} writes them. */
    FLOAT,
    /** {@code NumericUtils.floatToSortableInt}, as {@code FloatField} writes it. */
    SORTABLE_FLOAT
}
