package io.github.mfuerstenau.leia.tui;

import io.github.mfuerstenau.leia.protocol.index.DirectoryImpl;
import java.io.Serial;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The command line of leia, as the TUI CLI contract in {@code AGENTS.md} has it. The {@code leia}
 * and {@code leia.cmd} scripts take {@code -j/--java} themselves and pass the rest on (ADR 0034 §8).
 *
 * @param help {@code -h/--help}
 * @param version {@code -V/--version}
 * @param jars {@code --jar}, in the order given
 * @param noDefaultJars {@code --no-default-jars}
 * @param directory {@code --directory}, {@link DirectoryImpl#FS} without it
 * @param noReader {@code --no-reader}
 * @param indexPath {@code INDEX_PATH}
 */
record Cli(
        boolean help,
        boolean version,
        List<Path> jars,
        boolean noDefaultJars,
        DirectoryImpl directory,
        boolean noReader,
        Optional<String> indexPath) {

    static final String USAGE = "Usage: leia [OPTIONS] [INDEX_PATH]";

    static final String HELP = """
            Read-only Lucene index inspector (terminal UI)

            Usage: leia [OPTIONS] [INDEX_PATH]

            Arguments:
              [INDEX_PATH]  Lucene index directory to open, or a directory with indexes below it

            Options:
              -j, --java <PATH>
                      The JVM that runs leia: a java executable or a JDK/JRE directory. Defaults to
                      $JAVA_HOME/bin/java, then java on PATH
                  --jar <PATH>
                      A jar with analysis components (char filters, tokenizers, token filters,
                      analyzers), codecs or formats of its own, such as one of Lucene's analysis
                      modules, or a directory of such jars. Can be repeated. X adds more while leia
                      runs
                  --no-default-jars
                      Don't load the jars of the default folder: $XDG_CONFIG_HOME/leia/jars
                      (~/.config/leia/jars) on Linux, %APPDATA%\\leia\\jars on Windows. Without this
                      flag, they are loaded after those of --jar
                  --directory <IMPL>
                      The Lucene Directory to read indexes through: fs lets Lucene choose
                      (MMapDirectory on 64-bit), mmap is MMapDirectory, nio is NIOFSDirectory.
                      Ctrl+D in the open prompt changes it [default: fs] [possible values: fs, mmap,
                      nio]
                  --no-reader
                      Open INDEX_PATH without a reader, for an index that a reader can't open: only
                      its commits can be read. Ctrl+N in the open prompt does the same
              -V, --version
                      Print the version and build of leia, and the Java version in use
              -h, --help
                      Print help

            The log is $XDG_STATE_HOME/leia/leia.log (~/.local/state/leia/leia.log) on Linux,
            %LOCALAPPDATA%\\leia\\leia.log on Windows, written anew at each start. Its level comes
            from the JVM option -Dleia.log.level (INFO without it). The environment variable
            LEIA_OPTS passes options to the JVM, for example LEIA_OPTS=-Dleia.log.level=FINE.
            """;

    Cli {
        jars = List.copyOf(jars);
    }

    /** A wrong command line: an unknown option, a missing or bad value, a second index path. */
    static final class UsageException extends Exception {

        @Serial
        private static final long serialVersionUID = 1L;

        UsageException(String message) {
            super(message);
        }
    }

    /**
     * @throws UsageException if the command line is wrong
     */
    static Cli parse(String... args) throws UsageException {
        boolean help = false;
        boolean version = false;
        List<Path> jars = new ArrayList<>();
        boolean noDefaultJars = false;
        DirectoryImpl directory = DirectoryImpl.FS;
        boolean noReader = false;
        Optional<String> indexPath = Optional.empty();
        boolean options = true;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (options && arg.equals("--")) {
                options = false;
            } else if (options && arg.startsWith("-") && !arg.equals("-")) {
                int equals = arg.startsWith("--") ? arg.indexOf('=') : -1;
                String name = equals < 0 ? arg : arg.substring(0, equals);
                Optional<String> inline = equals < 0 ? Optional.empty() : Optional.of(arg.substring(equals + 1));
                switch (name) {
                    case "-h", "--help" -> help = flag(name, inline);
                    case "-V", "--version" -> version = flag(name, inline);
                    case "--no-default-jars" -> noDefaultJars = flag(name, inline);
                    case "--no-reader" -> noReader = flag(name, inline);
                    case "--jar" -> {
                        String value = inline.isPresent() ? inline.get() : next(args, ++i, name, "<PATH>");
                        jars.add(path(name, value));
                    }
                    case "--directory" -> {
                        String value = inline.isPresent() ? inline.get() : next(args, ++i, name, "<IMPL>");
                        directory = directory(value);
                    }
                    case "-j", "--java" ->
                        throw new UsageException(
                                name + " is taken by the leia script, which chooses the JVM; start leia with it");
                    default -> throw new UsageException("unknown option '" + arg + "'");
                }
            } else if (indexPath.isPresent()) {
                throw new UsageException("unexpected argument '" + arg + "': there is only one INDEX_PATH");
            } else {
                indexPath = Optional.of(arg);
            }
        }
        return new Cli(help, version, jars, noDefaultJars, directory, noReader, indexPath);
    }

    private static boolean flag(String name, Optional<String> inline) throws UsageException {
        if (inline.isPresent()) {
            throw new UsageException(name + " takes no value");
        }
        return true;
    }

    private static String next(String[] args, int i, String name, String value) throws UsageException {
        if (i >= args.length) {
            throw new UsageException(name + " needs a value " + value);
        }
        return args[i];
    }

    private static Path path(String name, String value) throws UsageException {
        try {
            return Path.of(value);
        } catch (InvalidPathException e) {
            throw new UsageException(name + ": not a path: " + e.getMessage());
        }
    }

    private static DirectoryImpl directory(String value) throws UsageException {
        return switch (value) {
            case "fs", "mmap", "nio" -> DirectoryImpl.valueOf(value.toUpperCase(Locale.ROOT));
            default ->
                throw new UsageException(
                        "invalid value '" + value + "' for --directory <IMPL>: possible values are fs, mmap, nio");
        };
    }
}
