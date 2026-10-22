package io.github.mfuerstenau.leia.tui;

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
 * java.util.logging.SimpleFormatter.format} if it is set.
 */
final class LeiaLog {

    static final String LEVEL_PROPERTY = "leia.log.level";

    private static final String FORMAT_PROPERTY = "java.util.logging.SimpleFormatter.format";
    private static final String DEFAULT_FORMAT = "%1$tT.%1$tL %4$-7s [%3$s] %5$s%6$s%n";

    private LeiaLog() {}

    /**
     * Logs into {@code file}, which is written anew, at {@code INFO} without {@code leia.log.level}.
     * If it can't be written, or there is none, the log is dropped.
     *
     * @return the log's stream, which also takes {@code System.out} and {@code System.err} while
     *     the UI runs
     */
    static PrintStream toFile(Optional<Path> file) {
        PrintStream stream = file.flatMap(LeiaLog::open)
                .orElseGet(() -> new PrintStream(OutputStream.nullOutputStream(), true, StandardCharsets.UTF_8));
        configure(stream, Level.INFO);
        return stream;
    }

    /** Logs to stderr, at {@code defaultLevel} without {@code leia.log.level}. */
    static void toStderr(Level defaultLevel) {
        configure(System.err, defaultLevel);
    }

    /** Logs to {@code stream}, such as a buffer while a dialogue has the terminal. */
    static void to(PrintStream stream, Level defaultLevel) {
        configure(stream, defaultLevel);
    }

    private static Optional<PrintStream> open(Path file) {
        try {
            Path dir = file.getParent();
            if (dir != null) {
                Files.createDirectories(dir);
            }
            return Optional.of(new PrintStream(Files.newOutputStream(file), true, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static void configure(PrintStream stream, Level defaultLevel) {
        if (System.getProperty(FORMAT_PROPERTY) == null) {
            System.setProperty(FORMAT_PROPERTY, DEFAULT_FORMAT);
        }
        String name = System.getProperty(LEVEL_PROPERTY);
        Optional<Level> parsed = name == null ? Optional.of(defaultLevel) : level(name);
        Level level = parsed.orElse(defaultLevel);

        LogManager.getLogManager().reset();
        StreamHandler handler = new FlushingHandler(stream);
        handler.setLevel(level);
        Logger root = Logger.getLogger("");
        root.setLevel(level);
        root.addHandler(handler);
        if (parsed.isEmpty()) {
            root.warning(() -> "Unknown " + LEVEL_PROPERTY + " '" + name + "', logging at " + level);
        }
    }

    private static Optional<Level> level(String name) {
        try {
            return Optional.of(Level.parse(name));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Writes each record at once, and leaves the stream open: it may be stderr. */
    private static final class FlushingHandler extends StreamHandler {

        FlushingHandler(PrintStream stream) {
            super(stream, new SimpleFormatter());
        }

        @Override
        public synchronized void publish(LogRecord record) {
            super.publish(record);
            flush();
        }

        @Override
        public synchronized void close() {
            flush();
        }
    }
}
