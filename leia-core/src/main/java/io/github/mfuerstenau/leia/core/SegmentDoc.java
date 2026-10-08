package io.github.mfuerstenau.leia.core;

import java.util.List;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.ReaderUtil;

/** The segment of a document, and the document's id within it. */
record SegmentDoc(LeafReader leaf, int doc) {

    static SegmentDoc of(IndexReader reader, int docId) {
        List<LeafReaderContext> leaves = reader.leaves();
        LeafReaderContext context = leaves.get(ReaderUtil.subIndex(docId, leaves));
        return new SegmentDoc(context.reader(), docId - context.docBase);
    }
}
