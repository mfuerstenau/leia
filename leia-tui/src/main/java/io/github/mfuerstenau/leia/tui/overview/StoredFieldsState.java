package io.github.mfuerstenau.leia.tui.overview;

import io.github.mfuerstenau.leia.protocol.document.StoredFields;

/** Where the scan of the stored fields of the overview is (ADR 0113). */
public sealed interface StoredFieldsState {

    /** @param docsScanned the documents gone through so far, deleted ones included */
    record Running(long docsScanned) implements StoredFieldsState {}

    record Done(StoredFields fields) implements StoredFieldsState {}

    record Failed(String error) implements StoredFieldsState {}
}
