package io.github.mfuerstenau.leia.protocol.document;

import java.util.List;

/**
 * The fields the documents of the open index store values in (ADR 0113). The index doesn't record
 * which fields are stored, so this is what reading every document's stored fields found.
 *
 * @param fields the fields with a stored value in at least one document, deleted ones included, by
 *     name
 * @param segments the segments of the index
 * @param cachedSegments those of them whose stored fields came from the cache instead of the index
 * @param tookMillis how long the scan took
 */
public record StoredFields(List<StoredField> fields, int segments, int cachedSegments, long tookMillis) {}
