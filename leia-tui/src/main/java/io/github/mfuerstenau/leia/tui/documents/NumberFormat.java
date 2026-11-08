package io.github.mfuerstenau.leia.tui.documents;

import io.github.mfuerstenau.leia.client.values.DocValuesNumbers;
import io.github.mfuerstenau.leia.protocol.search.DocValuesNumber;

/**
 * How the raw numbers of numeric doc values are shown. The index doesn't record what they encode,
 * so {@code x} cycles through the encodings Lucene's fields use.
 */
public enum NumberFormat {
    LONG,
    /** {@code DoubleDocValuesField}: the raw bits of a double. */
    DOUBLE,
    /** {@code NumericUtils.doubleToSortableLong}, as for sorted numeric doubles. */
    SORTABLE_DOUBLE,
    /** {@code FloatDocValuesField}: the raw bits of a float. */
    FLOAT,
    /** {@code NumericUtils.floatToSortableInt}. */
    SORTABLE_FLOAT;

    NumberFormat next() {
        return switch (this) {
            case LONG -> DOUBLE;
            case DOUBLE -> SORTABLE_DOUBLE;
            case SORTABLE_DOUBLE -> FLOAT;
            case FLOAT -> SORTABLE_FLOAT;
            case SORTABLE_FLOAT -> LONG;
        };
    }

    /** The same encoding on the wire, for a doc values query. */
    DocValuesNumber docValuesNumber() {
        return switch (this) {
            case LONG -> DocValuesNumber.LONG;
            case DOUBLE -> DocValuesNumber.DOUBLE;
            case SORTABLE_DOUBLE -> DocValuesNumber.SORTABLE_DOUBLE;
            case FLOAT -> DocValuesNumber.FLOAT;
            case SORTABLE_FLOAT -> DocValuesNumber.SORTABLE_FLOAT;
        };
    }

    String label() {
        return switch (this) {
            case LONG -> "long";
            case DOUBLE -> "double bits";
            case SORTABLE_DOUBLE -> "sortable double";
            case FLOAT -> "float bits";
            case SORTABLE_FLOAT -> "sortable float";
        };
    }

    /** {@code number} decoded as this format. Floats print as Rust's {@code {:?}} prints them. */
    public String format(long number) {
        return DocValuesNumbers.format(docValuesNumber(), number);
    }
}
