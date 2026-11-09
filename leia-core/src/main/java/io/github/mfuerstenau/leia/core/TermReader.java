package io.github.mfuerstenau.leia.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.MultiBits;
import org.apache.lucene.index.MultiTerms;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;
import org.jspecify.annotations.Nullable;

/**
 * Pages through the terms of a field and the postings of a term (ADR 0007). Every call seeks
 * afresh, so nothing is kept between calls, and reads at most one page.
 */
final class TermReader {

    private TermReader() {}

    static TermPage terms(IndexReader reader, String field, @Nullable BytesRef from, int limit) throws IOException {
        Terms terms = MultiTerms.getTerms(reader, field);
        if (terms == null) {
            return new TermPage(List.of(), null);
        }
        TermsEnum termsEnum = terms.iterator();
        BytesRef term;
        if (from != null) {
            term = termsEnum.seekCeil(from) == TermsEnum.SeekStatus.END ? null : termsEnum.term();
        } else {
            term = termsEnum.next();
        }
        List<TermPage.Entry> entries = new ArrayList<>();
        while (term != null && entries.size() < limit) {
            entries.add(new TermPage.Entry(BytesRef.deepCopyOf(term), termsEnum.docFreq(), termsEnum.totalTermFreq()));
            term = termsEnum.next();
        }
        return new TermPage(List.copyOf(entries), term != null ? BytesRef.deepCopyOf(term) : null);
    }

    static Optional<PostingsPage> postings(
            IndexReader reader,
            FieldInfo field,
            BytesRef term,
            int fromDoc,
            int limit,
            PostingsPage.@Nullable Filter filter,
            int maxPositions)
            throws IOException {
        Terms terms = MultiTerms.getTerms(reader, field.getName());
        if (terms == null) {
            return Optional.empty();
        }
        TermsEnum termsEnum = terms.iterator();
        if (!termsEnum.seekExact(term)) {
            return Optional.empty();
        }
        boolean hasFreqs = field.getIndexOptions().compareTo(IndexOptions.DOCS_AND_FREQS) >= 0;
        boolean withPositions = maxPositions > 0;
        int flags = withPositions ? PostingsEnum.ALL : hasFreqs ? PostingsEnum.FREQS : PostingsEnum.NONE;
        PostingsEnum postings = termsEnum.postings(null, flags);
        Bits liveDocs = MultiBits.getLiveDocs(reader);
        List<PostingsPage.Posting> page = new ArrayList<>();
        // Advancing past the last document is not allowed.
        int doc = fromDoc < reader.maxDoc() ? postings.advance(fromDoc) : DocIdSetIterator.NO_MORE_DOCS;
        doc = shown(postings, doc, filter);
        while (doc != DocIdSetIterator.NO_MORE_DOCS && page.size() < limit) {
            int docId = doc;
            page.add(new PostingsPage.Posting(
                    doc,
                    liveDocs != null && !liveDocs.get(doc),
                    hasFreqs ? Integer.valueOf(postings.freq()) : null,
                    filter != null ? Boolean.valueOf(filter.filter().contains(docId)) : null,
                    withPositions
                            ? terms.hasPositions()
                                    ? TermPosition.read(
                                            postings, Math.min(postings.freq(), maxPositions), true, terms.hasOffsets())
                                    : List.of()
                            : null));
            doc = shown(postings, postings.nextDoc(), filter);
        }
        return Optional.of(new PostingsPage(
                termsEnum.docFreq(),
                termsEnum.totalTermFreq(),
                List.copyOf(page),
                doc == DocIdSetIterator.NO_MORE_DOCS ? null : Integer.valueOf(doc)));
    }

    /**
     * The first posting from {@code doc}, the one {@code postings} is on, that the page shows: with
     * {@code onlyMatches}, the postings and the filter leapfrog to the next document they share.
     */
    private static int shown(PostingsEnum postings, int doc, PostingsPage.@Nullable Filter filter) throws IOException {
        if (filter == null || !filter.onlyMatches()) {
            return doc;
        }
        DocFilter matches = filter.filter();
        while (doc != DocIdSetIterator.NO_MORE_DOCS) {
            int match = matches.next(doc);
            if (match == doc || match == DocIdSetIterator.NO_MORE_DOCS) {
                return match;
            }
            doc = postings.advance(match);
        }
        return doc;
    }

    /** Read from the document's segment, like the rest of a document (ADR 0006). */
    static Optional<List<TermPosition>> positions(IndexReader reader, int docId, String field, BytesRef term)
            throws IOException {
        SegmentDoc segmentDoc = SegmentDoc.of(reader, docId);
        Terms terms = segmentDoc.leaf().terms(field);
        if (terms == null) {
            return Optional.empty();
        }
        TermsEnum termsEnum = terms.iterator();
        if (!termsEnum.seekExact(term)) {
            return Optional.empty();
        }
        PostingsEnum postings = termsEnum.postings(null, PostingsEnum.ALL);
        if (postings.advance(segmentDoc.doc()) != segmentDoc.doc()) {
            return Optional.empty();
        }
        // Offsets are only indexed together with positions.
        return Optional.of(
                terms.hasPositions()
                        ? TermPosition.read(postings, postings.freq(), true, terms.hasOffsets())
                        : List.of());
    }
}
