package io.github.mfuerstenau.leia.lucene5;

import io.github.mfuerstenau.leia.runtime.Search.DateResolution;
import io.github.mfuerstenau.leia.runtime.Search.Dates;
import io.github.mfuerstenau.leia.runtime.support.DateRanges;
import java.text.DateFormat;
import java.util.Date;
import java.util.Optional;
import java.util.TimeZone;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.DateTools;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.Query;

/**
 * Lucene 5.5's classic query parser, which reads range bounds as dates in its own time zone rather
 * than the JVM's default, as leia-core's {@code DateRangeQueryParser} reads them on Lucene 10 (ADR
 * 0026, ADR 0069). Adapted from Lucene's {@code QueryParserBase.getRangeQuery} (Apache License 2.0).
 */
final class Lucene5DateRangeQueryParser extends QueryParser {

    Lucene5DateRangeQueryParser(String defaultField, Analyzer analyzer, Dates dates) {
        super(defaultField, analyzer);
        Optional.ofNullable(dates.resolution())
                .map(Lucene5DateRangeQueryParser::resolution)
                .ifPresent(this::setDateResolution);
        dates.fields().forEach((field, resolution) -> setDateResolution(field, resolution(resolution)));
        Optional.ofNullable(dates.locale()).ifPresent(this::setLocale);
        Optional.ofNullable(dates.timeZone()).map(TimeZone::getTimeZone).ifPresent(this::setTimeZone);
    }

    /**
     * As Lucene's: the bounds are lowercased if expanded terms are, and a bound that isn't a date, or
     * without a resolution, stays text.
     */
    @Override
    protected Query getRangeQuery(
            String field, String part1, String part2, boolean startInclusive, boolean endInclusive)
            throws ParseException {
        if (getLowercaseExpandedTerms()) {
            part1 = part1 == null ? null : part1.toLowerCase(getLocale());
            part2 = part2 == null ? null : part2.toLowerCase(getLocale());
        }
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

    static DateTools.Resolution resolution(DateResolution resolution) {
        return DateTools.Resolution.valueOf(resolution.name());
    }
}
