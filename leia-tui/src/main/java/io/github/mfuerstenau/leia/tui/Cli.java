package io.github.mfuerstenau.leia.tui;

import io.github.mfuerstenau.leia.protocol.index.DirectoryImpl;
import io.github.mfuerstenau.leia.server.runtime.RuntimeFolders;
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
 * @param servers {@code --server}, in the order given: Elasticsearch or OpenSearch installations,
 *     whose Lucene reads the indexes of its majors (ADR 0090)
 * @param noDefaultJars {@code --no-default-jars}
 * @param directory {@code --directory}, {@link DirectoryImpl#FS} without it
 * @param noReader {@code --no-reader}
 * @param indexPath {@code INDEX_PATH}
 * @param downloadRuntimes {@code --download-runtimes}, which needs {@code --runtime}, takes no other
 *     option, and no {@code INDEX_PATH} (ADR 0080)
 * @param runtimes {@code --runtime}, in the order given: with {@code --download-runtimes}, the
 *     runtimes' folders to install without asking, each one leia knows (ADR 0079, ADR 0080)
 * @param theme {@code --theme}, the colour theme (ADR 0095): a name that {@link Main} checks
 *     against the themes, since the user's own are known only once their folder is read (ADR 0096)
 * @param mcp {@code --mcp}: serve {@code INDEX_PATH} to an MCP client on stdin and stdout instead
 *     of starting the TUI (ADR 0100); with neither {@code --download-runtimes}, {@code --runtime},
 *     {@code --theme} nor {@code --no-reader}
 * @param mcpRoots {@code --mcp-root}, in the order given: with {@code --mcp}, the directories below
 *     which alone {@code open_index} opens (ADR 0102)
 * @param mcpPin {@code --mcp-pin}: with {@code --mcp} and {@code INDEX_PATH}, and without {@code
 *     --mcp-root}, serve that index without {@code open_index} (ADR 0102)
 */
record Cli(
        boolean help,
        boolean version,
        List<Path> jars,
        List<Path> servers,
        boolean noDefaultJars,
        DirectoryImpl directory,
        boolean noReader,
        Optional<String> indexPath,
        boolean downloadRuntimes,
        List<String> runtimes,
        Optional<String> theme,
        boolean mcp,
        List<Path> mcpRoots,
        boolean mcpPin) {

    static final String USAGE = "Usage: leia [OPTIONS] [INDEX_PATH]";

    /** The usage error of a {@code --theme} that isn't among {@code ids}. */
    static UsageException unknownTheme(String value, List<String> ids) {
        return new UsageException(
                "invalid value '" + value + "' for --theme <THEME>: possible values are " + String.join(", ", ids));
    }

    static final String HELP_TEXT = """
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
                  --server <PATH>
                      An Elasticsearch 8 or 9 or OpenSearch 2 or 3 installation, its home or its lib
                      folder, whose Lucene, codecs and formats read the indexes of its Lucene's
                      major and the one before, such as the shards in its data folder. Only the jars
                      directly in lib are loaded. It needs leia's runtime for that Lucene, lucene9
                      or lucene10, of which only leia's own jars are used. Can be repeated, once per
                      Lucene major
                  --no-default-jars
                      Don't load the jars of the default folder: $XDG_CONFIG_HOME/leia/jars
                      (~/.config/leia/jars) on Linux, %APPDATA%\\leia\\jars on Windows. Without this
                      flag, they are loaded after those of --jar. The runtimes, a folder for each
                      Lucene from 3.6 to 10 in the folder runtimes, are loaded always, before all
                      others; without them, an index opens only without a reader. D on the start
                      screen downloads them. The folder runtimes is the one beside leia.jar, if
                      there is one, else $XDG_DATA_HOME/leia/runtimes (~/.local/share/leia/runtimes)
                      on Linux, %LOCALAPPDATA%\\leia\\runtimes on Windows
                  --directory <IMPL>
                      The Lucene Directory to read indexes through: fs lets Lucene choose
                      (MMapDirectory on 64-bit), mmap is MMapDirectory, nio is NIOFSDirectory.
                      Ctrl+D in the open prompt changes it [default: fs] [possible values: fs, mmap,
                      nio]
                  --no-reader
                      Open INDEX_PATH without a reader, for an index that a reader can't open: only
                      its commits can be read. Ctrl+N in the open prompt does the same
                  --download-runtimes
                      Install the runtimes of --runtime into the folder runtimes (see
                      --no-default-jars) without asking, as for a script, then exit: leia's own
                      jars from leia.jar, Lucene's from Maven Central
                      (https://repo1.maven.org/maven2/), each checked against its SHA-256. It
                      lists the Lucene jars with their URLs and SHA-256s, then downloads them; a
                      jar that is there and matches is kept. Behind a proxy, start it with
                      LEIA_OPTS=-Djava.net.useSystemProxies=true. Takes no other option but
                      --runtime. To choose runtimes instead, start leia and press D on its start
                      screen
                  --runtime <FOLDER>
                      With --download-runtimes, a runtime to install: lucene10, lucene9, lucene7,
                      lucene5 or lucene3. Can be repeated
                  --theme <THEME>
                      The colours: dark, the terminal's own; light, for a light background; mono,
                      none at all; nord, the Nord palette, and solarized-dark and
                      solarized-light, Solarized, each on its own background, which needs a
                      terminal with 24-bit colour; or a theme of your own, <THEME>.theme in
                      ~/.config/leia/themes. Without it, mono if NO_COLOR is set, else the theme
                      chosen last with T on the start screen, else dark [possible values: dark,
                      light, mono, nord, solarized-dark, solarized-light, or a theme of your own]
                  --mcp
                      Serve INDEX_PATH to an MCP client, such as a coding agent, on stdin and
                      stdout instead of starting the terminal UI: tools that search, show
                      documents and their whole values, and rebuild the fields that aren't
                      stored. Without INDEX_PATH, the client opens one with open_index. Goes with
                      --jar, --server, --no-default-jars and --directory; logs go to leia.log
                  --mcp-root <PATH>
                      With --mcp, a directory below which open_index opens indexes; any other
                      path is refused, also through a symbolic link. Can be repeated. Without it,
                      open_index opens any path. INDEX_PATH is opened wherever it is
                  --mcp-pin
                      With --mcp and INDEX_PATH, serve only that index: the client gets no
                      open_index
              -V, --version
                      Print the version and build of leia, and the Java version in use
              -h, --help
                      Print help

            The log is $XDG_STATE_HOME/leia/leia.log (~/.local/state/leia/leia.log) on Linux,
            %LOCALAPPDATA%\\leia\\leia.log on Windows, written anew at each start. Its level comes
            from the JVM option -Dleia.log.level (INFO without it). The environment variable
            LEIA_OPTS passes options to the JVM, for example LEIA_OPTS=-Dleia.log.level=FINE.
            L in leia shows the log, and - there lowers its level at once.

            Which fields the segments of an index store is cached in
            $XDG_CACHE_HOME/leia/stored-fields (~/.cache/leia/stored-fields) on Linux,
            %LOCALAPPDATA%\\leia\\cache\\stored-fields on Windows: field names and document
            numbers, never values. -Dleia.cache.dir=<PATH> moves it; an empty path turns it off.
            """;

    Cli {
        jars = List.copyOf(jars);
        servers = List.copyOf(servers);
        runtimes = List.copyOf(runtimes);
        mcpRoots = List.copyOf(mcpRoots);
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
        Parser parser = new Parser(args);
        parser.parse();
        return parser.cli();
    }

    /** What the arguments say so far, as {@link #parse} reads them one after another. */
    private static final class Parser {
        private final String[] args;
        private int i;
        private boolean help;
        private boolean version;
        private final List<Path> jars = new ArrayList<>();
        private final List<Path> servers = new ArrayList<>();
        private boolean noDefaultJars;
        private DirectoryImpl directory = DirectoryImpl.FS;
        private boolean directoryGiven;
        private boolean noReader;
        private Optional<String> indexPath = Optional.empty();
        private boolean downloadRuntimes;
        private final List<String> runtimes = new ArrayList<>();
        private Optional<String> theme = Optional.empty();
        private boolean mcp;
        private final List<Path> mcpRoots = new ArrayList<>();
        private boolean mcpPin;

        Parser(String[] args) {
            this.args = args;
        }

        void parse() throws UsageException {
            boolean options = true;
            for (i = 0; i < args.length; i++) {
                String arg = args[i];
                if (options && arg.equals("--")) {
                    options = false;
                } else if (options && arg.startsWith("-") && !arg.equals("-")) {
                    option(arg);
                } else if (indexPath.isPresent()) {
                    throw new UsageException("unexpected argument '" + arg + "': there is only one INDEX_PATH");
                } else {
                    indexPath = Optional.of(arg);
                }
            }
        }

        private void option(String arg) throws UsageException {
            int equals = arg.startsWith("--") ? arg.indexOf('=') : -1;
            String name = equals < 0 ? arg : arg.substring(0, equals);
            Optional<String> inline = equals < 0 ? Optional.empty() : Optional.of(arg.substring(equals + 1));
            switch (name) {
                case "-h", "--help" -> help = flag(name, inline);
                case "-V", "--version" -> version = flag(name, inline);
                case "--no-default-jars" -> noDefaultJars = flag(name, inline);
                case "--no-reader" -> noReader = flag(name, inline);
                case "--download-runtimes" -> downloadRuntimes = flag(name, inline);
                case "--mcp" -> mcp = flag(name, inline);
                case "--mcp-pin" -> mcpPin = flag(name, inline);
                case "--mcp-root" -> mcpRoots.add(path(name, value(name, inline, "<PATH>")));
                case "--jar" -> jars.add(path(name, value(name, inline, "<PATH>")));
                case "--server" -> servers.add(path(name, value(name, inline, "<PATH>")));
                case "--runtime" -> runtimes.add(runtime(value(name, inline, "<FOLDER>")));
                case "--theme" -> theme = Optional.of(value(name, inline, "<THEME>"));
                case "--directory" -> {
                    directory = directory(value(name, inline, "<IMPL>"));
                    directoryGiven = true;
                }
                case "-j", "--java" ->
                    throw new UsageException(
                            name + " is taken by the leia script, which chooses the JVM; start leia with it");
                default -> throw new UsageException("unknown option '" + arg + "'");
            }
        }

        /** The value of option {@code name}: after its {@code =}, or else the next argument. */
        private String value(String name, Optional<String> inline, String placeholder) throws UsageException {
            return inline.isPresent() ? inline.get() : next(args, ++i, name, placeholder);
        }

        Cli cli() throws UsageException {
            if (downloadRuntimes
                    && (indexPath.isPresent()
                            || !jars.isEmpty()
                            || !servers.isEmpty()
                            || noDefaultJars
                            || directoryGiven
                            || noReader
                            || theme.isPresent())) {
                throw new UsageException("--download-runtimes takes no INDEX_PATH and none of --jar, --server,"
                        + " --no-default-jars, --directory, --no-reader, --theme");
            }
            if (mcp && (downloadRuntimes || !runtimes.isEmpty() || theme.isPresent() || noReader)) {
                throw new UsageException("--mcp takes none of --download-runtimes, --runtime, --theme, --no-reader:"
                        + " an MCP client reads an index only with a reader");
            }
            if ((!mcpRoots.isEmpty() || mcpPin) && !mcp) {
                throw new UsageException("--mcp-root and --mcp-pin go only with --mcp");
            }
            if (mcpPin && indexPath.isEmpty() && !help && !version) {
                throw new UsageException("--mcp-pin needs the INDEX_PATH to serve");
            }
            if (mcpPin && !mcpRoots.isEmpty()) {
                throw new UsageException("--mcp-pin takes no --mcp-root: the client opens no other index");
            }
            if (!runtimes.isEmpty() && !downloadRuntimes) {
                throw new UsageException("--runtime goes only with --download-runtimes");
            }
            if (downloadRuntimes && runtimes.isEmpty() && !help && !version) {
                throw new UsageException("--download-runtimes needs --runtime <FOLDER>; to choose runtimes, start leia"
                        + " and press D on its start screen");
            }
            return new Cli(
                    help,
                    version,
                    jars,
                    servers,
                    noDefaultJars,
                    directory,
                    noReader,
                    indexPath,
                    downloadRuntimes,
                    runtimes.stream().distinct().toList(),
                    theme,
                    mcp,
                    mcpRoots,
                    mcpPin);
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

        private static String runtime(String value) throws UsageException {
            List<String> known = RuntimeFolders.KNOWN.stream()
                    .map(RuntimeFolders.Known::folder)
                    .toList();
            if (!known.contains(value)) {
                throw new UsageException("invalid value '" + value + "' for --runtime <FOLDER>: possible values are "
                        + String.join(", ", known));
            }
            return value;
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
}
