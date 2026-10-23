package io.github.mfuerstenau.leia.server.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mfuerstenau.leia.runtime.LuceneRuntime;
import java.io.IOException;
import java.io.InputStream;
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
