package io.github.mfuerstenau.leia.server.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mfuerstenau.leia.runtime.LuceneRuntime;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A runtime's folder that is missing, incomplete or refused (ADR 0073): none of them loads, and none
 * is fatal. Through {@link RuntimeFolders#loadFolder}, which leaves the JVM's folders alone.
 */
class RuntimeFoldersTest {

    private static final RuntimeFolders.Known LUCENE9 = RuntimeFolders.KNOWN.get(1);
    private static final RuntimeFolders.Known LUCENE3 = RuntimeFolders.KNOWN.getLast();

    @TempDir
    Path tmp;

    @Test
    void findsAFolderWithoutItsAdapterMissing() throws IOException {
        List<LuceneRuntime> loaded = new ArrayList<>();

        assertThat(RuntimeFolders.loadFolder(tmp.resolve("lucene9"), LUCENE9, loaded))
                .isEqualTo(new RuntimeFolders.State.Missing());
        Files.createDirectories(tmp.resolve("lucene9"));
        assertThat(RuntimeFolders.loadFolder(tmp.resolve("lucene9"), LUCENE9, loaded))
                .isEqualTo(new RuntimeFolders.State.Missing());
        assertThat(loaded).isEmpty();
    }

    @Test
    void namesTheFirstListedJarThatIsMissing() throws IOException {
        Path folder = Files.createDirectories(tmp.resolve("lucene9"));
        Path testFolder = Path.of(System.getProperty("leia.test.runtimes")).resolve("lucene9");
        Files.copy(testFolder.resolve("leia-lucene9.jar"), folder.resolve("leia-lucene9.jar"));
        List<String> listed = RuntimeFolders.listedJars(folder.resolve("leia-lucene9.jar"));
        assertThat(listed).anySatisfy(jar -> assertThat(jar).startsWith("lucene-core-9."));
        // All but the last.
        for (String jar : listed.subList(0, listed.size() - 1)) {
            Files.copy(testFolder.resolve(jar), folder.resolve(jar));
        }
        List<LuceneRuntime> loaded = new ArrayList<>();

        assertThat(RuntimeFolders.loadFolder(folder, LUCENE9, loaded))
                .isEqualTo(new RuntimeFolders.State.Incomplete(listed.getLast()));
        assertThat(loaded).isEmpty();
    }

    @Test
    void refusesARuntimeBuiltForAnotherInterface() throws IOException {
        Path folder = Files.createDirectories(tmp.resolve("lucene3"));
        adapter(folder.resolve("leia-lucene3.jar"), true);
        List<LuceneRuntime> loaded = new ArrayList<>();

        assertThat(RuntimeFolders.loadFolder(folder, LUCENE3, loaded))
                .isInstanceOfSatisfying(
                        RuntimeFolders.State.Refused.class,
                        refused -> assertThat(refused.reason())
                                .contains(
                                        FutureRuntime.class.getName(),
                                        "is built for runtime interface 99, leia has " + LuceneRuntime.API_VERSION));
        assertThat(loaded).isEmpty();
    }

    @Test
    void refusesAnAdapterWithoutAList() throws IOException {
        Path folder = Files.createDirectories(tmp.resolve("lucene3"));
        adapter(folder.resolve("leia-lucene3.jar"), false);

        assertThat(RuntimeFolders.loadFolder(folder, LUCENE3, new ArrayList<>()))
                .isInstanceOfSatisfying(
                        RuntimeFolders.State.Refused.class,
                        refused -> assertThat(refused.reason()).startsWith("cannot read " + RuntimeFolders.LIST));
    }

    @Test
    void refusesAnAdapterThatIsNotTheOneLeiaJarEmbedsAsOutdated() throws IOException {
        Path folder = Files.createDirectories(tmp.resolve("lucene3"));
        adapter(folder.resolve("leia-lucene3.jar"), true);
        Path embedded = Files.createDirectories(tmp.resolve("embedded/" + RuntimeFolders.EMBEDDED + "lucene3"));
        adapter(embedded.resolve("leia-lucene3.jar"), false);
        List<LuceneRuntime> loaded = new ArrayList<>();

        try (URLClassLoader resources = embedded(tmp.resolve("embedded"))) {
            assertThat(RuntimeFolders.loadFolder(folder, LUCENE3, loaded, resources))
                    .isInstanceOfSatisfying(
                            RuntimeFolders.State.Refused.class,
                            refused -> assertThat(refused.reason())
                                    .startsWith(
                                            "outdated: " + folder.resolve("leia-lucene3.jar") + " is another leia's"));
        }
        assertThat(loaded).isEmpty();
    }

    @Test
    void loadsAnAdapterThatIsTheOneLeiaJarEmbedsOrWithoutOne() throws IOException {
        Path folder = Files.createDirectories(tmp.resolve("lucene3"));
        adapter(folder.resolve("leia-lucene3.jar"), true);
        Path embedded = Files.createDirectories(tmp.resolve("embedded/" + RuntimeFolders.EMBEDDED + "lucene3"));
        Files.copy(folder.resolve("leia-lucene3.jar"), embedded.resolve("leia-lucene3.jar"));

        // Past the comparison, to the runtime's own refusal.
        try (URLClassLoader same = embedded(tmp.resolve("embedded"));
                URLClassLoader none = embedded(tmp.resolve("none"))) {
            for (ClassLoader resources : List.of(same, none)) {
                assertThat(RuntimeFolders.loadFolder(folder, LUCENE3, new ArrayList<>(), resources))
                        .isInstanceOfSatisfying(
                                RuntimeFolders.State.Refused.class,
                                refused -> assertThat(refused.reason()).contains("is built for runtime interface 99"));
            }
        }
    }

    private static Path testFolder() {
        return Path.of(System.getProperty("leia.test.runtimes")).resolve("lucene9");
    }

    /** A copy of the lucene9 folder of the tests, at {@code folder}. */
    private static void copyLucene9(Path folder) throws IOException {
        Files.createDirectories(folder);
        try (var files = Files.list(testFolder())) {
            for (Path file : files.toList()) {
                Files.copy(file, folder.resolve(file.getFileName()));
            }
        }
    }

    private static List<RuntimesDir> user(Path... dirs) {
        return java.util.Arrays.stream(dirs).map(RuntimesDir::user).toList();
    }

    @Test
    void loadsARuntimeFromTheFirstFolderThatHasItAndReportsWhere() throws IOException {
        Path first = Files.createDirectories(tmp.resolve("first"));
        copyLucene9(tmp.resolve("second/lucene9"));
        List<LuceneRuntime> loaded = new ArrayList<>();

        RuntimeFolders.Found found = RuntimeFolders.lookUp(
                LUCENE9, user(first, tmp.resolve("second")), loaded, RuntimeFolders.class.getClassLoader());

        assertThat(found.state()).isEqualTo(new RuntimeFolders.State.Loaded());
        assertThat(found.folder()).isEqualTo(tmp.resolve("second/lucene9"));
        assertThat(loaded).hasSize(1);
    }

    @Test
    void skipsAnOutdatedAdapterForTheNextFolder() throws IOException {
        Path first = tmp.resolve("first");
        copyLucene9(first.resolve("lucene9"));
        copyLucene9(tmp.resolve("second/lucene9"));
        // The adapter that leia.jar embeds is the copy in the second folder; the first is another leia's.
        Path embedded = Files.createDirectories(tmp.resolve("embedded/" + RuntimeFolders.EMBEDDED + "lucene9"));
        Files.copy(tmp.resolve("second/lucene9/leia-lucene9.jar"), embedded.resolve("leia-lucene9.jar"));
        Files.write(first.resolve("lucene9/leia-lucene9.jar"), new byte[] {1});
        List<LuceneRuntime> loaded = new ArrayList<>();

        try (URLClassLoader resources = embedded(tmp.resolve("embedded"))) {
            RuntimeFolders.Found found =
                    RuntimeFolders.lookUp(LUCENE9, user(first, tmp.resolve("second")), loaded, resources);

            assertThat(found.state()).isEqualTo(new RuntimeFolders.State.Loaded());
            assertThat(found.folder()).isEqualTo(tmp.resolve("second/lucene9"));
        }
    }

    @Test
    void reportsTheFirstStateThatIsNotMissingWhenNoFolderLoads() throws IOException {
        Path first = Files.createDirectories(tmp.resolve("first"));
        copyLucene9(tmp.resolve("second/lucene9"));
        List<String> listed = RuntimeFolders.listedJars(tmp.resolve("second/lucene9/leia-lucene9.jar"));
        Files.delete(tmp.resolve("second/lucene9/" + listed.getLast()));

        RuntimeFolders.Found found = RuntimeFolders.lookUp(
                LUCENE9,
                user(first, tmp.resolve("second"), tmp.resolve("third")),
                new ArrayList<>(),
                RuntimeFolders.class.getClassLoader());

        assertThat(found.state()).isEqualTo(new RuntimeFolders.State.Incomplete(listed.getLast()));
        assertThat(found.folder()).isEqualTo(tmp.resolve("second/lucene9"));
    }

    @Test
    void refusesATamperedJarInASystemFolderButNotInAUserFolder() throws IOException {
        copyLucene9(tmp.resolve("system/lucene9"));
        Path embedded = Files.createDirectories(tmp.resolve("embedded/" + RuntimeFolders.EMBEDDED + "lucene9"));
        Files.copy(tmp.resolve("system/lucene9/leia-lucene9.jar"), embedded.resolve("leia-lucene9.jar"));
        String core = RuntimeFolders.listedJars(tmp.resolve("system/lucene9/leia-lucene9.jar")).stream()
                .filter(jar -> jar.startsWith("lucene-core-"))
                .findFirst()
                .orElseThrow();
        // Same size, other bytes.
        byte[] bytes = Files.readAllBytes(tmp.resolve("system/lucene9/" + core));
        bytes[bytes.length / 2] ^= 1;
        Files.write(tmp.resolve("system/lucene9/" + core), bytes);

        try (URLClassLoader resources = embedded(tmp.resolve("embedded"))) {
            RuntimeFolders.Found system = RuntimeFolders.lookUp(
                    LUCENE9, List.of(new RuntimesDir(tmp.resolve("system"), true)), new ArrayList<>(), resources);

            assertThat(system.state())
                    .isInstanceOfSatisfying(
                            RuntimeFolders.State.Refused.class,
                            refused -> assertThat(refused.reason()).contains(core, "SHA-256 differs"));
        }
    }

    @Test
    void trustsAGenuineSystemFolder() throws IOException {
        copyLucene9(tmp.resolve("system/lucene9"));
        Path embedded = Files.createDirectories(tmp.resolve("embedded/" + RuntimeFolders.EMBEDDED + "lucene9"));
        Files.copy(tmp.resolve("system/lucene9/leia-lucene9.jar"), embedded.resolve("leia-lucene9.jar"));

        try (URLClassLoader resources = embedded(tmp.resolve("embedded"))) {
            assertThat(RuntimeFolders.lookUp(
                                    LUCENE9,
                                    List.of(new RuntimesDir(tmp.resolve("system"), true)),
                                    new ArrayList<>(),
                                    resources)
                            .state())
                    .isEqualTo(new RuntimeFolders.State.Loaded());
        }
    }

    @Test
    void refusesASystemFolderWhenLeiaJarEmbedsNoAdapterToCompare() throws IOException {
        copyLucene9(tmp.resolve("system/lucene9"));

        assertThat(RuntimeFolders.lookUp(
                                LUCENE9,
                                List.of(new RuntimesDir(tmp.resolve("system"), true)),
                                new ArrayList<>(),
                                embedded(tmp.resolve("none")))
                        .state())
                .isInstanceOf(RuntimeFolders.State.Refused.class);
    }

    /** A class loader that sees the resources in {@code dir}, as leia.jar's loader sees its own. */
    private static URLClassLoader embedded(Path dir) throws IOException {
        return new URLClassLoader(new URL[] {dir.toUri().toURL()}, null);
    }

    /**
     * An adapter jar with {@link FutureRuntime}, a runtime for interface 99, and its services file;
     * with an empty list if {@code list}.
     */
    private static void adapter(Path jar, boolean list) throws IOException {
        String path = FutureRuntime.class.getName().replace('.', '/') + ".class";
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar));
                InputStream bytes = FutureRuntime.class.getClassLoader().getResourceAsStream(path)) {
            out.putNextEntry(new JarEntry(path));
            bytes.transferTo(out);
            out.closeEntry();
            out.putNextEntry(new JarEntry("META-INF/services/" + LuceneRuntime.class.getName()));
            out.write((FutureRuntime.class.getName() + "\n").getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            if (list) {
                out.putNextEntry(new JarEntry(RuntimeFolders.LIST));
                out.write("# nothing beside it\n".getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
    }
}
