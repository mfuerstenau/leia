package io.github.mfuerstenau.leia.protocol;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A golden file in {@code protocol/fixtures/}, named {@code <method>.<kind>.json}, where a request or
 * response may have a case, {@code request-<case>} (see the README there).
 */
record Fixture(Path path, String method, Kind kind) {

    enum Kind {
        REQUEST,
        RESPONSE,
        ERROR
    }

    /** Pseudo-method for errors that belong to no method. */
    static final String RPC = "rpc";

    private static final Pattern NAME =
            Pattern.compile("([a-z][A-Za-z]*)\\.((request|response)(-[a-z0-9-]+)?|error-[a-z0-9-]+)\\.json");

    static Path protocolDir() {
        String dir = System.getProperty("leia.protocol.dir");
        if (dir == null) {
            throw new IllegalStateException("System property leia.protocol.dir is not set (see build.gradle.kts)");
        }
        return Path.of(dir);
    }

    static List<Fixture> all() {
        try (Stream<Path> files = Files.list(protocolDir().resolve("fixtures"))) {
            return files.filter(file -> file.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .map(Fixture::of)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Fixture of(Path path) {
        String fileName = path.getFileName().toString();
        Matcher matcher = NAME.matcher(fileName);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Fixture name doesn't match <method>.<kind>.json: " + fileName);
        }
        String kind = matcher.group(3) == null ? "error" : matcher.group(3);
        return new Fixture(
                path,
                matcher.group(1),
                switch (kind) {
                    case "request" -> Kind.REQUEST;
                    case "response" -> Kind.RESPONSE;
                    default -> Kind.ERROR;
                });
    }

    String read() {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String toString() {
        return path.getFileName().toString();
    }
}
