package io.github.mfuerstenau.leia.runtime.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.text.ParseException;
import java.time.Instant;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;

class DateRangesTest {

    private static final TimeZone BERLIN = TimeZone.getTimeZone("Europe/Berlin");

    @Test
    void formatReadsTheLocalesShortDatesInTheZone() throws ParseException {
        Date date = DateRanges.format(Locale.US, BERLIN).parse("1/15/26");

        assertThat(date.toInstant()).isEqualTo(Instant.parse("2026-01-14T23:00:00Z"));
        assertThat(DateRanges.format(Locale.GERMANY, TimeZone.getTimeZone("UTC"))
                        .parse("15.01.26")
                        .toInstant())
                .isEqualTo(Instant.parse("2026-01-15T00:00:00Z"));
    }

    @Test
    void endOfDayIsTheLastMillisecondOfTheDayInTheZone() {
        Date date = Date.from(Instant.parse("2026-01-14T23:00:00Z"));

        assertThat(DateRanges.endOfDay(date, BERLIN, Locale.US).toInstant())
                .isEqualTo(Instant.parse("2026-01-15T22:59:59.999Z"));
    }
}
