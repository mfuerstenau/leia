package io.github.mfuerstenau.leia.core;

import static io.github.mfuerstenau.leia.core.TestIndexes.testIndex;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mfuerstenau.leia.core.AnalysisComponents.Available;
import io.github.mfuerstenau.leia.core.AnalysisComponents.AvailableAnalyzer;
import io.github.mfuerstenau.leia.core.AnalyzerSpec.Component;
import io.github.mfuerstenau.leia.core.TextAnalysis.Token;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenFilterFactory;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.NumericDocValues;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Loads Lucene's phonetic and stempel modules as analysis jars (ADR 0016), the test codec jar
 * (ADR 0018) and jars made by the tests (ADR 0024). Lucene's lists of
 * factories, codecs and formats only grow, so this runs in a JVM of its own (the {@code jarsTest} task).
 */
@Tag("jars")
class ExtensionJarsTest {

    /** Each module with its dependencies, in a directory of its own. */
    private static final Path JARS = Path.of(System.getProperty("leia.test.analysis.jars"));

    /** A codec and one format of each kind; the custom-codec and custom-formats indexes need it. */
    private static final Path CODEC_JAR = Path.of(System.getProperty("leia.test.codec.jar"));

    /** Where the tests write the jars they make. */
    private static final Path MADE_JARS = Path.of("build", "tmp", "jarsTest", "made-jars");

    @TempDir
    Path tmp;

    @Test
    void aJarAddsItsCodecsAndFormats() throws Exception {
        ExtensionJars.load(List.of(CODEC_JAR));

        try (ReadOnlyIndex index = ReadOnlyIndex.open(testIndex("custom-codec"))) {
            assertThat(index.indexes().getFirst().maxDoc()).isEqualTo(3);
            assertThat(index.commits(0).unreadable()).isEmpty();
        }
        try (ReadOnlyIndex index = ReadOnlyIndex.open(testIndex("custom-formats"))) {
            LeafReader leaf = index.reader().leaves().getFirst().reader();
            assertThat(leaf.terms("title").size()).isEqualTo(5);
            NumericDocValues rank = leaf.getNumericDocValues("rank");
            assertThat(rank.advanceExact(2)).isTrue();
            assertThat(rank.longValue()).isEqualTo(2);
            assertThat(leaf.getFloatVectorValues("vector").size()).isEqualTo(3);
        }
    }

    @Test
    void aDirectoryAddsTheFactoriesOfItsJars() throws Exception {
        List<Path> loaded = ExtensionJars.load(List.of(JARS.resolve("phonetic")));

        assertThat(loaded)
                .extracting(jar -> jar.getFileName().toString())
                .satisfiesExactly(
                        codec -> assertThat(codec).startsWith("commons-codec-"),
                        phonetic -> assertThat(phonetic).startsWith("lucene-analysis-phonetic-"));
        assertThat(loaded).allMatch(Path::isAbsolute);
        assertThat(Analyzers.components().tokenFilters())
                .contains(new Available(
                        "doubleMetaphone", "org.apache.lucene.analysis.phonetic.DoubleMetaphoneFilterFactory"));
        TextAnalysis analysis = analyze(
                new AnalyzerSpec.Custom(
                        List.of(),
                        new Component("whitespace", Map.of()),
                        List.of(new Component("doubleMetaphone", Map.of("inject", "false"))),
                        Optional.empty()),
                "Smith");
        assertThat(analysis.stages().get(1).tokens()).extracting(Token::term).containsExactly("SM0", "XMT");
    }

    @Test
    void aJarAddsItsAnalyzersAsPresets() throws Exception {
        ExtensionJars.load(List.of(stempelJar()));

        assertThat(Analyzers.components().analyzers())
                .contains(new AvailableAnalyzer(
                        "PolishAnalyzer",
                        "org.apache.lucene.analysis.pl.PolishAnalyzer",
                        WordSetSupport.OPTIONAL,
                        WordSetSupport.OPTIONAL,
                        List.of()));
        TextAnalysis analysis =
                analyze(new AnalyzerSpec.Preset("org.apache.lucene.analysis.pl.PolishAnalyzer"), "kot i pies");
        // The stop words and the stemmer table are resources of the jar.
        assertThat(analysis.stages().getFirst().tokens())
                .extracting(Token::term)
                .containsExactly("kot", "pies");
    }

    @Test
    void aCustomAnalyzerReadsResourceFilesFromTheJars() throws Exception {
        ExtensionJars.load(List.of(stempelJar()));

        TextAnalysis analysis = analyze(
                new AnalyzerSpec.Custom(
                        List.of(),
                        new Component("whitespace", Map.of()),
                        List.of(new Component("stop", Map.of("words", "org/apache/lucene/analysis/pl/stopwords.txt"))),
                        Optional.of(tmp)),
                "kot i pies");

        assertThat(analysis.stages().get(1).tokens()).extracting(Token::term).containsExactly("kot", "pies");
    }

    @Test
    void aMissingDependencyFailsTheLoad() throws Exception {
        Path phonetic;
        try (Stream<Path> files = Files.list(JARS.resolve("phonetic"))) {
            phonetic = files.filter(file -> file.getFileName().toString().startsWith("lucene-analysis-phonetic-"))
                    .findFirst()
                    .orElseThrow();
        }

        assertThatThrownBy(() -> ExtensionJars.load(List.of(phonetic)))
                .isInstanceOf(ExtensionJarException.class)
                .hasMessageStartingWith("cannot load the analysis factories: java.lang.NoClassDefFoundError: ")
                .hasMessageContaining("org/apache/commons/codec/");
    }

    @Test
    void aPathMustBeAJarOrADirectoryWithJars() throws IOException {
        Path text = Files.writeString(tmp.resolve("notes.txt"), "not a jar");
        Path empty = Files.createDirectory(tmp.resolve("empty"));
        Path missing = tmp.resolve("missing.jar");

        assertThatThrownBy(() -> ExtensionJars.load(List.of(text)))
                .isInstanceOf(ExtensionJarException.class)
                .hasMessage("not a jar: " + text);
        assertThatThrownBy(() -> ExtensionJars.load(List.of(empty)))
                .isInstanceOf(ExtensionJarException.class)
                .hasMessage("no jars in " + empty);
        assertThatThrownBy(() -> ExtensionJars.load(List.of(missing)))
                .isInstanceOf(ExtensionJarException.class)
                .hasMessage("no such jar or directory: " + missing);
    }

    @Test
    void noPathsLoadNothing() throws Exception {
        assertThat(ExtensionJars.load(List.of())).isEmpty();
    }

    @Test
    void aJarThatFailsLeavesNoNamesBehind() throws Exception {
        // The token filter would be reloaded before the codec fails.
        Path broken = jar(
                "broken.jar",
                Map.of(
                        "META-INF/services/org.apache.lucene.analysis.TokenFilterFactory",
                        (JarTestFilterFactory.Other.class.getName() + "\n").getBytes(StandardCharsets.UTF_8),
                        "META-INF/services/org.apache.lucene.codecs.Codec",
                        "does.not.Exist\n".getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> ExtensionJars.add(List.of(broken)))
                .isInstanceOf(ExtensionJarException.class)
                .hasMessageStartingWith("cannot load the codecs and formats: ")
                .hasMessageContaining("does.not.Exist");

        assertThat(TokenFilterFactory.availableTokenFilters()).doesNotContain(JarTestFilterFactory.Other.NAME);
        assertThat(ExtensionJars.jars()).doesNotContain(broken);
    }

    @Test
    void addingSaysWhatTheJarsAddedAndLoadsAJarOnce() throws Exception {
        Path jar = jar(
                "added.jar",
                Map.of(
                        "META-INF/services/org.apache.lucene.analysis.TokenFilterFactory",
                        (JarTestFilterFactory.class.getName() + "\n").getBytes(StandardCharsets.UTF_8),
                        JarTestAnalyzer.class.getName().replace('.', '/') + ".class",
                        classFile(JarTestAnalyzer.class)));

        AddedJars added = ExtensionJars.add(List.of(jar, jar.getParent()));

        assertThat(added.jars()).containsExactly(jar);
        assertThat(added.alreadyLoaded()).isEmpty();
        assertThat(added.added())
                .isEqualTo(new JarComponents(
                        List.of(JarTestAnalyzer.class.getName()),
                        List.of(),
                        List.of(),
                        List.of(JarTestFilterFactory.NAME),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of()));
        assertThat(ExtensionJars.jars()).endsWith(jar);
        assertThat(TokenFilterFactory.lookupClass(JarTestFilterFactory.NAME)).isEqualTo(JarTestFilterFactory.class);

        AddedJars again = ExtensionJars.add(List.of(jar.getParent().resolve(".").resolve("added.jar")));

        assertThat(again).isEqualTo(new AddedJars(List.of(), List.of(jar), JarComponents.NONE));
    }

    /**
     * A jar made by the test, alone in a directory. It outlives the test, unlike {@link #tmp}:
     * Lucene reads the service files of every loaded jar again on each later load.
     */
    private static Path jar(String name, Map<String, byte[]> entries) throws IOException {
        Path jar = Files.createDirectories(MADE_JARS.resolve(name.replace(".jar", "")))
                .resolve(name)
                .toAbsolutePath();
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
        return jar;
    }

    private static byte[] classFile(Class<?> type) throws IOException {
        try (InputStream in = type.getResourceAsStream(type.getSimpleName() + ".class")) {
            return in.readAllBytes();
        }
    }

    private static Path stempelJar() throws IOException {
        try (Stream<Path> files = Files.list(JARS.resolve("stempel"))) {
            return files.filter(file -> file.getFileName().toString().startsWith("lucene-analysis-stempel-"))
                    .findFirst()
                    .orElseThrow();
        }
    }

    private static TextAnalysis analyze(AnalyzerSpec spec, String text) throws Exception {
        try (Analyzer analyzer = Analyzers.build(spec)) {
            return Analyzers.analyze(analyzer, text, 100);
        }
    }
}
