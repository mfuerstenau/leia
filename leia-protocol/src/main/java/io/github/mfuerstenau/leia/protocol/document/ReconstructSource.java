package io.github.mfuerstenau.leia.protocol.document;

/** Where the terms of a {@link ReconstructedField} come from; serialized as its name. */
public enum ReconstructSource {
    /** The document's term vector, which holds all that the postings hold. */
    TERM_VECTOR,
    /** The postings of every term of the field in the document's segment. */
    POSTINGS
}
