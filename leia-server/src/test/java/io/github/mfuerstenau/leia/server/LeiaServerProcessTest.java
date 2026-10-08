package io.github.mfuerstenau.leia.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.ProtocolJson;
import io.github.mfuerstenau.leia.protocol.Request;
import io.github.mfuerstenau.leia.protocol.Response;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexParams;
import io.github.mfuerstenau.leia.protocol.version.VersionParams;
import io.github.mfuerstenau.leia.protocol.version.VersionResult;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runs {@link LeiaServer#main} in a separate JVM and talks to it over its stdin and stdout. */
class LeiaServerProcessTest {

    @TempDir
    Path tmp;

    @Test
    void servesOverStdioAndExitsOnEndOfInput() throws Exception {
        Process process = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-Dleia.log.level=FINE",
                        "-cp",
                        System.getProperty("java.class.path"),
                        LeiaServer.class.getName())
                .start();
        try (OutputStream stdin = process.getOutputStream()) {
            String request =
                    ProtocolJson.mapper().writeValueAsString(Request.of(1, Methods.VERSION, new VersionParams()));
            stdin.write((request + "\n").getBytes(StandardCharsets.UTF_8));
        }

        assertThat(process.waitFor(30, TimeUnit.SECONDS))
                .as("exits after stdin is closed")
                .isTrue();
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(process.exitValue()).as(stderr).isZero();
        List<String> lines = stdout.lines().toList();
        assertThat(lines).as("stdout holds only the response").hasSize(1);
        Response<VersionResult> response =
                ProtocolJson.mapper().readValue(lines.getFirst(), ProtocolJson.responseType(Methods.VERSION));
        assertThat(response.result())
                .hasValueSatisfying(result -> assertThat(result.java().version())
                        .isEqualTo(Runtime.version().toString()));
        assertThat(stderr).contains("ready on Java", "stdin closed", "\"method\":\"version\"");
    }

    @Test
    void closesTheOpenIndexOnEndOfInput() throws Exception {
        Path dir = tmp.resolve("index");
        try (FSDirectory directory = FSDirectory.open(dir);
                IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig())) {
            writer.commit();
        }
        Process process = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp",
                        System.getProperty("java.class.path"),
                        LeiaServer.class.getName())
                .start();
        try (OutputStream stdin = process.getOutputStream()) {
            String request = ProtocolJson.mapper()
                    .writeValueAsString(Request.of(1, Methods.OPEN_INDEX, new OpenIndexParams(dir.toString())));
            stdin.write((request + "\n").getBytes(StandardCharsets.UTF_8));
        }

        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.exitValue()).as(stderr).isZero();
        assertThat(stderr).contains("Opened " + dir, "Closed " + dir);
    }

    @Test
    void exitsWithStatus2OnAnUnknownArgument() throws Exception {
        Process process = server("--verbose");
        process.getOutputStream().close();

        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isEqualTo(2);
        assertThat(new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8))
                .contains("unknown argument '--verbose'; " + ServerArgs.USAGE);
    }

    @Test
    void exitsWithStatus1IfTheExtensionJarsCantBeLoaded() throws Exception {
        Path missing = tmp.resolve("missing.jar");
        Process process = server("--jar", missing.toString());
        process.getOutputStream().close();

        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.exitValue()).as(stderr).isEqualTo(1);
        assertThat(stderr.strip().lines().toList().getLast())
                .endsWith("Cannot load the jars: no such jar or directory: " + missing);
        assertThat(process.getInputStream().readAllBytes()).isEmpty();
    }

    private static Process server(String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty("java.class.path"),
                LeiaServer.class.getName()));
        command.addAll(List.of(args));
        return new ProcessBuilder(command).start();
    }

    @Test
    void sendsStrayStdoutOutputToStderr() throws Exception {
        Process process = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp",
                        System.getProperty("java.class.path"),
                        StrayPrinter.class.getName())
                .start();
        process.getOutputStream().close();

        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8))
                .isEmpty();
        assertThat(new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8))
                .contains("stray output");
    }

    /** Runs the server (which returns at once, as stdin is closed), then prints to {@code System.out}. */
    public static final class StrayPrinter {

        public static void main(String[] args) {
            LeiaServer.main(args);
            System.out.println("stray output");
        }
    }
}
