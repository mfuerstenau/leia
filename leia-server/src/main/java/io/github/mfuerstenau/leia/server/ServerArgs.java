package io.github.mfuerstenau.leia.server;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The command line of {@code leia-server.jar}: {@code [--analysis-jar <PATH>]...} (ADR 0016).
 *
 * @param analysisJars the jars, or directories of jars, with analysis components of their own
 */
record ServerArgs(List<Path> analysisJars) {

    static final String USAGE = "usage: java -jar leia-server.jar [--analysis-jar <PATH>]...";

    private static final String ANALYSIS_JAR = "--analysis-jar";

    ServerArgs {
        analysisJars = List.copyOf(analysisJars);
    }

    /**
     * @throws IllegalArgumentException for an unknown argument, a missing or invalid path
     */
    static ServerArgs parse(String... args) {
        List<Path> analysisJars = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].equals(ANALYSIS_JAR)) {
                throw new IllegalArgumentException("unknown argument '" + args[i] + "'");
            }
            if (++i == args.length) {
                throw new IllegalArgumentException(ANALYSIS_JAR + " needs a path");
            }
            try {
                analysisJars.add(Path.of(args[i]));
            } catch (InvalidPathException e) {
                throw new IllegalArgumentException(ANALYSIS_JAR + ": " + e.getMessage(), e);
            }
        }
        return new ServerArgs(analysisJars);
    }
}
