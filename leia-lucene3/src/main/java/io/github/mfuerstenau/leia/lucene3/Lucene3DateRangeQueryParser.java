package io.github.mfuerstenau.leia.lucene3;

import io.github.mfuerstenau.leia.runtime.Search.DateResolution;
import io.github.mfuerstenau.leia.runtime.Search.Dates;
import io.github.mfuerstenau.leia.runtime.support.DateRanges;
import java.text.DateFormat;
import java.util.Date;
import java.util.Optional;
import java.util.TimeZone;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.DateField;
import org.apache.lucene.document.DateTools;
import org.apache.lucene.queryParser.ParseException;
import org.apache.lucene.queryParser.QueryParser;
import org.apache.lucene.search.Query;
import org.apache.lucene.util.Version;

/**
 * Lucene 3.6's classic query parser, which reads range bounds as dates in a time zone of its own, as
 * leia-core's {@code DateRangeQueryParser} reads them on Lucene 10 (ADR 0026, ADR 0069). 3.6's parser
 * has no time zone: it reads dates in the JVM's default one, which stays the zone without one given.
 * Adapted from Lucene's {@code QueryParser.getRangeQuery} (Apache License 2.0).
 */
final class Lucene3DateRangeQueryParser extends QueryParser {

    private final TimeZone timeZone;

    Lucene3DateRangeQueryParser(String defaultField, Analyzer analyzer, Dates dates) {
        super(Version.LUCENE_36, defaultField, analyzer);
        Optional.ofNullable(dates.resolution())
                .map(Lucene3DateRangeQueryParser::resolution)
                .ifPresent(this::setDateResolution);
        dates.fields().forEach((field, resolution) -> setDateResolution(field, resolution(resolution)));
        Optional.ofNullable(dates.locale()).ifPresent(this::setLocale);
        timeZone =
                Optional.ofNullable(dates.timeZone()).map(TimeZone::getTimeZone).orElseGet(TimeZone::getDefault);
    }

    /**
     * As Lucene's: the bounds are lowercased if expanded terms are, and a bound that isn't a date
     * stays text. Without a resolution, a date becomes a term of the deprecated {@code DateField},
     * which indexes before Lucene 1.9 used.
     */
    @Override
    protected Query getRangeQuery(String field, String part1, String part2, boolean inclusive) throws ParseException {
        if (getLowercaseExpandedTerms()) {
            part1 = part1 == null ? null : part1.toLowerCase();
            part2 = part2 == null ? null : part2.toLowerCase();
        }
        DateFormat format = DateRanges.format(getLocale(), timeZone);
        DateTools.Resolution resolution = getDateResolution(field);
        try {
            part1 = dateToString(format.parse(part1), resolution);
        } catch (Exception _) {
            // Not a date or an open bound: the bound stays as it is.
        }
        try {
            Date date = format.parse(part2);
            if (inclusive) {
                date = DateRanges.endOfDay(date, timeZone, getLocale());
            }
            part2 = dateToString(date, resolution);
        } catch (Exception _) {
            // As above.
        }
        return newRangeQuery(field, part1, part2, inclusive);
    }

    private static String dateToString(Date date, DateTools.Resolution resolution) {
        return resolution == null ? DateField.dateToString(date) : DateTools.dateToString(date, resolution);
    }

    static DateTools.Resolution resolution(DateResolution resolution) {
        return DateTools.Resolution.valueOf(resolution.name());
    }
}
