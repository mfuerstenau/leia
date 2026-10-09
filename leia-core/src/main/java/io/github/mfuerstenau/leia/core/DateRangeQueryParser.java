package io.github.mfuerstenau.leia.core;

import java.text.DateFormat;
import java.util.Date;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.DateTools;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.Query;

/**
 * Lucene's classic query parser, which reads range bounds as dates in its own time zone rather than
 * the JVM's default (ADR 0026). Adapted from Lucene's {@code QueryParserBase.getRangeQuery}
 * (Apache License 2.0).
 */
final class DateRangeQueryParser extends QueryParser {

    DateRangeQueryParser(String defaultField, Analyzer analyzer) {
        super(defaultField, analyzer);
    }

    /** As Lucene's: a bound that isn't a date, or without a resolution, stays text. */
    @Override
    protected Query getRangeQuery(
            String field, String part1, String part2, boolean startInclusive, boolean endInclusive)
            throws ParseException {
        DateFormat format = DateRanges.format(getLocale(), getTimeZone());
        DateTools.Resolution resolution = getDateResolution(field);
        try {
            part1 = DateTools.dateToString(format.parse(part1), resolution);
        } catch (Exception _) {
            // Not a date, an open bound or no resolution: the bound stays as it is.
        }
        try {
            Date date = format.parse(part2);
            if (endInclusive) {
                date = DateRanges.endOfDay(date, getTimeZone(), getLocale());
            }
            part2 = DateTools.dateToString(date, resolution);
        } catch (Exception _) {
            // As above.
        }
        return newRangeQuery(field, part1, part2, startInclusive, endInclusive);
    }
}
