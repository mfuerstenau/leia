package io.github.mfuerstenau.leia.core;

import static io.github.mfuerstenau.leia.core.TestIndexes.testIndex;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mfuerstenau.leia.core.AnalysisComponents.Available;
import io.github.mfuerstenau.leia.core.AnalysisComponents.AvailableAnalyzer;
import io.github.mfuerstenau.leia.core.AnalyzerSpec.Component;
import io.github.mfuerstenau.leia.core.TextAnalysis.Token;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.NumericDocValues;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Loads Lucene's phonetic and stempel modules as analysis jars (ADR 0016), and the test codec jar
 * (ADR 0018). Lucene's lists of
 * factories, codecs and formats only grow, so this runs in a JVM of its own (the {@code jarsTest} task).
 */
@Tag("jars")
class ExtensionJarsTest {

    /** Each module with its dependencies, in a directory of its own. */
    private static final Path JARS = Path.of(System.getProperty("leia.test.analysis.jars"));

    /** A codec and one format of each kind; the custom-codec and custom-formats indexes need it. */
    private static final Path CODEC_JAR = Path.of(System.getProperty("leia.test.codec.jar"));

    @TempDir
    Path tmp;

    @Test
    void aJarAddsItsCodecsAndFormats() throws Exception {
        ExtensionJars.load(List.of(CODEC_JAR));

        try (ReadOnlyIndex index = ReadOnlyIndex.open(testIndex("custom-codec"))) {
            assertThat(index.summary().maxDoc()).isEqualTo(3);
            assertThat(index.commits().unreadable()).isEmpty();
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
                        WordSetSupport.OPTIONAL));
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
