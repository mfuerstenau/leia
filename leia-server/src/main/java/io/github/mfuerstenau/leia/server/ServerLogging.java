package io.github.mfuerstenau.leia.server;

import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * Sends all {@code java.util.logging} output to stderr, one line per record. stdout belongs to the
 * protocol (AGENTS.md, hard rule 2).
 *
 * <p>The level comes from the system property {@code leia.log.level} (default {@code INFO}), and
 * the format from {@code java.util.logging.SimpleFormatter.format} if it is set.
 */
final class ServerLogging {

    static final String LEVEL_PROPERTY = "leia.log.level";

    private static final String FORMAT_PROPERTY = "java.util.logging.SimpleFormatter.format";
    private static final String DEFAULT_FORMAT = "%1$tT.%1$tL %4$-7s [%3$s] %5$s%6$s%n";

    private ServerLogging() {}

    static void configure() {
        if (System.getProperty(FORMAT_PROPERTY) == null) {
            System.setProperty(FORMAT_PROPERTY, DEFAULT_FORMAT);
        }
        Level level = Level.parse(System.getProperty(LEVEL_PROPERTY, "INFO"));

        LogManager.getLogManager().reset();
        ConsoleHandler handler = new ConsoleHandler(); // writes to System.err
        handler.setFormatter(new SimpleFormatter());
        handler.setLevel(level);
        Logger root = Logger.getLogger("");
        root.setLevel(level);
        root.addHandler(handler);
    }
}
