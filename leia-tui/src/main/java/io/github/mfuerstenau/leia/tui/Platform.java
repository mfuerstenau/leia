package io.github.mfuerstenau.leia.tui;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

/**
 * Where leia keeps its files on this platform: the state folder, with the log (ADR 0034 §7) and the
 * history (ADR 0020), and the default folder of jars (ADR 0024).
 *
 * @param env the environment variables
 * @param home the user's home directory, if known
 * @param windows whether this is Windows
 */
record Platform(Function<String, String> env, Optional<Path> home, boolean windows) {

    static Platform current() {
        String home = System.getProperty("user.home", "");
        return new Platform(
                System::getenv,
                home.isEmpty() ? Optional.empty() : Optional.of(Path.of(home)),
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows"));
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
