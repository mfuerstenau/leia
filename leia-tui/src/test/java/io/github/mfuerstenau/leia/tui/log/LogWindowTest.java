package io.github.mfuerstenau.leia.tui.log;

import static io.github.mfuerstenau.leia.tui.TestScreens.key;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tamboui.style.Style;
import dev.tamboui.tui.event.KeyCode;
import io.github.mfuerstenau.leia.tui.TestScreens;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.Test;

class LogWindowTest {

    private static final Instant NOON = Instant.parse("2026-10-18T12:00:00.123Z");

    private static void log(LogBuffer buffer, Level level, String message) {
        LogRecord record = new LogRecord(level, message);
        record.setLoggerName("io.github.mfuerstenau.leia.server.LeiaBackend");
        record.setInstant(NOON);
        buffer.publish(record);
    }

    private static LogWindow window(LogBuffer buffer, Level filter) {
        return new LogWindow(buffer, filter, ZoneOffset.UTC);
    }

    private static String render(LogWindow window, int width, int height) {
        return TestScreens.render(frame -> window.render(frame, frame.area()), width, height);
    }

    @Test
    void showsTheRecordsAtOrAboveTheFilter() {
        LogBuffer buffer = LogBuffer.detached();
        log(buffer, Level.FINE, "call getTerms");
        log(buffer, Level.INFO, "opened /data/idx");
        log(buffer, Level.WARNING, "no runtime");
        LogWindow window = window(buffer, Level.INFO);

        String screen = render(window, 100, 12);

        assertThat(screen)
                .contains(" Log: INFO and above, lines 1–2 of 2 ")
                .contains("12:00:00.123 INFO    [LeiaBackend] opened /data/idx")
                .contains("12:00:00.123 WARNING [LeiaBackend] no runtime")
                .doesNotContain("call getTerms")
                .contains("-/+ level", "Esc close");
        assertThat(window.rows()).extracting(LogWindow.Row::style).containsExactly(Style.EMPTY, Style.EMPTY.yellow());
    }

    @Test
    void minusAndPlusStepTheFilter() {
        LogBuffer buffer = LogBuffer.detached();
        log(buffer, Level.FINE, "call getTerms");
        log(buffer, Level.SEVERE, "broken");
        LogWindow window = window(buffer, Level.INFO);

        window.handleKey(key('-'));
        assertThat(window.filter()).isEqualTo(Level.CONFIG);
        window.handleKey(key('-'));
        assertThat(window.filter()).isEqualTo(Level.FINE);
        assertThat(window.entries()).extracting(LogBuffer.Entry::message).containsExactly("call getTerms", "broken");
        // A detached buffer logs at INFO, which the title says.
        assertThat(render(window, 100, 12)).contains(" Log: FINE and above, lines 1–2 of 2, logging at INFO ");

        for (int i = 0; i < 10; i++) {
            window.handleKey(key('+'));
        }
        assertThat(window.filter()).isEqualTo(Level.SEVERE);
        assertThat(window.entries()).extracting(LogBuffer.Entry::message).containsExactly("broken");
        for (int i = 0; i < 10; i++) {
            window.handleKey(key('-'));
        }
        assertThat(window.filter()).isEqualTo(Level.FINEST);
    }

    @Test
    void aLevelBetweenTheStepsShowsAsTheNextVerboseStep() {
        assertThat(LogWindow.nearest(Level.ALL)).isEqualTo(Level.FINEST);
        assertThat(LogWindow.nearest(Level.OFF)).isEqualTo(Level.SEVERE);
        assertThat(LogWindow.nearest(Level.parse("850"))).isEqualTo(Level.INFO);
    }

    @Test
    void followsTheEndUntilScrolledUp() {
        LogBuffer buffer = LogBuffer.detached();
        for (int i = 1; i <= 30; i++) {
            log(buffer, Level.INFO, "record " + i);
        }
        LogWindow window = window(buffer, Level.INFO);
        // 12 rows, less the borders, the window's margin and the legend: 7 records.
        String screen = render(window, 100, 12);
        assertThat(screen).contains("lines 24–30 of 30", "record 30").doesNotContain("record 23");

        log(buffer, Level.INFO, "record 31");
        assertThat(window.changed()).isTrue();
        assertThat(render(window, 100, 12)).contains("lines 25–31 of 31", "record 31");
        assertThat(window.changed()).isFalse();

        window.handleKey(key(KeyCode.UP));
        log(buffer, Level.INFO, "record 32");
        assertThat(render(window, 100, 12)).contains("lines 24–30 of 32").doesNotContain("record 31");

        window.handleKey(key('G'));
        assertThat(render(window, 100, 12)).contains("lines 26–32 of 32", "record 32");
        window.handleKey(key('g'));
        assertThat(render(window, 100, 12)).contains("lines 1–7 of 32", "record 1 ");
    }

    @Test
    void scrollsSideways() {
        LogBuffer buffer = LogBuffer.detached();
        log(buffer, Level.INFO, "x".repeat(60) + "TAIL");
        LogWindow window = window(buffer, Level.INFO);
        assertThat(render(window, 60, 10)).contains("12:00:00.123").doesNotContain("TAIL");

        window.handleKey(key(KeyCode.RIGHT));
        window.handleKey(key(KeyCode.RIGHT));
        window.handleKey(key(KeyCode.RIGHT));
        assertThat(render(window, 60, 10)).contains("TAIL").doesNotContain("12:00:00.123");

        window.handleKey(key(KeyCode.LEFT));
        window.handleKey(key(KeyCode.LEFT));
        window.handleKey(key(KeyCode.LEFT));
        assertThat(render(window, 60, 10)).contains("12:00:00.123");
    }

    @Test
    void continuesMessagesAndTracesOnIndentedLines() {
        LogBuffer buffer = LogBuffer.detached();
        LogRecord record = new LogRecord(Level.SEVERE, "Cannot open\nsecond line");
        record.setLoggerName("leia.Main");
        record.setInstant(NOON);
        record.setThrown(new IllegalStateException("broken"));
        buffer.publish(record);
        LogWindow window = window(buffer, Level.INFO);

        assertThat(window.rows())
                .extracting(LogWindow.Row::text)
                .startsWith(
                        "12:00:00.123 SEVERE  [Main] Cannot open",
                        "    second line",
                        "    java.lang.IllegalStateException: broken");
        assertThat(window.rows().get(3).text()).startsWith("        at ");
        assertThat(window.rows()).extracting(LogWindow.Row::style).containsOnly(Style.EMPTY.red());
    }

    @Test
    void yCopiesTheShownRecordsAsTheLogHasThem() {
        LogBuffer buffer = LogBuffer.detached();
        log(buffer, Level.FINE, "call getTerms");
        log(buffer, Level.INFO, "opened /data/idx");
        LogRecord failed = new LogRecord(Level.SEVERE, "failed");
        failed.setLoggerName("leia.Main");
        failed.setInstant(NOON);
        failed.setThrown(new IllegalStateException("broken"));
        buffer.publish(failed);
        LogWindow window = window(buffer, Level.INFO);

        LogWindow.Event event = window.handleKey(key('y'));

        assertThat(event)
                .isInstanceOfSatisfying(
                        LogWindow.Copy.class,
                        copy -> assertThat(copy.text())
                                .startsWith(
                                        "12:00:00.123 INFO    [io.github.mfuerstenau.leia.server.LeiaBackend] opened /data/idx\n"
                                                + "12:00:00.123 SEVERE  [leia.Main] failed\n"
                                                + "java.lang.IllegalStateException: broken\n\tat ")
                                .endsWith("\n")
                                .doesNotContain("getTerms"));
        assertThat(render(window, 100, 12)).contains("Copied 2 records");
        window.handleKey(key(KeyCode.DOWN));
        assertThat(render(window, 100, 12)).doesNotContain("Copied");
    }

    @Test
    void yWithNothingShownCopiesNothing() {
        LogWindow window = window(LogBuffer.detached(), Level.INFO);

        assertThat(window.handleKey(key('y'))).isInstanceOf(LogWindow.Nothing.class);
        assertThat(render(window, 100, 12)).contains("Nothing to copy.", "No records at INFO and above yet");
    }

    @Test
    void theTitleCountsTheDroppedRecords() {
        LogBuffer buffer = LogBuffer.detached(2);
        for (int i = 1; i <= 5; i++) {
            log(buffer, Level.INFO, "record " + i);
        }
        LogWindow window = window(buffer, Level.INFO);

        assertThat(render(window, 120, 12)).contains(" Log: INFO and above, lines 1–2 of 2 (3 older records dropped) ");
    }

    @Test
    void escClosesTheWindowAndOtherKeysDoNothing() {
        LogWindow window = window(LogBuffer.detached(), Level.INFO);

        assertThat(window.handleKey(key('q'))).isInstanceOf(LogWindow.Nothing.class);
        assertThat(window.handleKey(key(KeyCode.ESCAPE))).isInstanceOf(LogWindow.Close.class);
    }
}
