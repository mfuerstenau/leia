package io.github.mfuerstenau.leia.tui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.mfuerstenau.leia.server.runtime.RuntimesDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformTest {

    private static final Optional<Path> HOME = Optional.of(Path.of("/home/u"));

    @TempDir
    Path tmp;

    @Test
    void listsTheUserCandidatesInOrder() {
        Platform platform =
                linux(Map.of("XDG_STATE_HOME", "/state", "XDG_CONFIG_HOME", "/config", "XDG_DATA_HOME", "/data"), HOME);

        assertThat(platform.stateDirs()).containsExactly(Path.of("/state/leia"), Path.of("/home/u/.local/state/leia"));
        assertThat(platform.configDirs()).containsExactly(Path.of("/config/leia"), Path.of("/home/u/.config/leia"));
        assertThat(platform.dataDirs()).containsExactly(Path.of("/data/leia"), Path.of("/home/u/.local/share/leia"));
        assertThat(platform.cacheDirs()).containsExactly(Path.of("/home/u/.cache/leia"));
    }

    @Test
    void fallsBackToTheHomeDirectory() {
        Platform platform = linux(Map.of(), HOME);

        assertThat(platform.stateDirs()).containsExactly(Path.of("/home/u/.local/state/leia"));
        assertThat(platform.defaultJarsDir()).hasValue(Path.of("/home/u/.config/leia/jars"));
    }

    @Test
    void listsACandidateOnceWhenTheVariableNamesTheDefault() {
        Platform platform = linux(Map.of("XDG_CONFIG_HOME", "/home/u/.config"), HOME);

        assertThat(platform.configDirs()).containsExactly(Path.of("/home/u/.config/leia"));
    }

    @Test
    void ignoresRelativeAndEmptyXdgVariables() {
        Platform platform = linux(Map.of("XDG_STATE_HOME", "state", "XDG_CONFIG_HOME", ""), HOME);

        assertThat(platform.stateDirs()).containsExactly(Path.of("/home/u/.local/state/leia"));
        assertThat(platform.configDirs()).containsExactly(Path.of("/home/u/.config/leia"));
    }

    @Test
    void knowsNoFolderWithoutVariablesOrHome() {
        Platform platform = linux(Map.of(), Optional.empty());

        assertThat(platform.stateDirs()).isEmpty();
        assertThat(platform.logFile()).isEmpty();
        assertThat(platform.historyFile()).isEmpty();
        assertThat(platform.defaultJarsDir()).isEmpty();
        assertThat(platform.cacheDir()).isEmpty();
    }

    @Test
    void listsTheSystemFoldersFromTheXdgDirsVariablesOrTheirDefaults() {
        assertThat(linux(Map.of(), HOME).systemConfigDirs()).containsExactly(Path.of("/etc/xdg/leia"));
        assertThat(linux(Map.of(), HOME).systemDataDirs())
                .containsExactly(Path.of("/usr/local/share/leia"), Path.of("/usr/share/leia"));
        Platform set = linux(Map.of("XDG_CONFIG_DIRS", "/a:rel:/b::/a", "XDG_DATA_DIRS", "/opt/share"), HOME);
        assertThat(set.systemConfigDirs()).containsExactly(Path.of("/a/leia"), Path.of("/b/leia"));
        assertThat(set.systemDataDirs()).containsExactly(Path.of("/opt/share/leia"));
        // Empty means unset.
        assertThat(linux(Map.of("XDG_CONFIG_DIRS", ""), HOME).systemConfigDirs())
                .containsExactly(Path.of("/etc/xdg/leia"));
        assertThat(set.configReadDirs())
                .containsExactly(Path.of("/home/u/.config/leia"), Path.of("/a/leia"), Path.of("/b/leia"));
    }

    @Test
    void usesAppDataOnWindows() {
        // A path that is absolute on the platform the test runs on stands for a folder below C:\Users.
        Platform platform = new Platform(
                Map.of(
                        "LOCALAPPDATA", "/local",
                        "APPDATA", "/roaming",
                        "PROGRAMDATA", "/program",
                        "XDG_STATE_HOME", "/state")::get,
                HOME,
                true,
                Optional.empty());

        assertThat(platform.stateDirs()).containsExactly(Path.of("/local/leia"));
        assertThat(platform.dataDirs()).containsExactly(Path.of("/local/leia"));
        assertThat(platform.cacheDirs()).containsExactly(Path.of("/local/leia/cache"));
        assertThat(platform.configDirs()).containsExactly(Path.of("/roaming/leia"));
        assertThat(platform.systemConfigDirs()).containsExactly(Path.of("/program/leia"));
        assertThat(platform.systemDataDirs()).containsExactly(Path.of("/program/leia"));
        assertThat(platform.defaultJarsDir()).hasValue(Path.of("/roaming/leia/jars"));
    }

    @Test
    void fallsBackToTheFoldersBelowTheHomeOnWindowsOnlyWithoutTheVariable() {
        Platform platform = new Platform(Map.<String, String>of()::get, HOME, true, Optional.empty());

        assertThat(platform.configDirs()).containsExactly(Path.of("/home/u/AppData/Roaming/leia"));
        assertThat(platform.stateDirs()).containsExactly(Path.of("/home/u/AppData/Local/leia"));
        assertThat(platform.systemDataDirs()).isEmpty();
        // The variable can point to a share; the folder below the home isn't then added.
        Platform redirected = new Platform(Map.of("APPDATA", "/share/roaming")::get, HOME, true, Optional.empty());
        assertThat(redirected.configDirs()).containsExactly(Path.of("/share/roaming/leia"));
        assertThat(new Platform(Map.<String, String>of()::get, Optional.empty(), true, Optional.empty()).configDirs())
                .isEmpty();
    }

    @Test
    void readsTheThemeFromTheFirstConfigFolderThatHasOne() throws IOException {
        Path user = Files.createDirectories(tmp.resolve("user/leia"));
        Path system = Files.createDirectories(tmp.resolve("system/leia"));
        Platform platform = linux(
                Map.of(
                        "XDG_CONFIG_HOME",
                        tmp.resolve("user").toString(),
                        "XDG_CONFIG_DIRS",
                        tmp.resolve("system").toString()),
                Optional.empty());
        assertThat(platform.themeFile()).isEmpty();

        Files.writeString(system.resolve("theme"), "nord\n");
        assertThat(platform.themeFile()).hasValue(system.resolve("theme"));
        Files.writeString(user.resolve("theme"), "light\n");
        assertThat(platform.themeFile()).hasValue(user.resolve("theme"));
        // Written to the user's.
        assertThat(platform.themeWriteFile()).hasValue(user.resolve("theme"));
    }

    @Test
    void readsABackedUpConfigFolderWhenTheVariableMovedAway() throws IOException {
        Path home = Files.createDirectories(tmp.resolve("home"));
        Files.createDirectories(home.resolve(".config/leia/themes"));
        Platform platform = linux(Map.of("XDG_CONFIG_HOME", tmp.resolve("gone").toString()), Optional.of(home));

        assertThat(platform.themesDirs()).containsExactly(home.resolve(".config/leia/themes"));
        // The variable's folder can be created, so it is where the theme is written.
        assertThat(platform.themeWriteFile()).hasValue(tmp.resolve("gone/leia/theme"));
    }

    @Test
    void listsTheThemesFoldersThatExistWithTheUsersFirst() throws IOException {
        Path user = Files.createDirectories(tmp.resolve("user/leia/themes"));
        Path system = Files.createDirectories(tmp.resolve("system/leia/themes"));
        Files.createDirectories(tmp.resolve("system2/leia"));
        Platform platform = linux(
                Map.of(
                        "XDG_CONFIG_HOME",
                        tmp.resolve("user").toString(),
                        "XDG_CONFIG_DIRS",
                        tmp.resolve("system") + ":" + tmp.resolve("system2")),
                Optional.empty());

        assertThat(platform.themesDirs()).containsExactly(user, system);
    }

    @Test
    void takesTheDefaultJarsOnlyFromTheUsersConfigFolders() throws IOException {
        Path system = Files.createDirectories(tmp.resolve("system/leia/jars"));
        Files.createFile(system.resolve("planted.jar"));
        Path home = Files.createDirectories(tmp.resolve("home"));
        Platform platform =
                linux(Map.of("XDG_CONFIG_DIRS", tmp.resolve("system").toString()), Optional.of(home));

        assertThat(platform.defaultJarsDir()).hasValue(home.resolve(".config/leia/jars"));

        // The first candidate that holds a jar, else the first that exists.
        Path second = Files.createDirectories(home.resolve(".config/leia/jars"));
        assertThat(platform.defaultJarsDir()).hasValue(second);
        Path first = Files.createDirectories(tmp.resolve("xdg/leia/jars"));
        Platform both = linux(Map.of("XDG_CONFIG_HOME", tmp.resolve("xdg").toString()), Optional.of(home));
        assertThat(both.defaultJarsDir()).hasValue(first);
        Files.createFile(second.resolve("a.jar"));
        assertThat(both.defaultJarsDir()).hasValue(second);
    }

    @Test
    void writesToTheFirstCandidateThatIsWritableOrCanBeCreated() throws IOException {
        Path blocked = Files.createFile(tmp.resolve("blocked"));
        Path good = Files.createDirectories(tmp.resolve("good"));

        // Below a file, nothing can be created; below a folder, it can, without making any.
        assertThat(Platform.firstWritable(List.of(blocked.resolve("leia"), good.resolve("a/b/leia"))))
                .hasValue(good.resolve("a/b/leia"));
        assertThat(good.resolve("a")).doesNotExist();
        assertThat(Platform.firstWritable(List.of(blocked.resolve("leia")))).isEmpty();
        assertThat(Platform.firstWritable(List.of(blocked))).isEmpty();
    }

    @Test
    void skipsAFolderThatIsNotWritable() throws IOException {
        Path locked = Files.createDirectories(tmp.resolve("locked"));
        Path open = Files.createDirectories(tmp.resolve("open"));
        assumeTrue(locked.toFile().setWritable(false) && !Files.isWritable(locked), "root can write anywhere");
        try {
            assertThat(Platform.firstWritable(List.of(locked.resolve("leia"), locked, open.resolve("leia"))))
                    .hasValue(open.resolve("leia"));
        } finally {
            locked.toFile().setWritable(true);
        }
    }

    @Test
    void writesTheStateAndTheLogToTheNextCandidateAndNeverToTheTemporaryFolder() throws IOException {
        Path file = Files.createFile(tmp.resolve("file"));
        Path home = Files.createDirectories(tmp.resolve("home"));
        Platform moved = linux(Map.of("XDG_STATE_HOME", file.toString()), Optional.of(home));

        assertThat(moved.stateWriteDir()).hasValue(home.resolve(".local/state/leia"));
        assertThat(moved.logFile()).hasValue(home.resolve(".local/state/leia/leia.log"));
        assertThat(moved.historyFile()).hasValue(home.resolve(".local/state/leia/history"));
        Platform none = linux(Map.of("XDG_STATE_HOME", file.toString()), Optional.of(file));
        assertThat(none.stateWriteDir()).isEmpty();
        assertThat(none.logFile()).isEmpty();
        assertThat(none.historyFile()).isEmpty();
    }

    @Test
    void readsTheHistoryFromTheFirstStateFolderThatHasOne() throws IOException {
        Path home = Files.createDirectories(tmp.resolve("home"));
        Path old = Files.createDirectories(home.resolve(".local/state/leia"));
        Platform platform = linux(Map.of("XDG_STATE_HOME", tmp.resolve("new").toString()), Optional.of(home));

        // Nothing yet: read where it is written.
        assertThat(platform.historyReadFile()).hasValue(tmp.resolve("new/leia/history"));
        Files.writeString(old.resolve("history"), "/a\n");
        assertThat(platform.historyReadFile()).hasValue(old.resolve("history"));
        assertThat(platform.historyFile()).hasValue(tmp.resolve("new/leia/history"));
    }

    @Test
    void keepsTheCacheInTheFirstWritableCandidateOrNowhere() throws IOException {
        Path home = Files.createDirectories(tmp.resolve("home"));

        assertThat(linux(Map.of("XDG_CACHE_HOME", tmp.resolve("c").toString()), Optional.of(home))
                        .cacheDir())
                .hasValue(tmp.resolve("c/leia"));
        Path file = Files.createFile(tmp.resolve("file"));
        assertThat(linux(Map.of("XDG_CACHE_HOME", file.toString()), Optional.of(file))
                        .cacheDir())
                .isEmpty();
    }

    @Test
    void theCachePropertyReplacesTheCandidates() {
        String before = System.getProperty("leia.cache.dir");
        try {
            System.setProperty("leia.cache.dir", tmp.resolve("override").toString());
            assertThat(linux(Map.of("XDG_CACHE_HOME", "/cache"), HOME).cacheDir())
                    .hasValue(tmp.resolve("override"));
            System.setProperty("leia.cache.dir", "");
            assertThat(linux(Map.of("XDG_CACHE_HOME", "/cache"), HOME).cacheDir())
                    .isEmpty();
        } finally {
            if (before == null) {
                System.clearProperty("leia.cache.dir");
            } else {
                System.setProperty("leia.cache.dir", before);
            }
        }
    }

    @Test
    void listsTheRuntimesBesideLeiaJarThenTheDataFoldersUsersFirst() throws IOException {
        Path jar = tmp.resolve("leia.jar");
        Path beside = Files.createDirectories(tmp.resolve("runtimes"));
        Platform platform = withJar(Map.of("XDG_DATA_HOME", "/data", "XDG_DATA_DIRS", "/sys1:/sys2"), jar);

        assertThat(platform.runtimesDirs())
                .containsExactly(
                        RuntimesDir.user(beside),
                        RuntimesDir.user(Path.of("/data/leia/runtimes")),
                        RuntimesDir.user(Path.of("/home/u/.local/share/leia/runtimes")),
                        new RuntimesDir(Path.of("/sys1/leia/runtimes"), true),
                        new RuntimesDir(Path.of("/sys2/leia/runtimes"), true));
        assertThat(platform.userRuntimesDirs())
                .containsExactly(beside, Path.of("/data/leia/runtimes"), Path.of("/home/u/.local/share/leia/runtimes"));
    }

    @Test
    void leavesOutTheRuntimesBesideLeiaJarUnlessItIsADirectory() {
        Path jar = tmp.resolve("leia.jar");

        assertThat(withJar(Map.of("XDG_DATA_HOME", "/data", "XDG_DATA_DIRS", "/sys"), jar)
                        .runtimesDirs())
                .containsExactly(
                        RuntimesDir.user(Path.of("/data/leia/runtimes")),
                        RuntimesDir.user(Path.of("/home/u/.local/share/leia/runtimes")),
                        new RuntimesDir(Path.of("/sys/leia/runtimes"), true));
        assertThat(new Platform(
                                Map.of("LOCALAPPDATA", "/local", "PROGRAMDATA", "/program")::get,
                                HOME,
                                true,
                                Optional.of(jar))
                        .runtimesDirs())
                .containsExactly(
                        RuntimesDir.user(Path.of("/local/leia/runtimes")),
                        new RuntimesDir(Path.of("/program/leia/runtimes"), true));
        assertThat(new Platform(Map.<String, String>of()::get, Optional.empty(), false, Optional.of(jar))
                        .runtimesDirs().stream().filter(dir -> !dir.system()))
                .isEmpty();
    }

    @Test
    void installsIntoTheFirstUserRuntimesFolderThatCanBeWritten() throws IOException {
        Path jar = tmp.resolve("opt/leia/leia.jar");
        Path beside = Files.createDirectories(tmp.resolve("opt/leia/runtimes"));
        Path home = Files.createDirectories(tmp.resolve("home"));
        Platform platform = new Platform(Map.<String, String>of()::get, Optional.of(home), false, Optional.of(jar));
        assertThat(platform.runtimesInstallDir()).hasValue(beside);

        assumeTrue(beside.toFile().setWritable(false) && !Files.isWritable(beside), "root can write anywhere");
        try {
            // A read-only /opt/leia/runtimes: downloads go to the user's data folder instead.
            assertThat(platform.runtimesInstallDir()).hasValue(home.resolve(".local/share/leia/runtimes"));
        } finally {
            beside.toFile().setWritable(true);
        }
    }

    @Test
    void findsNoRuntimesWithoutLeiaJar() throws IOException {
        Files.createDirectories(tmp.resolve("runtimes"));

        assertThat(linux(Map.of("XDG_DATA_HOME", "/data"), HOME).runtimesDirs()).isEmpty();
        assertThat(linux(Map.of("XDG_DATA_HOME", "/data"), HOME).runtimesInstallDir())
                .isEmpty();
    }

    @Test
    void theRuntimesPropertyReplacesTheCandidatesAlone() {
        String before = System.getProperty("leia.runtimes");
        System.setProperty("leia.runtimes", tmp.resolve("elsewhere").toString());
        try {
            assertThat(withJar(Map.of("XDG_DATA_HOME", "/data"), tmp.resolve("leia.jar"))
                            .runtimesDirs())
                    .containsExactly(RuntimesDir.user(tmp.resolve("elsewhere")));
            assertThat(linux(Map.of(), HOME).runtimesDirs())
                    .containsExactly(RuntimesDir.user(tmp.resolve("elsewhere")));
        } finally {
            if (before == null) {
                System.clearProperty("leia.runtimes");
            } else {
                System.setProperty("leia.runtimes", before);
            }
        }
    }

    @Test
    void findsTheJarOfAClassOnlyIfItIsInOne() {
        // The test's own classes are in a directory, JUnit's in a jar.
        assertThat(Platform.jarOf(PlatformTest.class)).isEmpty();
        assertThat(Platform.jarOf(Test.class))
                .hasValueSatisfying(
                        jar -> assertThat(jar.getFileName().toString()).endsWith(".jar"));
        // From the boot class path, without a code source.
        assertThat(Platform.jarOf(String.class)).isEmpty();
    }

    private static Platform withJar(Map<String, String> env, Path jar) {
        return new Platform(env::get, HOME, false, Optional.of(jar));
    }

    private static Platform linux(Map<String, String> env, Optional<Path> home) {
        return new Platform(env::get, home, false, Optional.empty());
    }
}
