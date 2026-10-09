package io.github.mfuerstenau.leia.core;

import java.text.DateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Reads the bounds of a range as dates the way Lucene's query parsers do, but in the parser's time
 * zone rather than the JVM's default (ADR 0026). Without a time zone of its own, a parser's zone is
 * the JVM's default, so it reads them exactly as Lucene does.
 */
final class DateRanges {

    private DateRanges() {}

    /** The short date format of {@code locale}, lenient, as Lucene's; in {@code zone}. */
    static DateFormat format(Locale locale, TimeZone zone) {
        DateFormat format = DateFormat.getDateInstance(DateFormat.SHORT, locale);
        format.setLenient(true);
        format.setTimeZone(zone);
        return format;
    }

    /**
     * The last millisecond of the day of {@code date} in {@code zone}: an inclusive upper bound
     * names a day, not a time, so it includes the whole day (as Lucene's parsers do).
     */
    static Date endOfDay(Date date, TimeZone zone, Locale locale) {
        Calendar calendar = Calendar.getInstance(zone, locale);
        calendar.setTime(date);
        calendar.set(Calendar.HOUR_OF_DAY, 23);
        calendar.set(Calendar.MINUTE, 59);
        calendar.set(Calendar.SECOND, 59);
        calendar.set(Calendar.MILLISECOND, 999);
        return calendar.getTime();
    }
}
