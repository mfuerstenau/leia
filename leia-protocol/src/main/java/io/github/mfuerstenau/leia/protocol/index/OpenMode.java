package io.github.mfuerstenau.leia.protocol.index;

/** How {@code openIndex} opens the indexes of a path (ADR 0021); serialized as its name. */
public enum OpenMode {
    /** With a reader: everything can be read. */
    READER,
    /** Only the {@code Directory}, for indexes that a reader can't open: only the commits can be read. */
    DIRECTORY_ONLY
}
