package io.github.mfuerstenau.leia.protocol.terms;

import io.github.mfuerstenau.leia.protocol.document.BytesValue;

/**
 * Params of {@code getPositions}.
 *
 * @param docId the document id, from 0 to maxDoc - 1
 * @param field the field
 * @param term the term
 */
public record GetPositionsParams(int docId, String field, BytesValue term) {}
