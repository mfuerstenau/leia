package io.github.mfuerstenau.leia.client.log;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LogBufferTest {

    private static final Logger LOG = Logger.getLogger(LogBufferTest.class.getName());

    @AfterEach
    void resetLogging() {
        LogManager.getLogManager().reset();
    }

    private static LogRecord record(Level level, String message) {
        LogRecord record = new LogRecord(level, message);
        record.setLoggerName("io.github.Some");
        return record;
    }

    @Test
    void dropsTheOldestRecordsAndCountsThem() {
        LogBuffer buffer = LogBuffer.detached(3);

        for (int i = 1; i <= 5; i++) {
            buffer.publish(record(Level.INFO, "record " + i));
        }

        assertThat(buffer.entries())
                .extracting(LogBuffer.Entry::message)
                .containsExactly("record 3", "record 4", "record 5");
        assertThat(buffer.serial()).isEqualTo(5);
        assertThat(buffer.dropped()).isEqualTo(2);
    }

    @Test
    void fillsInTheParameters() {
        LogBuffer buffer = LogBuffer.detached();
        LogRecord record = record(Level.WARNING, "Cannot read {0} of {1}");
        record.setParameters(new Object[] {"_0.cfs", "/data/idx"});

        buffer.publish(record);

        LogBuffer.Entry entry = buffer.entries().getFirst();
        assertThat(entry.message()).isEqualTo("Cannot read _0.cfs of /data/idx");
        assertThat(entry.level()).isEqualTo(Level.WARNING);
        assertThat(entry.logger()).isEqualTo("io.github.Some");
        assertThat(entry.trace()).isNull();
    }

    @Test
    void keepsTheTraceAsText() {
        LogBuffer buffer = LogBuffer.detached();
        LogRecord record = record(Level.SEVERE, "failed");
        record.setThrown(new IllegalStateException("broken"));

        buffer.publish(record);

        assertThat(buffer.entries().getFirst().trace())
                .startsWith("java.lang.IllegalStateException: broken")
                .contains("\tat " + LogBufferTest.class.getName());
    }

    @Test
    void logsAtTheMoreVerboseOfTheStartAndTheWindowAndSaysSo() {
        Logger root = Logger.getLogger("");
        root.setLevel(Level.INFO);
        LogBuffer buffer = new LogBuffer(Level.INFO);
        root.addHandler(buffer);

        buffer.logFrom(Level.FINE);
        LOG.fine("now at FINE");

        assertThat(buffer.logged()).isEqualTo(Level.FINE);
        assertThat(buffer.entries())
                .extracting(LogBuffer.Entry::message)
                .containsExactly("Logging at FINE", "now at FINE");

        // Less verbose than the start: the log keeps the start's level.
        buffer.logFrom(Level.WARNING);
        LOG.fine("dropped");
        LOG.info("kept");

        assertThat(buffer.logged()).isEqualTo(Level.INFO);
        assertThat(buffer.entries())
                .extracting(LogBuffer.Entry::message)
                .containsExactly("Logging at FINE", "now at FINE", "Logging at INFO", "kept");

        // No change, no message.
        buffer.logFrom(Level.SEVERE);
        assertThat(buffer.serial()).isEqualTo(4);
    }

    @Test
    void saysSoAlsoWhenTheStartLogsLessThanInfo() {
        Logger root = Logger.getLogger("");
        root.setLevel(Level.WARNING);
        LogBuffer buffer = new LogBuffer(Level.WARNING);
        root.addHandler(buffer);

        buffer.logFrom(Level.SEVERE);
        assertThat(buffer.serial()).isZero();

        buffer.logFrom(Level.INFO);
        buffer.logFrom(Level.SEVERE);

        assertThat(buffer.logged()).isEqualTo(Level.WARNING);
        assertThat(buffer.entries())
                .extracting(LogBuffer.Entry::message)
                .containsExactly("Logging at INFO", "Logging at WARNING");
    }

    @Test
    void aDetachedBufferLeavesTheRootAlone() {
        Logger root = Logger.getLogger("");
        root.setLevel(Level.INFO);
        LogBuffer buffer = LogBuffer.detached();

        buffer.logFrom(Level.FINEST);

        assertThat(root.getLevel()).isEqualTo(Level.INFO);
        assertThat(buffer.logged()).isEqualTo(Level.INFO);
    }
}
