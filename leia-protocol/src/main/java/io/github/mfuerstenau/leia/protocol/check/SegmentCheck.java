package io.github.mfuerstenau.leia.protocol.check;

import java.util.Optional;

/**
 * The outcome of checking one segment.
 *
 * @param name the segment's name, such as {@code _0}
 * @param maxDoc its documents, deleted ones included
 * @param codec its codec, if its {@code .si} file could be read
 * @param compound whether it is a compound file
 * @param files the files it consists of
 * @param sizeBytes the size of those files
 * @param hasDeletions whether it has deleted documents
 * @param openReaderPassed whether a reader could open it
 * @param error the test it failed and why, with the causes; absent if it passed
 */
public record SegmentCheck(
        String name,
        int maxDoc,
        Optional<String> codec,
        boolean compound,
        int files,
        long sizeBytes,
        boolean hasDeletions,
        boolean openReaderPassed,
        Optional<String> error) {}
