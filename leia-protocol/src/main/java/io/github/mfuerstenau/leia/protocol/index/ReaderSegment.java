package io.github.mfuerstenau.leia.protocol.index;

/**
 * A segment of the open reader (ADR 0051): the segments the documents are read from, which may
 * differ from those of the directory's newest commit.
 *
 * @param name the segment's name, such as {@code _0}; {@code ?} if the reader doesn't say
 * @param docBase the document id of the segment's first document in the opened path
 * @param maxDoc the documents of the segment, deleted ones included
 * @param numDocs the live documents of the segment
 */
public record ReaderSegment(String name, int docBase, int maxDoc, int numDocs) {}
