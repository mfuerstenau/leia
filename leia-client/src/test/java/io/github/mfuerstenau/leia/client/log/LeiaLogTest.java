package io.github.mfuerstenau.leia.client.log;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LeiaLogTest {

    private static final Logger LOG = Logger.getLogger(LeiaLogTest.class.getName());

    @TempDir
    Path tmp;

    @AfterEach
    void resetLogging() {
        System.clearProperty(LeiaLog.LEVEL_PROPERTY);
        LogManager.getLogManager().reset();
    }

    @Test
    void writesTheFileAnewAtEachStart() throws Exception {
        Path file = tmp.resolve("state/leia/leia.log");

        logInto(Optional.of(file), () -> {
            LOG.info("first session");
            LOG.fine("not at INFO");
        });
        assertThat(Files.readString(file)).contains("INFO", "[" + LeiaLogTest.class.getName() + "] first session");
        assertThat(Files.readString(file)).doesNotContain("not at INFO");

        try (PrintStream stream = LeiaLog.toFile(Optional.of(file)).stream()) {
            LOG.info("second session");
            stream.println("a stray print");
        }
        assertThat(Files.readString(file)).doesNotContain("first session").contains("second session", "a stray print");
    }

    @Test
    void takesTheLevelFromTheSystemProperty() throws Exception {
        Path file = tmp.resolve("leia.log");
        System.setProperty(LeiaLog.LEVEL_PROPERTY, "FINE");

        logInto(Optional.of(file), () -> {
            LOG.fine("at FINE");
        });

        assertThat(Files.readString(file)).contains("at FINE");
    }

    @Test
    void theLogWindowLowersTheLevelForTheFileToo() throws Exception {
        Path file = tmp.resolve("leia.log");

        try (LeiaLog.Opened log = LeiaLog.toFile(Optional.of(file))) {
            LOG.fine("before");
            log.buffer().logFrom(Level.FINE);
            LOG.fine("after");

            assertThat(log.buffer().entries())
                    .extracting(LogBuffer.Entry::message)
                    .containsExactly("Logging at FINE", "after");
            assertThat(Logger.getLogger("").getHandlers())
                    .extracting(Handler::getLevel)
                    .containsOnly(Level.ALL);
        }
        assertThat(Files.readString(file)).contains("Logging at FINE", "after").doesNotContain("before");
    }

    @Test
    void warnsOfAnUnknownLevel() throws Exception {
        Path file = tmp.resolve("leia.log");
        System.setProperty(LeiaLog.LEVEL_PROPERTY, "LOUD");

        logInto(Optional.of(file), () -> {
            LOG.info("still logged");
        });

        assertThat(Files.readString(file)).contains("Unknown leia.log.level 'LOUD'", "still logged");
    }

    @Test
    void dropsTheLogIfTheFileCantBeWritten() throws Exception {
        Path notADirectory = Files.createFile(tmp.resolve("state"));

        try (PrintStream stream = LeiaLog.toFile(Optional.of(notADirectory.resolve("leia.log"))).stream()) {
            LOG.info("dropped");
            stream.println("dropped too");
            assertThat(stream.checkError()).isFalse();
        }
        logInto(Optional.empty(), () -> {
            LOG.info("dropped");
        });
    }

    /** Logs with {@code body} into {@code file}, and closes it. */
    private static void logInto(Optional<Path> file, Runnable body) {
        try (PrintStream stream = LeiaLog.toFile(file).stream()) {
            body.run();
            assertThat(stream.checkError()).isFalse();
        }
    }
}
