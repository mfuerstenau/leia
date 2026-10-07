package io.github.mfuerstenau.leia.core;

import java.text.NumberFormat;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.queryparser.flexible.core.QueryNodeException;
import org.apache.lucene.queryparser.flexible.standard.StandardQueryParser;
import org.apache.lucene.queryparser.flexible.standard.config.PointsConfig;
import org.apache.lucene.queryparser.flexible.standard.config.StandardQueryConfigHandler;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.util.BytesRef;

/** Builds the queries that {@link ReadOnlyIndex#search} runs (ADR 0010, ADR 0011). */
public final class Queries {

    /** How a query parser combines clauses without an operator. */
    public enum Operator {
        OR,
        AND
    }

    private Queries() {}

    /**
     * Parses {@code expression} with Lucene's classic {@link QueryParser} and {@link
     * StandardAnalyzer}.
     *
     * @param defaultField the field of terms that name none
     * @throws InvalidQueryException if the expression doesn't parse
     */
    public static Query classic(String expression, String defaultField, ParserOptions options, ClassicOptions classic)
            throws InvalidQueryException {
        try (Analyzer analyzer = new StandardAnalyzer()) {
            QueryParser parser = new QueryParser(defaultField, analyzer);
            parser.setDefaultOperator(
                    switch (options.defaultOperator()) {
                        case OR -> QueryParser.Operator.OR;
                        case AND -> QueryParser.Operator.AND;
                    });
            parser.setAllowLeadingWildcard(options.allowLeadingWildcard());
            parser.setPhraseSlop(options.phraseSlop());
            parser.setFuzzyPrefixLength(options.fuzzyPrefixLength());
            parser.setEnablePositionIncrements(options.enablePositionIncrements());
            parser.setFuzzyMinSim(classic.fuzzyMaxEdits());
            // In this order: the parser refuses auto-generated phrases while it doesn't split.
            parser.setSplitOnWhitespace(classic.splitOnWhitespace());
            parser.setAutoGeneratePhraseQueries(classic.autoGeneratePhraseQueries());
            return parser.parse(expression);
        } catch (ParseException e) {
            // The first line says what is wrong and where; the rest lists every token that could follow.
            throw new InvalidQueryException(firstLine(e), e);
        } catch (IndexSearcher.TooManyClauses e) {
            throw new InvalidQueryException(e.getMessage(), e);
        }
    }

    /**
     * Parses {@code expression} with Lucene's {@link StandardQueryParser} and {@link
     * StandardAnalyzer}. A range or a value on a field in {@code points} becomes a point query for
     * values of its type; other fields are text. A fuzzy term without a distance always allows 2
     * edits: the parser ignores {@code setFuzzyMinSim}.
     *
     * @param defaultField the field of terms that name none
     * @throws InvalidQueryException if the expression doesn't parse, or a point value is not a number
     */
    public static Query standard(
            String expression, String defaultField, ParserOptions options, Map<String, PointType> points)
            throws InvalidQueryException {
        try (Analyzer analyzer = new StandardAnalyzer()) {
            StandardQueryParser parser = new StandardQueryParser(analyzer);
            parser.setDefaultOperator(
                    switch (options.defaultOperator()) {
                        case OR -> StandardQueryConfigHandler.Operator.OR;
                        case AND -> StandardQueryConfigHandler.Operator.AND;
                    });
            parser.setAllowLeadingWildcard(options.allowLeadingWildcard());
            parser.setPhraseSlop(options.phraseSlop());
            parser.setFuzzyPrefixLength(options.fuzzyPrefixLength());
            parser.setEnablePositionIncrements(options.enablePositionIncrements());
            Map<String, PointsConfig> configs = new HashMap<>();
            points.forEach((field, type) -> configs.put(field, new PointsConfig(numberFormat(type), type.type())));
            parser.setPointsConfigMap(configs);
            return parser.parse(expression, defaultField);
        } catch (org.apache.lucene.queryparser.flexible.standard.parser.ParseException e) {
            throw new InvalidQueryException(syntaxError(expression, e), e);
        } catch (QueryNodeException e) {
            throw new InvalidQueryException(firstLine(e), e);
        } catch (IndexSearcher.TooManyClauses e) {
            throw new InvalidQueryException(e.getMessage(), e);
        }
    }

    /** A query for exactly this term, which is neither analyzed nor split. */
    public static Query term(String field, BytesRef term) {
        return new TermQuery(new Term(field, term));
    }

    /** Whole numbers for integer types and decimals for the others, as Luke reads them. */
    private static NumberFormat numberFormat(PointType type) {
        return switch (type) {
            case INT, LONG -> NumberFormat.getIntegerInstance(Locale.ROOT);
            case FLOAT, DOUBLE -> NumberFormat.getNumberInstance(Locale.ROOT);
        };
    }

    /**
     * Where the standard parser's syntax error is, as the classic parser says it: its own message
     * leaves out the token.
     */
    private static String syntaxError(
            String expression, org.apache.lucene.queryparser.flexible.standard.parser.ParseException e) {
        if (e.currentToken == null || e.currentToken.next == null) {
            return firstLine(e);
        }
        var next = e.currentToken.next;
        String token = next.image.isEmpty() ? "<EOF>" : next.image;
        return "Cannot parse '" + expression + "': Encountered \"" + token + "\" at column " + next.beginColumn + ".";
    }

    private static String firstLine(Exception e) {
        String message = e.getMessage();
        return message == null ? "Cannot parse" : message.lines().findFirst().orElse("Cannot parse");
    }
}
