package io.github.mfuerstenau.leia.tui.runtimes;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The mirrors of Maven Central and their order (ADR 0124). */
class MirrorsTest {

    private static final URI EU = URI.create("https://maven-central-eu.storage-download.googleapis.com/maven2/");

    @TempDir
    Path tmp;

    @Test
    void leiasOwnListStartsWithCentralAndIsAllHttps() {
        List<Mirror> builtIn = Mirrors.builtIn();

        assertThat(builtIn.getFirst().uri()).isEqualTo(Mirrors.CENTRAL);
        assertThat(builtIn.getFirst().name()).isEqualTo("Maven Central");
        assertThat(builtIn).hasSizeGreaterThanOrEqualTo(5).allSatisfy(mirror -> {
            assertThat(mirror.uri().getScheme()).isEqualTo("https");
            assertThat(mirror.uri().toString()).endsWith("/");
            assertThat(mirror.origin()).isEqualTo(Mirror.Origin.BUILT_IN);
        });
        assertThat(builtIn).extracting(Mirror::uri).doesNotHaveDuplicates().contains(EU);
    }

    @Test
    void parsesUrlsWithAndWithoutNamesAndSkipsCommentsAndBlankLines() {
        Mirrors.Parsed parsed = Mirrors.parse(
                List.of(
                        "# the company's",
                        "",
                        "https://maven.example.com/central   Example, inside  # the nearest",
                        "   https://other.example.com/maven2",
                        "http://plain.example.com/",
                        "not a url"),
                Mirror.Origin.FILE,
                false);

        assertThat(parsed.mirrors())
                .containsExactly(
                        new Mirror(
                                URI.create("https://maven.example.com/central/"),
                                "Example, inside",
                                Mirror.Origin.FILE),
                        new Mirror(
                                URI.create("https://other.example.com/maven2/"),
                                "other.example.com",
                                Mirror.Origin.FILE));
        assertThat(parsed.problems())
                .containsExactly(
                        "line 5: 'http://plain.example.com/' is http, which a proxy may rewrite; use https, or start"
                                + " leia with -Dleia.download.insecure=true",
                        "line 6: 'not' is not an absolute URL with a host, such as " + Mirrors.CENTRAL);
    }

    @Test
    void checksTheUrlOfAMirror() {
        assertThat(Mirrors.check("https://maven.example.com/", false)).isEmpty();
        assertThat(Mirrors.check("http://maven.example.com/", false))
                .hasValueSatisfying(problem -> assertThat(problem).contains("is http"));
        assertThat(Mirrors.check("http://maven.example.com/", true)).isEmpty();
        assertThat(Mirrors.check("ftp://maven.example.com/", true))
                .hasValueSatisfying(problem -> assertThat(problem).endsWith("must be https"));
        assertThat(Mirrors.check("/maven2/", false))
                .hasValueSatisfying(problem -> assertThat(problem).contains("not an absolute URL"));
        assertThat(Mirrors.check("https://maven.example.com/?token=1", false))
                .hasValueSatisfying(problem -> assertThat(problem).contains("query"));
        assertThat(Mirrors.check("https://someone@maven.example.com/", false))
                .hasValueSatisfying(problem -> assertThat(problem).contains("credentials"));
        assertThat(Mirrors.check("https://maven example", false))
                .hasValueSatisfying(problem -> assertThat(problem).contains("is not a URL"));
    }

    @Test
    void ordersThePropertyTheOptionsTheSavedTheFileAndLeiasOwnEachOnce() throws Exception {
        Path saved = Files.writeString(tmp.resolve("mirror"), EU + " Google Cloud, EU\n");
        Path file = Files.writeString(
                tmp.resolve("mirrors"),
                "https://file.example.com/\n" + Mirrors.CENTRAL + "\nhttp://bad.example.com/\n");

        Mirrors.Loaded loaded = Mirrors.resolve(
                "https://property.example.com/maven2",
                List.of("https://option.example.com/", "https://property.example.com/maven2/"),
                Optional.of(saved),
                Optional.of(file),
                false);

        List<Mirror> mirrors = loaded.mirrors();
        assertThat(mirrors.subList(0, 5))
                .extracting(mirror -> mirror.uri().toString())
                .containsExactly(
                        "https://property.example.com/maven2/",
                        "https://option.example.com/",
                        EU.toString(),
                        "https://file.example.com/",
                        Mirrors.CENTRAL.toString());
        assertThat(mirrors.subList(0, 5))
                .extracting(Mirror::origin)
                .containsExactly(
                        Mirror.Origin.PROPERTY,
                        Mirror.Origin.OPTION,
                        Mirror.Origin.SAVED,
                        Mirror.Origin.FILE,
                        Mirror.Origin.FILE);
        // A mirror of leia's own list, named in the file by its URL only, keeps its name.
        assertThat(mirrors.get(4).name()).isEqualTo("Maven Central");
        assertThat(mirrors).extracting(Mirror::uri).doesNotHaveDuplicates();
        assertThat(mirrors).hasSize(3 + Mirrors.builtIn().size());
        assertThat(loaded.problems())
                .singleElement()
                .satisfies(problem ->
                        assertThat(problem).startsWith(file + ", line 3: 'http://bad.example.com/' is http"));
    }

    @Test
    void reportsABadPropertyAndKeepsLeiasOwnList() {
        Mirrors.Loaded loaded = Mirrors.resolve(
                "http://insecure.example.com/",
                List.of(),
                Optional.empty(),
                Optional.of(tmp.resolve("missing")),
                false);

        assertThat(loaded.mirrors()).isEqualTo(Mirrors.builtIn());
        assertThat(loaded.problems())
                .singleElement()
                .satisfies(problem ->
                        assertThat(problem).startsWith("-Dleia.download.url: 'http://insecure.example.com/' is http"));
    }

    @Test
    void savesTheMirrorChosenSoThatItComesFirstNextTime() {
        Path saved = tmp.resolve("config/leia/mirror");
        Mirror eu = Mirrors.builtIn().stream()
                .filter(mirror -> mirror.uri().equals(EU))
                .findFirst()
                .orElseThrow();

        Mirrors.save(saved, eu);

        Mirrors.Loaded loaded = Mirrors.resolve(null, List.of(), Optional.of(saved), Optional.empty(), false);
        assertThat(loaded.mirrors().getFirst()).isEqualTo(new Mirror(EU, "Google Cloud, EU", Mirror.Origin.SAVED));
        assertThat(loaded.mirrors()).extracting(Mirror::uri).doesNotHaveDuplicates();
    }

    @Test
    void putsAMirrorFirst() {
        List<Mirror> builtIn = Mirrors.builtIn();
        Mirror typed = Mirror.of(URI.create("https://typed.example.com/maven2"), Mirror.Origin.TYPED);

        assertThat(Mirrors.withFirst(builtIn, builtIn.get(2)))
                .startsWith(builtIn.get(2), builtIn.get(0), builtIn.get(1))
                .hasSize(builtIn.size());
        assertThat(Mirrors.withFirst(builtIn, typed)).startsWith(typed).hasSize(builtIn.size() + 1);
    }
}
