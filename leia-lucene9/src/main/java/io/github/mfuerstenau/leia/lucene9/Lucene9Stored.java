package io.github.mfuerstenau.leia.lucene9;

import java.io.IOException;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.DocumentStoredFieldVisitor;
import org.apache.lucene.index.CodecReader;
import org.apache.lucene.index.Fields;
import org.apache.lucene.index.FilterLeafReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.StoredFieldVisitor;

/**
 * The stored fields and term vectors of a reader's documents, through 9.5's {@code StoredFields} and
 * {@code TermVectors} where the runtime's Lucene has them, and otherwise through the reader's own
 * methods of 9.0, which 9.12 still has (ADR 0091). Only {@link Current} names the classes of 9.5, so
 * that this class loads on 9.0.
 */
final class Lucene9Stored {

    /** The stored fields of a reader, for one document or many: an instance is for one thread. */
    interface Reader {

        void document(int docId, StoredFieldVisitor visitor) throws IOException;

        default Document document(int docId) throws IOException {
            DocumentStoredFieldVisitor visitor = new DocumentStoredFieldVisitor();
            document(docId, visitor);
            return visitor.getDocument();
        }
    }

    private Lucene9Stored() {}

    /** A reader of {@code reader}'s stored fields, for one scan of its documents. */
    static Reader of(IndexReader reader) throws IOException {
        if (Lucene9Api.STORED_FIELDS) {
            return Current.of(reader);
        }
        return reader::document;
    }

    /**
     * A reader of the stored fields of {@code leaf} for one pass in document order on this thread
     * (ADR 0113): from 9.5 on, the codec's merge instance, which decompresses each block of documents
     * once, where the leaf decompresses a block for each document; before, the leaf's own.
     */
    static Reader sequential(LeafReader leaf) throws IOException {
        if (Lucene9Api.STORED_FIELDS) {
            return Current.sequential(leaf);
        }
        return leaf::document;
    }

    /** The term vectors of {@code docId} of {@code reader}; null if it has none. */
    static Fields termVectors(IndexReader reader, int docId) throws IOException {
        return Lucene9Api.STORED_FIELDS ? Current.termVectors(reader, docId) : reader.getTermVectors(docId);
    }

    /** Through 9.5's classes, loaded only where they are. */
    private static final class Current {

        static Reader of(IndexReader reader) throws IOException {
            return reader.storedFields()::document;
        }

        static Reader sequential(LeafReader leaf) throws IOException {
            if (FilterLeafReader.unwrap(leaf) instanceof CodecReader codec && codec.getFieldsReader() != null) {
                return codec.getFieldsReader().getMergeInstance()::document;
            }
            return leaf.storedFields()::document;
        }

        static Fields termVectors(IndexReader reader, int docId) throws IOException {
            return reader.termVectors().get(docId);
        }
    }
}
