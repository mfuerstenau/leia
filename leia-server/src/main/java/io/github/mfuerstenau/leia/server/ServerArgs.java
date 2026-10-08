package io.github.mfuerstenau.leia.server;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The command line of {@code leia-server.jar}: {@code [--jar <PATH>]...} (ADR 0016, ADR 0018).
 *
 * @param jars the jars, or directories of jars, with analysis components, codecs or formats of
 *     their own
 */
record ServerArgs(List<Path> jars) {

    static final String USAGE = "usage: java -jar leia-server.jar [--jar <PATH>]...";

    private static final String JAR = "--jar";

    ServerArgs {
        jars = List.copyOf(jars);
    }

    /**
     * @throws IllegalArgumentException for an unknown argument, a missing or invalid path
     */
    static ServerArgs parse(String... args) {
        List<Path> jars = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].equals(JAR)) {
                throw new IllegalArgumentException("unknown argument '" + args[i] + "'");
            }
            if (++i == args.length) {
                throw new IllegalArgumentException(JAR + " needs a path");
            }
            try {
                jars.add(Path.of(args[i]));
            } catch (InvalidPathException e) {
                throw new IllegalArgumentException(JAR + ": " + e.getMessage(), e);
            }
        }
        return new ServerArgs(jars);
    }
}
