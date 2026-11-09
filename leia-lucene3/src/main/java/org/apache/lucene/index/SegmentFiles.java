package org.apache.lucene.index;

import java.io.IOException;
import java.util.List;
import java.util.TreeSet;
import org.jspecify.annotations.NullMarked;

/**
 * The files of a segment, for leia's cache of which fields its documents store (ADR 0113). A
 * segment reader's {@link SegmentInfo} is package-private in Lucene 3.6, so this class lives in
 * Lucene's package, as {@link RawPostings} does.
 */
@NullMarked
public final class SegmentFiles {

    private SegmentFiles() {}

    /** The files of {@code segment}, sorted, without its deletions file. */
    public static List<String> files(SegmentReader segment) throws IOException {
        SegmentInfo info = segment.getSegmentInfo();
        TreeSet<String> files = new TreeSet<>(info.files());
        String deletions = info.getDelFileName();
        if (deletions != null) {
            files.remove(deletions);
        }
        return List.copyOf(files);
    }
}
