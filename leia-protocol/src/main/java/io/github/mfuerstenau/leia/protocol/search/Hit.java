package io.github.mfuerstenau.leia.protocol.search;

import java.util.List;

/**
 * A matching document.
 *
 * @param docId the document id
 * @param score the document's score for the query, also when the hits are sorted by fields
 * @param sortValues the values the hit was sorted by, one per sort key; empty without a sort
 * @param fields the document's stored values in the fields asked for, in the order of each field's
 *     first value; values longer than {@link SearchResults#MAX_VALUE_LENGTH} bytes are cut
 */
public record Hit(int docId, float score, List<SortValue> sortValues, List<HitField> fields) {}
