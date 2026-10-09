package io.github.mfuerstenau.leia.protocol.document;

import java.util.List;
import java.util.Optional;

/**
 * Params of {@code startReconstruct} (ADR 0029): rebuild the terms a document was indexed with.
 *
 * @param docId the document id, from 0 to maxDoc - 1; a deleted document is rebuilt too
 * @param fields the indexed fields to rebuild, in this order; absent for every indexed field, in
 *     field number order
 */
public record StartReconstructParams(int docId, Optional<List<String>> fields) {}
