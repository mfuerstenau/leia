package io.github.mfuerstenau.leia.protocol.terms;

/**
 * Params of {@code startFieldTermScan}.
 *
 * @param field the field to scan
 * @param topN how many of the terms with the highest document frequency to return, from 0 to {@code
 *     MAX_TOP_N}; 0 only counts the terms
 */
public record StartFieldTermScanParams(String field, int topN) {

    /** The largest {@code topN} the server accepts. */
    public static final int MAX_TOP_N = 1000;
}
