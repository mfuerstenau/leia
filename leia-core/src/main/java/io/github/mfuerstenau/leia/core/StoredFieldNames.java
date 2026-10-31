package io.github.mfuerstenau.leia.core;

import java.io.IOException;
import java.util.function.ObjIntConsumer;
import org.apache.lucene.index.CodecReader;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FilterLeafReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.StoredFieldVisitor;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.util.Bits;

/**
 * Which fields the documents of a segment store values in, without reading the values (ADR 0113): a
 * visitor that notes each field and skips its value, which spares decoding and copying it, though
 * not decompressing it. The documents are read in order through the codec's merge instance, which
 * decompresses each block of documents once, where the leaf's own stored fields decompress a block
 * again for each document: ten times faster with LZ4, ninety with DEFLATE.
 */
final class StoredFieldNames {

    /** How many documents the scan reads between two progress reports. */
    static final int PROGRESS_INTERVAL = 256;

    private StoredFieldNames() {}

    /**
     * Passes the field of each stored value of every document of {@code leaf}, deleted ones too, to
     * {@code sink} with the document's id in the leaf, in document order.
     *
     * @throws java.util.concurrent.CancellationException from {@code progress}, to stop the scan
     */
    static void scan(LeafReader leaf, ObjIntConsumer<String> sink, ScanProgress progress) throws IOException {
        StoredFields stored = sequential(leaf);
        Names names = new Names(sink);
        int maxDoc = leaf.maxDoc();
        for (int doc = 0; doc < maxDoc; doc++) {
            if (doc % PROGRESS_INTERVAL == 0) {
                progress.scanned(doc);
            }
            names.doc = doc;
            stored.document(doc, names);
        }
        progress.scanned(maxDoc);
    }

    /**
     * A reader of the stored fields of {@code leaf} for one pass in document order on this thread: the
     * codec's merge instance, or the leaf's own if it isn't a segment's.
     */
    static StoredFields sequential(LeafReader leaf) throws IOException {
        if (FilterLeafReader.unwrap(leaf) instanceof CodecReader codec && codec.getFieldsReader() != null) {
            return codec.getFieldsReader().getMergeInstance();
        }
        return leaf.storedFields();
    }

    /** The deleted documents of {@code leaf}, ascending. */
    static int[] deleted(LeafReader leaf) {
        Bits liveDocs = leaf.getLiveDocs();
        if (liveDocs == null) {
            return new int[0];
        }
        int[] deleted = new int[leaf.maxDoc() - leaf.numDocs()];
        int count = 0;
        for (int doc = 0; doc < leaf.maxDoc() && count < deleted.length; doc++) {
            if (!liveDocs.get(doc)) {
                deleted[count++] = doc;
            }
        }
        return deleted;
    }

    /** Notes the field of each value and skips the value. */
    private static final class Names extends StoredFieldVisitor {

        private final ObjIntConsumer<String> sink;
        int doc;

        Names(ObjIntConsumer<String> sink) {
            this.sink = sink;
        }

        @Override
        public Status needsField(FieldInfo field) {
            sink.accept(field.name, doc);
            return Status.NO;
        }
    }
}
