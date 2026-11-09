package io.github.mfuerstenau.leia.client.platform;

import io.github.mfuerstenau.leia.server.runtime.RuntimesDir;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * Where leia keeps its files on this platform, as search paths (ADR 0122). Each kind of folder has
 * user candidates in order, and config and data also system ones; none of them need exist.
 *
 * <ul>
 *   <li>config: {@code $XDG_CONFIG_HOME/leia}, then {@code ~/.config/leia}; on Windows {@code
 *       %APPDATA%\leia}, or below the home folder if the variable is unset; system: each of {@code
 *       $XDG_CONFIG_DIRS} (default {@code /etc/xdg}), on Windows {@code %PROGRAMDATA%\leia}
 *   <li>data: {@code $XDG_DATA_HOME/leia}, then {@code ~/.local/share/leia}; on Windows {@code
 *       %LOCALAPPDATA%\leia}; system: each of {@code $XDG_DATA_DIRS} (default {@code
 *       /usr/local/share:/usr/share}), on Windows {@code %PROGRAMDATA%\leia}
 *   <li>state, with the log (ADR 0034 §7) and the history (ADR 0020): {@code $XDG_STATE_HOME/leia},
 *       then {@code ~/.local/state/leia}; on Windows as data; no system ones
 *   <li>cache (ADR 0113): {@code $XDG_CACHE_HOME/leia}, then {@code ~/.cache/leia}; on Windows
 *       {@code %LOCALAPPDATA%\leia\cache}; no system ones
 * </ul>
 *
 * <p>An item is read from the first candidate that has it, and written to the first user candidate
 * that is writable or can be created. Never to the temporary folder, which is shared. {@code
 * -Dleia.runtimes} and {@code -Dleia.cache.dir} replace their lists.
 *
 * @param env the environment variables
 * @param home the user's home directory; null if not known
 * @param windows whether this is Windows
 * @param leiaJar the jar that leia runs from; null if it runs from classes, as under Gradle
 */
public record Platform(
        Function<String, @Nullable String> env,
        @Nullable Path home,
        boolean windows,
        @Nullable Path leiaJar) {

    /**
     * The platform leia runs on.
     *
     * @param entry the class leia was started with, whose jar is {@code leia.jar} if it runs from one
     */
    public static Platform current(Class<?> entry) {
        String home = System.getProperty("user.home", "");
        return new Platform(
                System::getenv,
                home.isEmpty() ? null : Path.of(home),
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows"),
                jarOf(entry).orElse(null));
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
     * The folders that runtimes are looked up in, one runtime at a time, in order (ADR 0122): the
     * folder that {@code -Dleia.runtimes} names, as under Gradle, alone; else {@code runtimes} beside
     * {@code leia.jar} if it is a directory, as in a portable install (ADR 0044, ADR 0073), then {@code
     * runtimes} in each user {@linkplain #dataDirs() data folder}, then in each system one. Empty without
     * {@code leia.jar}, which brings the adapters.
     */
    public List<RuntimesDir> runtimesDirs() {
        String property = System.getProperty("leia.runtimes", "");
        if (!property.isEmpty()) {
            return List.of(new RuntimesDir(Path.of(property).toAbsolutePath(), false));
        }
        if (leiaJar == null) {
            return List.of();
        }
        Set<RuntimesDir> dirs = new LinkedHashSet<>();
        Path beside = leiaJar.resolveSibling("runtimes");
        if (Files.isDirectory(beside)) {
            dirs.add(new RuntimesDir(beside, false));
        }
        dataDirs().forEach(dir -> dirs.add(new RuntimesDir(dir.resolve("runtimes"), false)));
        systemDataDirs().forEach(dir -> dirs.add(new RuntimesDir(dir.resolve("runtimes"), true)));
        return List.copyOf(dirs);
    }

    /** The user's runtimes folders, those of {@link #runtimesDirs()} that leia may write into. */
    public List<Path> userRuntimesDirs() {
        return runtimesDirs().stream()
                .filter(dir -> !dir.system())
                .map(RuntimesDir::path)
                .toList();
    }

    /**
     * Where runtimes are installed: the first user candidate of {@link #runtimesDirs()} that is
     * writable or can be created (ADR 0122). Empty without {@code leia.jar}, or if there is none.
     */
    public Optional<Path> runtimesInstallDir() {
        return firstWritable(userRuntimesDirs());
    }

    /** The user's data folders, in order. */
    public List<Path> dataDirs() {
        return userDirs("XDG_DATA_HOME", ".local/share", "LOCALAPPDATA", "AppData/Local", "");
    }

    /** The system-wide data folders, in order. */
    public List<Path> systemDataDirs() {
        return systemDirs("XDG_DATA_DIRS", "/usr/local/share:/usr/share");
    }

    /** The user's config folders, in order. */
    public List<Path> configDirs() {
        return userDirs("XDG_CONFIG_HOME", ".config", "APPDATA", "AppData/Roaming", "");
    }

    /** The system-wide config folders, in order. */
    public List<Path> systemConfigDirs() {
        return systemDirs("XDG_CONFIG_DIRS", "/etc/xdg");
    }

    /** The user's config folders, then the system-wide ones: the order to read in. */
    public List<Path> configReadDirs() {
        return concat(configDirs(), systemConfigDirs());
    }

    /** The config folder to write into: the first user one that is writable or can be created. */
    public Optional<Path> configWriteDir() {
        return firstWritable(configDirs());
    }

    /** The user's state folders, in order. */
    public List<Path> stateDirs() {
        return userDirs("XDG_STATE_HOME", ".local/state", "LOCALAPPDATA", "AppData/Local", "");
    }

    /**
     * The state folder to write into: the first one that is writable or can be created. Empty if
     * none is; the temporary folder is shared and never a substitute.
     */
    public Optional<Path> stateWriteDir() {
        return firstWritable(stateDirs());
    }

    /** The user's cache folders, in order. */
    public List<Path> cacheDirs() {
        return userDirs("XDG_CACHE_HOME", ".cache", "LOCALAPPDATA", "AppData/Local", "cache");
    }

    /**
     * leia's cache folder, for the stored fields of the segments it read (ADR 0113): the folder that
     * {@code -Dleia.cache.dir} names, or none if it names none; else the first of the {@linkplain
     * #cacheDirs() cache folders} that is writable or can be created. Empty keeps the cache in
     * memory.
     */
    public Optional<Path> cacheDir() {
        String property = System.getProperty("leia.cache.dir");
        if (property != null) {
            return property.isEmpty()
                    ? Optional.empty()
                    : Optional.of(Path.of(property).toAbsolutePath());
        }
        return firstWritable(cacheDirs());
    }

    /** The log, {@code leia.log} in the {@linkplain #stateWriteDir() state folder}. */
    public Optional<Path> logFile() {
        return stateWriteDir().map(dir -> dir.resolve("leia.log"));
    }

    /** Where the history is written (ADR 0020), {@code history} in the {@linkplain #stateWriteDir() state folder}. */
    public Optional<Path> historyFile() {
        return stateWriteDir().map(dir -> dir.resolve("history"));
    }

    /**
     * Where the history is read from: the first state folder that has one, so that moving {@code
     * XDG_STATE_HOME} doesn't lose it; else {@link #historyFile()}.
     */
    public Optional<Path> historyReadFile() {
        return firstExisting(stateDirs(), "history", Files::isRegularFile).or(this::historyFile);
    }

    /**
     * The default folder of jars (ADR 0024), {@code jars} in a user config folder only: a system
     * folder, such as {@code %PROGRAMDATA%}, may be one that any local user can write to, and its jars
     * would run as code. The first that holds a jar; else the first that exists; else the first
     * candidate. Empty if the platform's variables don't say where.
     */
    public Optional<Path> defaultJarsDir() {
        List<Path> candidates =
                configDirs().stream().map(dir -> dir.resolve("jars")).toList();
        return candidates.stream()
                .filter(DefaultJars::hasJars)
                .findFirst()
                .or(() -> candidates.stream().filter(Files::isDirectory).findFirst())
                .or(() -> candidates.stream().findFirst());
    }

    /**
     * The theme chosen last (ADR 0095), read from the first config folder, user's or system's, that
     * has a {@code theme}, so that an administrator can set a default. Empty if none has.
     */
    public Optional<Path> themeFile() {
        return firstExisting(configReadDirs(), "theme", Files::isRegularFile);
    }

    /** Where the theme is written: {@code theme} in the {@linkplain #configWriteDir() config folder}. */
    public Optional<Path> themeWriteFile() {
        return configWriteDir().map(dir -> dir.resolve("theme"));
    }

    /**
     * The user's mirrors of Maven Central (ADR 0124), {@code mirrors} in the first config folder,
     * user's or system's, that has one, so that an administrator can name the company's. Its mirrors
     * run no code, and every jar from them is checked against its SHA-256. Empty if none has.
     */
    public Optional<Path> mirrorsFile() {
        return firstExisting(configReadDirs(), "mirrors", Files::isRegularFile);
    }

    /**
     * The mirror chosen last in the window of {@code D} (ADR 0124), {@code mirror} in the first
     * config folder that has one; else {@link #mirrorWriteFile()}.
     */
    public Optional<Path> mirrorFile() {
        return firstExisting(configReadDirs(), "mirror", Files::isRegularFile).or(this::mirrorWriteFile);
    }

    /** Where the mirror chosen last is written: {@code mirror} in the {@linkplain #configWriteDir() config folder}. */
    public Optional<Path> mirrorWriteFile() {
        return configWriteDir().map(dir -> dir.resolve("mirror"));
    }

    /**
     * The folders of the user's own themes (ADR 0096) that exist, with the one that wins a clash of
     * names first: {@code themes} in each user config folder, then each system one.
     */
    public List<Path> themesDirs() {
        return configReadDirs().stream()
                .map(dir -> dir.resolve("themes"))
                .filter(Files::isDirectory)
                .toList();
    }

    /** The user candidates: {@code $xdg/leia}, then {@code ~/<homeRelative>/leia}; on Windows {@code %variable%\leia}. */
    private List<Path> userDirs(
            String xdg, String homeRelative, String windowsVariable, String windowsRelative, String below) {
        Set<Path> dirs = new LinkedHashSet<>();
        if (windows) {
            Optional<Path> root = absolute(windowsVariable)
                    .or(() -> Optional.ofNullable(home).map(dir -> dir.resolve(windowsRelative)));
            root.ifPresent(dir -> dirs.add(
                    below.isEmpty() ? dir.resolve("leia") : dir.resolve("leia").resolve(below)));
        } else {
            absolute(xdg).ifPresent(dir -> dirs.add(dir.resolve("leia")));
            if (home != null) {
                dirs.add(home.resolve(homeRelative).resolve("leia"));
            }
        }
        return List.copyOf(dirs);
    }

    /** The system candidates: each absolute entry of {@code variable}, or of {@code fallback}, plus {@code /leia}. */
    private List<Path> systemDirs(String variable, String fallback) {
        if (windows) {
            return absolute("PROGRAMDATA")
                    .map(dir -> List.of(dir.resolve("leia")))
                    .orElse(List.of());
        }
        String value = env.apply(variable);
        Set<Path> dirs = new LinkedHashSet<>();
        for (String entry : (value == null || value.isEmpty() ? fallback : value).split(":")) {
            if (!entry.isEmpty() && Path.of(entry).isAbsolute()) {
                dirs.add(Path.of(entry).resolve("leia"));
            }
        }
        return List.copyOf(dirs);
    }

    private static Optional<Path> firstExisting(List<Path> dirs, String name, Predicate<Path> exists) {
        return dirs.stream().map(dir -> dir.resolve(name)).filter(exists).findFirst();
    }

    /**
     * The first of {@code dirs} that is a folder leia can write into, or can create: the nearest
     * ancestor that exists must be a writable folder. It creates nothing.
     */
    static Optional<Path> firstWritable(List<Path> dirs) {
        return dirs.stream().filter(Platform::writable).findFirst();
    }

    private static boolean writable(Path dir) {
        if (Files.exists(dir)) {
            return Files.isDirectory(dir) && Files.isWritable(dir);
        }
        Path ancestor = dir.getParent();
        while (ancestor != null && !Files.exists(ancestor)) {
            ancestor = ancestor.getParent();
        }
        return ancestor != null && Files.isDirectory(ancestor) && Files.isWritable(ancestor);
    }

    private static <T> List<T> concat(List<T> first, List<T> second) {
        List<T> all = new ArrayList<>(first);
        all.addAll(second);
        return List.copyOf(all);
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
