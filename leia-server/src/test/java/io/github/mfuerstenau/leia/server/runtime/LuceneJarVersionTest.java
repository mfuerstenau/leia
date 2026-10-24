package io.github.mfuerstenau.leia.server.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class LuceneJarVersionTest {

    @TempDir
    Path tmp;

    @ParameterizedTest
    @CsvSource({
        "lucene-core-9.10.0.jar, 9.10.0, 9",
        "lucene-core-10.2.2.jar, 10.2.2, 10",
        "lucene-core-9.9.0-snapshot-a1b2c3d.jar, 9.9.0-snapshot-a1b2c3d, 9",
        "lucene-core-8.11.4.jar, 8.11.4, 8",
    })
    void readsTheVersionInTheFileName(String fileName, String version, int major) {
        assertThat(LuceneJarVersion.fromFileName(fileName)).hasValue(new LuceneJarVersion(version, major));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "lucene-core.jar",
                "lucene-core-snapshot.jar",
                "lucene-core-9.jar",
                "lucene-backward-codecs-9.10.0.jar",
                "lucene-core-99999999999.1.jar"
            })
    void hasNoneInOtherNames(String fileName) {
        assertThat(LuceneJarVersion.fromFileName(fileName)).isEmpty();
    }

    @Test
    void readsTheImplementationVersionOfTheManifest() {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes()
                .put(
                        Attributes.Name.IMPLEMENTATION_VERSION,
                        "9.10.0 695c0ac84508438302cd346a812cfa2fdc5a10df - 2024-02-14");
        manifest.getMainAttributes().put(Attributes.Name.SPECIFICATION_VERSION, "1.0");

        assertThat(LuceneJarVersion.fromManifest(manifest)).hasValue(new LuceneJarVersion("9.10.0", 9));
    }

    @Test
    void fallsBackToTheSpecificationVersion() {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.IMPLEMENTATION_VERSION, "unknown");
        manifest.getMainAttributes().put(Attributes.Name.SPECIFICATION_VERSION, "10.2.2");

        assertThat(LuceneJarVersion.fromManifest(manifest)).hasValue(new LuceneJarVersion("10.2.2", 10));
        assertThat(LuceneJarVersion.fromManifest(new Manifest())).isEmpty();
    }

    @Test
    void readsTheManifestOfAJarWhoseNameHasNone() throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.IMPLEMENTATION_VERSION, "9.11.1");
        Path jar = tmp.resolve("lucene-core-snapshot.jar");
        try (OutputStream out = Files.newOutputStream(jar);
                JarOutputStream _ = new JarOutputStream(out, manifest)) {
            // Only the manifest.
        }

        assertThat(LuceneJarVersion.of(jar)).hasValue(new LuceneJarVersion("9.11.1", 9));
        // The name wins, without reading the jar.
        assertThat(LuceneJarVersion.of(tmp.resolve("lucene-core-9.12.3.jar")))
                .hasValue(new LuceneJarVersion("9.12.3", 9));
    }
}
