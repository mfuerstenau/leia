package io.github.mfuerstenau.leia.core;

import java.io.IOException;
import java.util.Objects;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.queries.mlt.MoreLikeThis;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.QueryVisitor;

/**
 * Documents like one document of the index (ADR 0013). The query only holds the settings: it
 * rewrites to the {@link MoreLikeThis} query, which is built from the searcher's reader. Rewriting
 * happens inside the search, so it can be cancelled through the {@code ExitableDirectoryReader}
 * like the search (ADR 0010).
 */
final class MoreLikeDocumentQuery extends Query {

    private final int docId;
    private final MoreLikeThisOptions options;

    MoreLikeDocumentQuery(int docId, MoreLikeThisOptions options) {
        this.docId = docId;
        this.options = Objects.requireNonNull(options);
    }

    /**
     * The terms of the document's term vectors or, for fields without one, of its stored strings
     * analyzed with {@link StandardAnalyzer}, as {@code SHOULD} clauses of a {@code BooleanQuery}.
     */
    @Override
    public Query rewrite(IndexSearcher searcher) throws IOException {
        try (Analyzer analyzer = new StandardAnalyzer()) {
            MoreLikeThis mlt = new MoreLikeThis(searcher.getIndexReader());
            mlt.setAnalyzer(analyzer);
            mlt.setFieldNames(options.fields().toArray(String[]::new));
            mlt.setMinTermFreq(options.minTermFreq());
            mlt.setMinDocFreq(options.minDocFreq());
            mlt.setMaxDocFreq(options.maxDocFreq().orElse(MoreLikeThis.DEFAULT_MAX_DOC_FREQ));
            mlt.setMaxQueryTerms(options.maxQueryTerms());
            mlt.setBoost(options.boost());
            return mlt.like(docId);
        }
    }

    @Override
    public void visit(QueryVisitor visitor) {
        visitor.visitLeaf(this);
    }

    /** The document and the settings, such as {@code like(doc 42, fields [body], minTermFreq 2, …)}. */
    @Override
    public String toString(String field) {
        StringBuilder text = new StringBuilder("like(doc ")
                .append(docId)
                .append(", fields ")
                .append(options.fields())
                .append(", minTermFreq ")
                .append(options.minTermFreq())
                .append(", minDocFreq ")
                .append(options.minDocFreq());
        options.maxDocFreq().ifPresent(max -> text.append(", maxDocFreq ").append(max));
        text.append(", maxQueryTerms ").append(options.maxQueryTerms());
        if (options.boost()) {
            text.append(", boost");
        }
        return text.append(')').toString();
    }

    @Override
    public boolean equals(Object other) {
        return sameClassAs(other)
                && docId == ((MoreLikeDocumentQuery) other).docId
                && options.equals(((MoreLikeDocumentQuery) other).options);
    }

    @Override
    public int hashCode() {
        return Objects.hash(classHash(), docId, options);
    }
}
