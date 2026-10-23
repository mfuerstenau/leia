package io.github.mfuerstenau.leia.tui.log;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * The last records of {@code java.util.logging}, for the log window (ADR 0087): a handler on the
 * root logger that takes every record that the root's level lets through, from any thread.
 *
 * <p>It also sets that level (ADR 0087): {@link #logFrom} makes the log as verbose as the window
 * shows, for {@code leia.log} too, but never less verbose than {@code leia.log.level} asked for.
 */
public final class LogBuffer extends Handler {

    /** How many records the buffer keeps; the oldest go first. */
    public static final int CAPACITY = 10_000;

    private static final Logger LOG = Logger.getLogger(LogBuffer.class.getName());

    /**
     * A record as the window shows it.
     *
     * @param message the message with its parameters filled in
     * @param trace the stack trace of the record's exception, as text: the exception itself would
     *     keep the class loader of a runtime or of jars alive (ADR 0068, ADR 0073)
     */
    public record Entry(Instant instant, Level level, String logger, String message, Optional<String> trace) {}

    private final int maxEntries;

    /** The level that the start set, from {@code leia.log.level}. */
    private final Level configured;

    /** The root logger whose level {@link #logFrom} sets; empty for a detached buffer. */
    private final Optional<Logger> root;

    private final SimpleFormatter formatter = new SimpleFormatter();
    private final ArrayDeque<Entry> entries = new ArrayDeque<>();
    private long serial;
    private long dropped;

    private LogBuffer(int capacity, Level configured, Optional<Logger> root) {
        this.maxEntries = capacity;
        this.configured = configured;
        this.root = root;
        setLevel(Level.ALL);
    }

    /** A buffer for the root logger, which the start set to {@code configured}. */
    public LogBuffer(Level configured) {
        this(CAPACITY, configured, Optional.of(Logger.getLogger("")));
    }

    /** A buffer that leaves the root logger alone, at INFO; nothing publishes to it unless added. */
    public static LogBuffer detached() {
        return detached(CAPACITY);
    }

    static LogBuffer detached(int capacity) {
        return new LogBuffer(capacity, Level.INFO, Optional.empty());
    }

    @Override
    public void publish(LogRecord logRecord) {
        if (!isLoggable(logRecord)) {
            return;
        }
        Entry entry = new Entry(
                logRecord.getInstant(),
                logRecord.getLevel(),
                logRecord.getLoggerName() == null ? "" : logRecord.getLoggerName(),
                formatter.formatMessage(logRecord),
                Optional.ofNullable(logRecord.getThrown()).map(LogBuffer::trace));
        synchronized (entries) {
            if (entries.size() == maxEntries) {
                entries.removeFirst();
                dropped++;
            }
            entries.addLast(entry);
            serial++;
        }
    }

    private static String trace(Throwable thrown) {
        StringWriter text = new StringWriter();
        try (PrintWriter writer = new PrintWriter(text)) {
            thrown.printStackTrace(writer);
        }
        return text.toString();
    }

    /** How many records have been published so far. */
    public long serial() {
        synchronized (entries) {
            return serial;
        }
    }

    /** How many records the buffer has let go of, oldest first. */
    public long dropped() {
        synchronized (entries) {
            return dropped;
        }
    }

    /** The records kept, oldest first. */
    public List<Entry> entries() {
        synchronized (entries) {
            return List.copyOf(entries);
        }
    }

    /** The level the root logger logs at now; INFO for a detached buffer. */
    public Level logged() {
        return root.map(Logger::getLevel).orElse(configured);
    }

    /**
     * Logs at the more verbose of {@code shown} and the level the start set, and says so at INFO when
     * that changes the level. The message is logged while the more verbose of the old and the new
     * level holds, and INFO at least, so that it reaches the log and the window either way.
     */
    public void logFrom(Level shown) {
        if (root.isEmpty()) {
            return;
        }
        Logger logger = root.get();
        Level target = moreVerbose(configured, shown);
        Level old = logger.getLevel();
        if (old != null && old.intValue() == target.intValue()) {
            return;
        }
        logger.setLevel(moreVerbose(moreVerbose(old == null ? target : old, target), Level.INFO));
        LOG.info(() -> "Logging at " + target);
        logger.setLevel(target);
    }

    private static Level moreVerbose(Level a, Level b) {
        return a.intValue() <= b.intValue() ? a : b;
    }

    @Override
    public void flush() {
        // Nothing is buffered.
    }

    @Override
    public void close() {
        // Nothing to release.
    }
}
