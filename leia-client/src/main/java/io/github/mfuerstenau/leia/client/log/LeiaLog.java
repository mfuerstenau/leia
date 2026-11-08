package io.github.mfuerstenau.leia.client.log;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.logging.StreamHandler;

/**
 * Where {@code java.util.logging} goes (ADR 0034 §7): into {@code leia.log}, since the terminal
 * belongs to the UI, or to stderr for {@code --version}.
 *
 * <p>The level comes from the system property {@code leia.log.level}, and the format from {@code
 * java.util.logging.SimpleFormatter.format} if it is set. The level is the root logger's alone, and
 * the handlers take what it lets through, so that the log window can change it (ADR 0087).
 */
public final class LeiaLog {

    public static final String LEVEL_PROPERTY = "leia.log.level";

    private static final String FORMAT_PROPERTY = "java.util.logging.SimpleFormatter.format";
    private static final String DEFAULT_FORMAT = "%1$tT.%1$tL %4$-7s [%3$s] %5$s%6$s%n";

    private LeiaLog() {}

    /**
     * The log of the UI.
     *
     * @param stream the log's stream, which also takes {@code System.out} and {@code System.err}
     *     while the UI runs
     * @param buffer the last records, for the log window (ADR 0087)
     */
    public record Opened(PrintStream stream, LogBuffer buffer) implements AutoCloseable {

        @Override
        public void close() {
            stream.close();
        }
    }

    /**
     * Logs into {@code file}, which is written anew, at {@code INFO} without {@code leia.log.level},
     * and into a {@link LogBuffer}. If the file can't be written, or there is none, only the buffer
     * keeps the log.
     */
    public static Opened toFile(Optional<Path> file) {
        PrintStream stream = file.flatMap(LeiaLog::open)
                .orElseGet(() -> new PrintStream(OutputStream.nullOutputStream(), true, StandardCharsets.UTF_8));
        Level level = configure(stream, Level.INFO);
        LogBuffer buffer = new LogBuffer(level);
        Logger.getLogger("").addHandler(buffer);
        return new Opened(stream, buffer);
    }

    /** Logs to stderr, at {@code defaultLevel} without {@code leia.log.level}. */
    // The fallback for when the log can't be opened (ADR 0088)
    @SuppressWarnings("java:S106")
    public static void toStderr(Level defaultLevel) {
        configure(System.err, defaultLevel);
    }

    private static Optional<PrintStream> open(Path file) {
        try {
            Path dir = file.getParent();
            if (dir != null) {
                Files.createDirectories(dir);
            }
            return Optional.of(new PrintStream(Files.newOutputStream(file), true, StandardCharsets.UTF_8));
        } catch (IOException _) {
            return Optional.empty();
        }
    }

    /** Returns the level logged at. */
    private static Level configure(PrintStream stream, Level defaultLevel) {
        if (System.getProperty(FORMAT_PROPERTY) == null) {
            System.setProperty(FORMAT_PROPERTY, DEFAULT_FORMAT);
        }
        String name = System.getProperty(LEVEL_PROPERTY);
        Optional<Level> parsed = name == null ? Optional.of(defaultLevel) : level(name);
        Level level = parsed.orElse(defaultLevel);

        LogManager.getLogManager().reset();
        StreamHandler handler = new FlushingHandler(stream);
        handler.setLevel(Level.ALL);
        Logger root = Logger.getLogger("");
        root.setLevel(level);
        root.addHandler(handler);
        if (parsed.isEmpty()) {
            root.warning(() -> "Unknown " + LEVEL_PROPERTY + " '" + name + "', logging at " + level);
        }
        return level;
    }

    private static Optional<Level> level(String name) {
        try {
            return Optional.of(Level.parse(name));
        } catch (IllegalArgumentException _) {
            return Optional.empty();
        }
    }

    /** Writes each record at once, and leaves the stream open: it may be stderr. */
    private static final class FlushingHandler extends StreamHandler {

        FlushingHandler(PrintStream stream) {
            super(stream, new SimpleFormatter());
        }

        @Override
        public synchronized void publish(LogRecord logRecord) {
            super.publish(logRecord);
            flush();
        }

        @Override
        public synchronized void close() {
            flush();
        }
    }
}
