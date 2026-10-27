package io.github.mfuerstenau.leia.tui;

import io.github.mfuerstenau.leia.tui.jars.DefaultJars;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

/**
 * Where leia keeps its files on this platform: the state folder, with the log (ADR 0034 §7) and the
 * history (ADR 0020), the default folder of jars (ADR 0024), and the folder of runtimes, beside
 * {@code leia.jar} or in the data folder (ADR 0044, ADR 0098).
 *
 * @param env the environment variables
 * @param home the user's home directory, if known
 * @param windows whether this is Windows
 * @param leiaJar the jar that leia runs from; empty if it runs from classes, as under Gradle
 */
record Platform(Function<String, String> env, Optional<Path> home, boolean windows, Optional<Path> leiaJar) {

    static Platform current() {
        String home = System.getProperty("user.home", "");
        return new Platform(
                System::getenv,
                home.isEmpty() ? Optional.empty() : Optional.of(Path.of(home)),
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows"),
                jarOf(Main.class));
    }

    /** The jar that {@code type} was loaded from, if it was loaded from a jar. */
    static Optional<Path> jarOf(Class<?> type) {
        CodeSource source = type.getProtectionDomain().getCodeSource();
        URL location = source == null ? null : source.getLocation();
        if (location == null) {
            return Optional.empty();
        }
        try {
            Path path = Path.of(location.toURI());
            return Files.isRegularFile(path)
                            && DefaultJars.isJarName(path.getFileName().toString())
                    ? Optional.of(path)
                    : Optional.empty();
        } catch (URISyntaxException | IllegalArgumentException | FileSystemNotFoundException _) {
            return Optional.empty();
        }
    }

    /**
     * The folder of runtimes (ADR 0098): the folder that {@code -Dleia.runtimes} names, as under
     * Gradle; else {@code runtimes} beside {@code leia.jar} if it is a directory, as in a portable
     * install (ADR 0044, ADR 0073); else {@code runtimes} in the {@linkplain #dataDir() data folder}.
     * Empty without {@code leia.jar}, which brings the adapters, or if the platform's variables don't
     * say where.
     */
    Optional<Path> runtimesDir() {
        String property = System.getProperty("leia.runtimes", "");
        if (!property.isEmpty()) {
            return Optional.of(Path.of(property).toAbsolutePath());
        }
        if (leiaJar.isEmpty()) {
            return Optional.empty();
        }
        return leiaJar.map(jar -> jar.resolveSibling("runtimes"))
                .filter(Files::isDirectory)
                .or(() -> dataDir().map(dir -> dir.resolve("runtimes")));
    }

    /**
     * {@code %LOCALAPPDATA%\leia} on Windows; elsewhere {@code $XDG_DATA_HOME/leia}, or {@code
     * ~/.local/share/leia} without it. Empty if the platform's variables don't say where.
     */
    Optional<Path> dataDir() {
        return windows
                ? absolute("LOCALAPPDATA").map(dir -> dir.resolve("leia"))
                : absolute("XDG_DATA_HOME")
                        .or(() -> home.map(dir -> dir.resolve(".local").resolve("share")))
                        .map(dir -> dir.resolve("leia"));
    }

    /**
     * {@code %LOCALAPPDATA%\leia} on Windows; elsewhere {@code $XDG_STATE_HOME/leia}, or {@code
     * ~/.local/state/leia} without it. Empty if the platform's variables don't say where.
     */
    Optional<Path> stateDir() {
        return windows
                ? absolute("LOCALAPPDATA").map(dir -> dir.resolve("leia"))
                : absolute("XDG_STATE_HOME")
                        .or(() -> home.map(dir -> dir.resolve(".local").resolve("state")))
                        .map(dir -> dir.resolve("leia"));
    }

    /** The log, {@code leia.log} in the {@linkplain #stateDir() state folder}. */
    Optional<Path> logFile() {
        return stateDir().map(dir -> dir.resolve("leia.log"));
    }

    /** The recently opened paths (ADR 0020), {@code history} in the {@linkplain #stateDir() state folder}. */
    Optional<Path> historyFile() {
        return stateDir().map(dir -> dir.resolve("history"));
    }

    /**
     * The default folder of jars: {@code %APPDATA%\leia\jars} on Windows; elsewhere {@code
     * $XDG_CONFIG_HOME/leia/jars}, or {@code ~/.config/leia/jars} without it. Empty if the
     * platform's variables don't say where.
     */
    Optional<Path> defaultJarsDir() {
        return (windows
                        ? absolute("APPDATA")
                        : absolute("XDG_CONFIG_HOME").or(() -> home.map(dir -> dir.resolve(".config"))))
                .map(dir -> dir.resolve("leia").resolve("jars"));
    }

    /**
     * The theme chosen last (ADR 0095), {@code theme} beside the {@linkplain #defaultJarsDir()
     * default folder of jars}.
     */
    Optional<Path> themeFile() {
        return defaultJarsDir().map(dir -> dir.resolveSibling("theme"));
    }

    /**
     * The user's own themes (ADR 0096), {@code themes} beside the {@linkplain #defaultJarsDir()
     * default folder of jars}.
     */
    Optional<Path> themesDir() {
        return defaultJarsDir().map(dir -> dir.resolveSibling("themes"));
    }

    /** The XDG Base Directory spec says that a relative path in its variables is to be ignored. */
    private Optional<Path> absolute(String variable) {
        String value = env.apply(variable);
        if (value == null || value.isEmpty()) {
            return Optional.empty();
        }
        Path path = Path.of(value);
        return path.isAbsolute() ? Optional.of(path) : Optional.empty();
    }
}
