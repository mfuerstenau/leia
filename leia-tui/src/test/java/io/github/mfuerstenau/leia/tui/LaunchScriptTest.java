package io.github.mfuerstenau.leia.tui;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The {@code leia} sh script (ADR 0034 §8), run by each shell there is, with a fake {@code java}
 * that prints how it was called. {@code leia.cmd} is checked by hand on Windows.
 */
@DisabledOnOs(OS.WINDOWS)
class LaunchScriptTest {

    private static final Path SCRIPT = Path.of(System.getProperty("leia.scripts"), "leia");

    /** The fake java: its path on the first line, then one line per argument. */
    private static final String FAKE_JAVA = """
            #!/bin/sh
            printf '%s\\n' "$0" "$@"
            exit "${FAKE_STATUS:-0}"
            """;

    @TempDir
    Path tmp;

    /** The folder with leia and leia.jar, as in a dist folder. */
    private Path home;

    private Path jar;

    /** dirname and readlink, which the script needs, without a java beside them. */
    private Path tools;

    private final Map<String, String> env = new HashMap<>();

    /** sh, and dash and bash if they are there and sh isn't one of them. */
    static Stream<String> shells() {
        Map<Path, String> shells = new LinkedHashMap<>();
        for (String shell : List.of("/bin/sh", "/bin/dash", "/bin/bash")) {
            Path path = Path.of(shell);
            if (Files.isExecutable(path)) {
                shells.putIfAbsent(realPath(path), shell);
            }
        }
        return shells.values().stream();
    }

    @BeforeEach
    void home() throws IOException {
        home = Files.createDirectories(tmp.resolve("leia home")).toRealPath();
        Files.copy(SCRIPT, home.resolve("leia"));
        jar = Files.createFile(home.resolve("leia.jar"));
        tools = Files.createDirectories(tmp.resolve("tools"));
        for (String tool : List.of("dirname", "readlink")) {
            Files.createSymbolicLink(tools.resolve(tool), find(tool));
        }
        env.put("PATH", tools.toString());
    }

    @ParameterizedTest
    @MethodSource("shells")
    void javaOptionWithAJdkDirectoryUsesItsBinJava(String shell) throws IOException {
        Path jdk = fakeJdk("jdk 25");

        Run run = run(shell, "-j", jdk.toString(), "--version");

        assertThat(run.status()).isZero();
        assertThat(run.out()).containsExactly(java(jdk), "-jar", jar.toString(), "--version");
    }

    @ParameterizedTest
    @MethodSource("shells")
    void javaOptionWithAnExecutableUsesIt(String shell) throws IOException {
        String java = java(fakeJdk("jdk"));

        assertThat(run(shell, "--java", java).out()).first().isEqualTo(java);
        assertThat(run(shell, "--java=" + java).out()).first().isEqualTo(java);
    }

    @ParameterizedTest
    @MethodSource("shells")
    void aBareFileNameIsAFileInTheWorkingDirectory(String shell) throws IOException {
        Path bin = fakeJdk("jdk").resolve("bin");

        Run run = runIn(bin, shell, "-j", "java");

        // dash runs ./java, bash makes it absolute: either way, the file in the working directory.
        assertThat(bin.resolve(run.out().getFirst()).normalize()).isEqualTo(bin.resolve("java"));
    }

    @ParameterizedTest
    @MethodSource("shells")
    void javaOptionWithADirectoryWithoutJavaFails(String shell) throws IOException {
        Path empty = Files.createDirectories(tmp.resolve("empty"));

        Run run = run(shell, "-j", empty.toString());

        assertThat(run.status()).isEqualTo(1);
        assertThat(run.out()).isEmpty();
        assertThat(run.err()).contains("--java " + empty + ": the directory has no bin/java");
    }

    @ParameterizedTest
    @MethodSource("shells")
    void javaOptionWithAMissingFileFails(String shell) {
        Run run = run(shell, "-j", tmp.resolve("nope").toString());

        assertThat(run.status()).isEqualTo(1);
        assertThat(run.err()).contains("no such executable");
    }

    @ParameterizedTest
    @MethodSource("shells")
    void javaOptionWithoutAValueIsAUsageError(String shell) {
        for (List<String> args : List.of(List.of("-j"), List.of("--java"), List.of("--java="), List.of("-j", ""))) {
            Run run = run(shell, args.toArray(String[]::new));

            assertThat(run.status()).as("%s", args).isEqualTo(2);
            assertThat(run.err()).as("%s", args).contains("needs a value <PATH>", "Usage: leia");
        }
    }

    @ParameterizedTest
    @MethodSource("shells")
    void javaOptionWinsOverJavaHome(String shell) throws IOException {
        Path option = fakeJdk("option");
        env.put("JAVA_HOME", fakeJdk("home").toString());

        assertThat(run(shell, "-j", option.toString()).out()).first().isEqualTo(java(option));
    }

    @ParameterizedTest
    @MethodSource("shells")
    void javaHomeIsUsedWithoutTheOption(String shell) throws IOException {
        Path javaHome = fakeJdk("home");
        env.put("JAVA_HOME", javaHome.toString());
        env.put("PATH", tools + ":" + fakeJdk("path").resolve("bin"));

        assertThat(run(shell).out()).first().isEqualTo(java(javaHome));
    }

    @ParameterizedTest
    @MethodSource("shells")
    void javaHomeWithoutJavaFallsBackToPath(String shell) throws IOException {
        env.put("JAVA_HOME", Files.createDirectories(tmp.resolve("no java")).toString());
        Path path = fakeJdk("path");
        env.put("PATH", tools + ":" + path.resolve("bin"));

        assertThat(run(shell).out()).first().isEqualTo(java(path));
    }

    @ParameterizedTest
    @MethodSource("shells")
    void withoutAnyJavaItFails(String shell) {
        Run run = run(shell, "--version");

        assertThat(run.status()).isEqualTo(1);
        assertThat(run.err()).contains("no java found");
    }

    @ParameterizedTest
    @MethodSource("shells")
    void theOtherArgumentsArePassedOnInOrder(String shell) throws IOException {
        Path jdk = fakeJdk("jdk");

        Run run = run(shell, "--jar", "a b.jar", "-j", jdk.toString(), "--no-reader", "my index");

        assertThat(run.out())
                .containsExactly(java(jdk), "-jar", jar.toString(), "--jar", "a b.jar", "--no-reader", "my index");
    }

    @ParameterizedTest
    @MethodSource("shells")
    void optionValuesAndArgumentsAfterDoubleDashAreNotTakenForJava(String shell) throws IOException {
        Path jdk = fakeJdk("jdk");
        env.put("JAVA_HOME", jdk.toString());

        Run run = run(shell, "--jar", "-j", "--directory", "--java", "--", "-j", "x", "--java=y");

        assertThat(run.out())
                .containsExactly(
                        java(jdk),
                        "-jar",
                        jar.toString(),
                        "--jar",
                        "-j",
                        "--directory",
                        "--java",
                        "--",
                        "-j",
                        "x",
                        "--java=y");
    }

    @ParameterizedTest
    @MethodSource("shells")
    void leiaOptsGoBeforeTheJarUnglobbed(String shell) throws IOException {
        Path jdk = fakeJdk("jdk");
        env.put("JAVA_HOME", jdk.toString());
        env.put("LEIA_OPTS", "-Dleia.log.level=FINE  -Xmx1g *");

        // The working directory has files that * would match.
        Run run = runIn(home, shell, "x");

        assertThat(run.out())
                .containsExactly(java(jdk), "-Dleia.log.level=FINE", "-Xmx1g", "*", "-jar", jar.toString(), "x");
    }

    @ParameterizedTest
    @MethodSource("shells")
    void anAbsoluteSymlinkFindsTheJarNextToItsTarget(String shell) throws IOException {
        env.put("JAVA_HOME", fakeJdk("jdk").toString());
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        Files.createSymbolicLink(bin.resolve("leia"), home.resolve("leia"));

        Run run = runScript(bin.resolve("leia"), tmp, shell);

        assertThat(run.out()).contains(jar.toString());
    }

    @ParameterizedTest
    @MethodSource("shells")
    void aChainOfRelativeSymlinksFindsTheJarNextToItsTarget(String shell) throws IOException {
        env.put("JAVA_HOME", fakeJdk("jdk").toString());
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        Path links = Files.createDirectories(tmp.resolve("links"));
        Files.createSymbolicLink(bin.resolve("leia"), Path.of("../links/leia-link"));
        Files.createSymbolicLink(links.resolve("leia-link"), Path.of("../leia home/leia"));

        // Called by a relative path from the working directory, as a PATH lookup doesn't.
        Run run = runScript(Path.of("bin/leia"), tmp, shell);

        assertThat(run.out()).contains(jar.toString());
    }

    @ParameterizedTest
    @MethodSource("shells")
    void withoutTheJarItFails(String shell) throws IOException {
        env.put("JAVA_HOME", fakeJdk("jdk").toString());
        Files.delete(jar);

        Run run = run(shell);

        assertThat(run.status()).isEqualTo(1);
        assertThat(run.err()).contains("leia.jar not found next to the leia script (looked for " + jar + ")");
    }

    @ParameterizedTest
    @MethodSource("shells")
    void theExitStatusOfJavaIsPassedOn(String shell) throws IOException {
        env.put("JAVA_HOME", fakeJdk("jdk").toString());
        env.put("FAKE_STATUS", "3");

        assertThat(run(shell).status()).isEqualTo(3);
    }

    private record Run(int status, List<String> out, String err) {}

    private Run run(String shell, String... args) {
        return runIn(tmp, shell, args);
    }

    private Run runIn(Path workingDir, String shell, String... args) {
        return runScript(home.resolve("leia"), workingDir, shell, args);
    }

    private Run runScript(Path script, Path workingDir, String shell, String... args) {
        List<String> command = new ArrayList<>(List.of(shell, script.toString()));
        command.addAll(List.of(args));
        try {
            Path out = Files.createTempFile(tmp, "out", ".txt");
            Path err = Files.createTempFile(tmp, "err", ".txt");
            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(workingDir.toFile())
                    .redirectOutput(out.toFile())
                    .redirectError(err.toFile());
            builder.environment().clear();
            builder.environment().putAll(env);
            Process process = builder.start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new AssertionError("timed out: " + command);
            }
            Run run = new Run(process.exitValue(), Files.readAllLines(out), Files.readString(err));
            Files.delete(out);
            Files.delete(err);
            return run;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    /** A fake JDK: a folder with the fake java as {@code bin/java}. */
    private Path fakeJdk(String name) throws IOException {
        Path jdk = Files.createDirectories(tmp.resolve(name).resolve("bin")).getParent();
        Path java = jdk.resolve("bin/java");
        Files.writeString(java, FAKE_JAVA);
        Files.setPosixFilePermissions(java, PosixFilePermissions.fromString("rwxr-xr-x"));
        return jdk;
    }

    private static String java(Path jdk) {
        return jdk.resolve("bin/java").toString();
    }

    private static Path find(String tool) {
        return Stream.of("/usr/bin", "/bin")
                .map(dir -> Path.of(dir, tool))
                .filter(Files::isExecutable)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + tool + " in /usr/bin or /bin"));
    }

    private static Path realPath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
