package io.github.mfuerstenau.leia.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * leia-client is headless (ADR 0126): neither TamboUI nor Lucene nor leia-core is on its runtime
 * class path, so that any frontend can use it, and it reads an index only through LeiaBackend
 * (AGENTS.md, hard rule 2). The tests themselves have Lucene 10, for the demo index.
 */
class IsolationTest {

    private static List<String> mainClasspath() {
        String property = System.getProperty("leia.test.mainClasspath");
        assertThat(property).as("the build passes the main class path").isNotNull();
        return Arrays.stream(property.split(File.pathSeparator))
                .filter(entry -> !entry.isBlank())
                .map(entry -> Path.of(entry).getFileName().toString())
                .toList();
    }

    @Test
    void hasNeitherTamboUiNorLuceneNorTheTui() {
        List<String> jars = mainClasspath();

        assertThat(jars).isNotEmpty();
        assertThat(jars)
                .noneMatch(jar -> jar.startsWith("tamboui"))
                .noneMatch(jar -> jar.startsWith("lucene-"))
                .noneMatch(jar -> jar.startsWith("leia-core"))
                .noneMatch(jar -> jar.startsWith("leia-tui"))
                .noneMatch(jar -> jar.startsWith("leia-lucene"));
    }

    @Test
    void leavesTheTuiOutOfItsOwnClasses() {
        assertThat(IsolationTest.class.getClassLoader().getResource("io/github/mfuerstenau/leia/tui/app/App.class"))
                .isNull();
    }
}
