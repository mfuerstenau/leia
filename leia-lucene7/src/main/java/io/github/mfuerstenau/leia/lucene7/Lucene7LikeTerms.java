package io.github.mfuerstenau.leia.lucene7;

import io.github.mfuerstenau.leia.runtime.MoreLikeThisSearch.LikeTerm;
import io.github.mfuerstenau.leia.runtime.MoreLikeThisSearch.Source;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.index.Fields;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.queries.mlt.MoreLikeThis;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.similarities.ClassicSimilarity;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.CharsRefBuilder;

/**
 * The terms that 7.7's {@link MoreLikeThis} chose, with the numbers it chose them by: a copy of
 * leia-core's {@code LikeTerms} (ADR 0028, ADR 0066, ADR 0074), which works them out again with 7.7's
 * rules: the idf over all live documents, and each token counted once.
 */
final class Lucene7LikeTerms {

    private final IndexReader reader;
    private final Lucene7MoreLikeQuery query;
    private final Analyzer analyzer;
    private final Runnable check;
    private final ClassicSimilarity similarity = new ClassicSimilarity();
    /** Per field, how often each term occurs in the source, worked out once a clause needs it. */
    private final Map<String, Map<String, Integer>> frequencies = new HashMap<>();

    private Lucene7LikeTerms(IndexReader reader, Lucene7MoreLikeQuery query, Analyzer analyzer, Runnable check) {
        this.reader = reader;
        this.query = query;
        this.analyzer = analyzer;
        this.check = check;
    }

    /**
     * The terms of {@code built}, the query that {@code query} built from {@code reader} with {@code
     * analyzer}, best first.
     *
     * @param check throws to stop; it is called between fields and stored values
     */
    static List<LikeTerm> of(
            IndexReader reader, Lucene7MoreLikeQuery query, Analyzer analyzer, Query built, Runnable check)
            throws IOException {
        if (!(built instanceof BooleanQuery bool)) {
            return List.of();
        }
        Lucene7LikeTerms terms = new Lucene7LikeTerms(reader, query, analyzer, check);
        List<LikeTerm> chosen = new ArrayList<>(bool.clauses().size());
        for (BooleanClause clause : bool.clauses()) {
            Query clauseQuery = clause.getQuery() instanceof BoostQuery boost ? boost.getQuery() : clause.getQuery();
            if (clauseQuery instanceof TermQuery termQuery) {
                chosen.add(terms.term(termQuery.getTerm()));
            }
        }
        // Lucene adds the clauses weakest first.
        return List.copyOf(chosen.reversed());
    }

    private LikeTerm term(Term term) throws IOException {
        String field = term.field();
        String word = term.text();
        Map<String, Integer> fieldFrequencies = frequencies.get(field);
        if (fieldFrequencies == null) {
            fieldFrequencies = frequencies(field);
            frequencies.put(field, fieldFrequencies);
        }
        int tf = fieldFrequencies.getOrDefault(word, 0);
        int docFreq = reader.docFreq(term);
        float idf = similarity.idf(docFreq, reader.numDocs());
        return new LikeTerm(field, word, tf, docFreq, idf, tf * idf);
    }

    /** How often each term of {@code field} occurs in the source, as {@code MoreLikeThis} counts. */
    private Map<String, Integer> frequencies(String field) throws IOException {
        check.run();
        Map<String, Integer> counts = new HashMap<>();
        switch (query.source()) {
            case Source.Text(String text) -> analyze(field, text, counts);
            case Source.Doc(int docId) -> {
                Fields vectors = reader.getTermVectors(docId);
                Terms vector = vectors != null ? vectors.terms(field) : null;
                if (vector != null) {
                    count(vector, counts);
                } else {
                    for (IndexableField value : reader.document(docId).getFields(field)) {
                        String text = value.stringValue();
                        if (text != null) {
                            check.run();
                            analyze(field, text, counts);
                        }
                    }
                }
            }
        }
        return counts;
    }

    /** The term vector's terms, decoded as UTF-8, with their frequencies in the document. */
    private void count(Terms vector, Map<String, Integer> counts) throws IOException {
        TermsEnum terms = vector.iterator();
        CharsRefBuilder spare = new CharsRefBuilder();
        BytesRef bytes;
        while ((bytes = terms.next()) != null) {
            spare.copyUTF8Bytes(bytes);
            String word = spare.toString();
            if (!query.options().isNoiseWord(word)) {
                counts.merge(word, (int) terms.totalTermFreq(), Integer::sum);
            }
        }
    }

    /** The analyzed tokens of one value, up to Lucene's 5000, counted before stop words are left out. */
    private void analyze(String field, String text, Map<String, Integer> counts) throws IOException {
        try (TokenStream tokens = analyzer.tokenStream(field, new StringReader(text))) {
            CharTermAttribute termAtt = tokens.addAttribute(CharTermAttribute.class);
            tokens.reset();
            int tokenCount = 0;
            while (tokens.incrementToken()) {
                String word = termAtt.toString();
                tokenCount++;
                if (tokenCount > MoreLikeThis.DEFAULT_MAX_NUM_TOKENS_PARSED) {
                    break;
                }
                if (!query.options().isNoiseWord(word)) {
                    counts.merge(word, 1, Integer::sum);
                }
            }
            tokens.end();
        }
    }
}
