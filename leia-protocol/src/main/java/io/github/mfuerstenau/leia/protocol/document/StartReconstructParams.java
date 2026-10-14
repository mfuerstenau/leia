package io.github.mfuerstenau.leia.protocol.document;

import java.util.List;
import java.util.Optional;

/**
 * Params of {@code startReconstruct} (ADR 0029): rebuild the terms and points a document was
 * indexed with (ADR 0041).
 *
 * @param docId the document id, from 0 to maxDoc - 1; a deleted document is rebuilt too
 * @param fields the fields with postings or points to rebuild, in this order; absent for every such
 *     field, in field number order
 */
public record StartReconstructParams(int docId, Optional<List<String>> fields) {}
