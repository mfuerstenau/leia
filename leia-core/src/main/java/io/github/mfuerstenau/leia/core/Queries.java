package io.github.mfuerstenau.leia.core;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.util.BytesRef;

/** Builds the queries that {@link ReadOnlyIndex#search} runs (ADR 0010). */
public final class Queries {

    /** How the classic query parser combines clauses without an operator. */
    public enum Operator {
        OR,
        AND
    }

    private Queries() {}

    /**
     * Parses {@code expression} with Lucene's classic {@link QueryParser} and {@link
     * StandardAnalyzer}, otherwise with the parser's defaults.
     *
     * @param defaultField the field of terms that name none
     * @throws InvalidQueryException if the expression doesn't parse
     */
    public static Query classic(String expression, String defaultField, Operator operator)
            throws InvalidQueryException {
        try (Analyzer analyzer = new StandardAnalyzer()) {
            QueryParser parser = new QueryParser(defaultField, analyzer);
            parser.setDefaultOperator(
                    switch (operator) {
                        case OR -> QueryParser.Operator.OR;
                        case AND -> QueryParser.Operator.AND;
                    });
            return parser.parse(expression);
        } catch (ParseException e) {
            // The first line says what is wrong and where; the rest lists every token that could follow.
            throw new InvalidQueryException(e.getMessage().lines().findFirst().orElse("Cannot parse"), e);
        } catch (IndexSearcher.TooManyClauses e) {
            throw new InvalidQueryException(e.getMessage(), e);
        }
    }

    /** A query for exactly this term, which is neither analyzed nor split. */
    public static Query term(String field, BytesRef term) {
        return new TermQuery(new Term(field, term));
    }
}
