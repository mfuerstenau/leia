package io.github.mfuerstenau.leia.lucene5;

import io.github.mfuerstenau.leia.runtime.Search.Dates;
import io.github.mfuerstenau.leia.runtime.Search.InvalidQueryException;
import io.github.mfuerstenau.leia.runtime.StandardSearch.Options;
import io.github.mfuerstenau.leia.runtime.StandardSearch.PointType;
import io.github.mfuerstenau.leia.runtime.support.Failures;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TimeZone;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.DateTools;
import org.apache.lucene.queryparser.flexible.core.QueryNodeException;
import org.apache.lucene.queryparser.flexible.core.processors.QueryNodeProcessorPipeline;
import org.apache.lucene.queryparser.flexible.standard.StandardQueryParser;
import org.apache.lucene.queryparser.flexible.standard.config.StandardQueryConfigHandler;
import org.apache.lucene.queryparser.flexible.standard.parser.ParseException;
import org.apache.lucene.queryparser.flexible.standard.processors.TermRangeQueryNodeProcessor;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Query;

/**
 * Lucene 5.5's {@link StandardQueryParser}, set up as leia-core's {@code Queries.standard} sets up
 * Lucene 10's (ADR 0011, ADR 0066, ADR 0074), with the same messages and the same dates (ADR 0026, ADR 0069):
 * a copy of 9.12's, as the facades are (ADR 0050 §1). 5.5 has no points, and lower-cases the terms of
 * wildcards, prefixes, fuzzy queries and ranges itself, before the processor of term ranges.
 */
final class Lucene5StandardParser {

    private Lucene5StandardParser() {}

    /**
     * Parses {@code expression}.
     *
     * @param points must be empty: 5.5 has no points
     * @param analyzer analyzes the text of the expression; the caller closes it
     * @throws InvalidQueryException if the expression doesn't parse
     * @throws IllegalArgumentException if {@code points} isn't empty
     */
    // Lucene's setDateResolutionMap takes a Map with CharSequence keys, which are Strings
    @SuppressWarnings("UndefinedEquals")
    static Query parse(String expression, Options options, Map<String, PointType> points, Analyzer analyzer)
            throws InvalidQueryException {
        if (!points.isEmpty()) {
            throw new IllegalArgumentException("Lucene 5.5 has no points: " + points.keySet());
        }
        try {
            StandardQueryParser parser = new StandardQueryParser(analyzer);
            readDatesInTheParsersZone(parser);
            parser.setDefaultOperator(
                    options.defaultAnd()
                            ? StandardQueryConfigHandler.Operator.AND
                            : StandardQueryConfigHandler.Operator.OR);
            parser.setAllowLeadingWildcard(options.allowLeadingWildcard());
            parser.setPhraseSlop(options.phraseSlop());
            parser.setFuzzyPrefixLength(options.fuzzyPrefixLength());
            parser.setEnablePositionIncrements(options.enablePositionIncrements());
            Dates dates = options.dates();
            Optional.ofNullable(dates.resolution())
                    .map(Lucene5DateRangeQueryParser::resolution)
                    .ifPresent(parser::setDateResolution);
            Map<CharSequence, DateTools.Resolution> resolutions = new HashMap<>();
            dates.fields()
                    .forEach((field, resolution) ->
                            resolutions.put(field, Lucene5DateRangeQueryParser.resolution(resolution)));
            parser.setDateResolutionMap(resolutions);
            Optional.ofNullable(dates.locale()).ifPresent(parser::setLocale);
            Optional.ofNullable(dates.timeZone()).map(TimeZone::getTimeZone).ifPresent(parser::setTimeZone);
            return parser.parse(expression, options.defaultField());
        } catch (ParseException e) {
            throw new InvalidQueryException(syntaxError(expression, e), e);
        } catch (QueryNodeException e) {
            throw new InvalidQueryException(firstLine(e), e);
        } catch (BooleanQuery.TooManyClauses e) {
            throw new InvalidQueryException(Failures.message(e), e);
        }
    }

    /**
     * Replaces the parser's processor of term ranges with one that reads dates in the parser's time
     * zone (ADR 0026), as leia-core does.
     *
     * @throws IllegalStateException if the parser has no such processor
     */
    private static void readDatesInTheParsersZone(StandardQueryParser parser) {
        var pipeline = (QueryNodeProcessorPipeline) parser.getQueryNodeProcessor();
        for (int i = 0; i < pipeline.size(); i++) {
            if (pipeline.get(i).getClass() == TermRangeQueryNodeProcessor.class) {
                pipeline.set(i, new Lucene5DateRangeQueryNodeProcessor());
                return;
            }
        }
        throw new IllegalStateException("The standard query parser has no TermRangeQueryNodeProcessor");
    }

    /**
     * Where the standard parser's syntax error is, as the classic parser says it: its own message
     * leaves out the token.
     */
    private static String syntaxError(String expression, ParseException e) {
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
