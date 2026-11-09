package io.github.mfuerstenau.leia.tui.jars;

import static io.github.mfuerstenau.leia.tui.TestScreens.key;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import io.github.mfuerstenau.leia.client.platform.DefaultJars;
import io.github.mfuerstenau.leia.client.platform.RuntimesDirs;
import io.github.mfuerstenau.leia.protocol.jars.AddJarsResult;
import io.github.mfuerstenau.leia.protocol.jars.JarComponents;
import io.github.mfuerstenau.leia.tui.TestScreens;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JarsPopupTest {

    private static final JarComponents NONE =
            new JarComponents(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

    @TempDir
    Path tmp;

    private static void typed(JarsPopup popup, String text) {
        text.codePoints().forEach(c -> popup.handleKey(KeyEvent.ofChar(c)));
    }

    private static String render(JarsPopup popup, int width, int height) {
        return TestScreens.render(frame -> popup.render(frame, frame.area()), width, height);
    }

    private static JarsPopup popup() {
        return new JarsPopup(DefaultJars.none(), RuntimesDirs.none(), Optional.empty());
    }

    private static AddJarsResult result(List<String> jars, List<String> alreadyLoaded) {
        return new AddJarsResult(jars, alreadyLoaded, NONE);
    }

    @Test
    void listsTheJarsAndMarksTheDefaultFolderAndTheRuntimes() {
        Path home = Path.of("/home/me");
        Path dir = home.resolve(".config/leia/jars");
        Path runtimes = Path.of("/opt/leia/runtimes");
        JarsPopup popup = new JarsPopup(
                new DefaultJars(dir, DefaultJars.State.LOADED), RuntimesDirs.of(runtimes), Optional.of(home));
        assertThat(render(popup, 100, 20)).contains("Listing the jars…");
        assertThat(popup.waiting()).isTrue();

        popup.jarsLoaded(List.of(
                runtimes.resolve("lucene10").resolve("leia-lucene10.jar").toString(),
                "/opt/modules/kuromoji/lucene-analysis-kuromoji-10.5.2.jar",
                dir.resolve("mine.jar").toString()));

        String screen = render(popup, 100, 20);
        assertThat(popup.waiting()).isFalse();
        assertThat(screen)
                .contains("Default folder: ~" + File.separator + ".config" + File.separator + "leia" + File.separator
                        + "jars ")
                .contains("Runtimes: " + runtimes + " ")
                .contains("Loaded: 3 jars")
                .contains("leia-lucene10.jar  runtimes/lucene10")
                .contains("lucene-analysis-kuromoji-10.5.2.jar  " + Path.of("/opt/modules/kuromoji"))
                .contains("mine.jar  default folder")
                .contains("Enter add   Tab complete   Esc close");
    }

    @Test
    void saysWhyTheDefaultFolderAndTheRuntimesWereNotLoaded() {
        JarsPopup popup = new JarsPopup(
                new DefaultJars(Path.of("/cfg/leia/jars"), DefaultJars.State.SKIPPED),
                RuntimesDirs.of(Path.of("/opt/leia/runtimes")),
                Optional.empty());
        popup.jarsLoaded(List.of());

        String screen = render(popup, 100, 12);

        assertThat(screen)
                .contains(Path.of("/cfg/leia/jars") + "  (left out: --no-default-jars)")
                .contains(Path.of("/opt/leia/runtimes") + "  (missing)")
                .contains("No jars loaded");
        assertThat(render(popup(), 100, 12))
                .contains("Default folder: none (no home directory)")
                .contains("Runtimes: none (not run from leia.jar)");
    }

    @Test
    void saysWhyTheJarsCannotBeListed() {
        JarsPopup popup = popup();

        popup.jarsFailed("boom");

        assertThat(render(popup, 100, 12)).contains("Cannot list the jars: boom");
        assertThat(popup.waiting()).isFalse();
    }

    @Test
    void tabCompletesJarsAndDirectories() throws Exception {
        Files.createDirectory(tmp.resolve("modules"));
        Files.createFile(tmp.resolve("mine.jar"));
        Files.createFile(tmp.resolve("mine.txt"));
        String base = tmp + File.separator;
        JarsPopup popup = popup();

        typed(popup, base + "mi");
        popup.handleKey(key(KeyCode.TAB));
        assertThat(popup.input()).isEqualTo(base + "mine.jar");

        clear(popup);
        typed(popup, base + "mo");
        popup.handleKey(key(KeyCode.TAB));
        assertThat(popup.input()).isEqualTo(base + "modules" + File.separator);

        clear(popup);
        typed(popup, base);
        popup.handleKey(key(KeyCode.TAB));
        assertThat(render(popup, 100, 12)).contains("mine.jar", "modules" + File.separator, "Enter choose");
    }

    @Test
    void theListTakesTheArrowsAndEscBeforeThePopupAndAChosenJarIsAdded() throws Exception {
        Files.createFile(tmp.resolve("a1.jar"));
        Files.createFile(tmp.resolve("a2.jar"));
        String base = tmp + File.separator;
        JarsPopup popup = popup();
        popup.jarsLoaded(IntStream.range(0, 30)
                .mapToObj(i -> String.format("/jars/j%02d.jar", i))
                .toList());
        typed(popup, base + "a");

        popup.handleKey(key(KeyCode.TAB));
        popup.handleKey(key(KeyCode.DOWN));
        assertThat(render(popup, 100, 20)).contains("j00.jar", "Enter choose   ↑↓ move   Esc close");
        popup.handleKey(key(KeyCode.PAGE_DOWN));
        assertThat(render(popup, 100, 20)).as("the jars did not scroll").contains("j00.jar");

        popup.handleKey(key(KeyCode.ENTER));
        assertThat(popup.input()).isEqualTo(base + "a2.jar");
        assertThat(popup.handleKey(key(KeyCode.ENTER))).isEqualTo(new JarsPopup.Submit(base + "a2.jar"));

        clear(popup);
        typed(popup, base + "a");
        popup.handleKey(key(KeyCode.TAB));
        assertThat(popup.handleKey(key(KeyCode.ESCAPE))).isEqualTo(new JarsPopup.Nothing());
        assertThat(render(popup, 100, 20)).contains("Enter add").doesNotContain("Enter choose");
        popup.handleKey(key(KeyCode.PAGE_DOWN));
        assertThat(render(popup, 100, 20)).as("the jars scroll again").doesNotContain("j00.jar");
        assertThat(popup.handleKey(key(KeyCode.ESCAPE))).isEqualTo(new JarsPopup.Close());
    }

    @Test
    void theListGivesTheJarsTheirRowsLast() throws Exception {
        for (int i = 0; i < 12; i++) {
            Files.createFile(tmp.resolve(String.format("a%02d.jar", i)));
        }
        JarsPopup popup = popup();
        popup.jarsLoaded(IntStream.range(0, 30)
                .mapToObj(i -> String.format("/jars/j%02d.jar", i))
                .toList());
        typed(popup, tmp + File.separator + "a");

        popup.handleKey(key(KeyCode.TAB));

        String screen = render(popup, 100, 16);
        assertThat(screen).contains("a00.jar", "a07.jar", "1/12", "Add: ").doesNotContain("a08.jar");
        assertThat(render(popup, 100, 10)).contains("Add: ", "Enter choose");
    }

    private static void clear(JarsPopup popup) {
        while (!popup.input().isEmpty()) {
            popup.handleKey(key(KeyCode.BACKSPACE));
        }
    }

    @Test
    void enterSubmitsUnlessBlankAndEscCloses() {
        JarsPopup popup = popup();
        assertThat(popup.handleKey(key(KeyCode.ENTER))).isEqualTo(new JarsPopup.Nothing());

        typed(popup, "~/x.jar");

        assertThat(popup.handleKey(key(KeyCode.ENTER))).isEqualTo(new JarsPopup.Submit("~/x.jar"));
        assertThat(popup.handleKey(key(KeyCode.ESCAPE))).isEqualTo(new JarsPopup.Close());
    }

    @Test
    void takesNoKeyWhileAdding() {
        JarsPopup popup = popup();
        popup.jarsLoaded(List.of());
        typed(popup, "/x.jar");
        popup.setBusy();

        assertThat(popup.handleKey(key(KeyCode.ESCAPE))).isEqualTo(new JarsPopup.Nothing());
        typed(popup, "y");

        assertThat(popup.input()).isEqualTo("/x.jar");
        assertThat(popup.waiting()).isTrue();
        assertThat(render(popup, 100, 12)).contains("Loading…");
    }

    @Test
    void showsWhatTheJarsAddedAndListsThem() {
        JarsPopup popup = popup();
        popup.jarsLoaded(List.of("/a/old.jar"));
        typed(popup, "/m");
        popup.setBusy();
        assertThat(render(popup, 100, 20)).contains("Loading…");

        popup.added(new AddJarsResult(
                List.of("/m/kuromoji.jar", "/m/codecs.jar"),
                List.of("/a/old.jar"),
                new JarComponents(
                        List.of("org.apache.lucene.analysis.ja.JapaneseAnalyzer"),
                        List.of(),
                        List.of(),
                        List.of("japaneseBaseForm", "japaneseKatakanaStem"),
                        List.of("ES87"),
                        List.of(),
                        List.of(),
                        List.of())));

        assertThat(popup.input()).isEmpty();
        assertThat(popup.waiting()).isFalse();
        String screen = render(popup, 100, 20);
        assertThat(screen)
                .contains("Loaded 2 jars (1 already loaded):")
                .contains("  1 preset: JapaneseAnalyzer")
                .contains("  2 token filters: japaneseBaseForm, japaneseKatakanaStem")
                .contains("  1 codec: ES87")
                .contains("Loaded: 3 jars")
                .contains("codecs.jar  " + Path.of("/m"));
        typed(popup, "x");
        assertThat(render(popup, 100, 20)).doesNotContain("1 codec: ES87");
    }

    @Test
    void saysWhenNothingWasLoadedOrAdded() {
        JarsPopup popup = popup();
        popup.jarsLoaded(List.of("/a.jar", "/b.jar"));

        popup.added(result(List.of(), List.of("/a.jar", "/b.jar")));
        assertThat(render(popup, 100, 12)).contains("All 2 jars are already loaded; nothing was loaded");
        popup.added(result(List.of(), List.of("/a.jar")));
        assertThat(render(popup, 100, 12)).contains("The jar is already loaded; nothing was loaded");

        popup.added(result(List.of("/c.jar"), List.of()));
        assertThat(render(popup, 100, 12))
                .contains("Loaded 1 jar, which adds nothing new: each of its names was taken");
    }

    @Test
    void showsWhyAddingFailed() {
        JarsPopup popup = popup();
        popup.jarsLoaded(List.of());
        typed(popup, "/x.jar");
        popup.setBusy();

        popup.addFailed("Cannot add the jars: no such jar or directory: /x.jar");

        assertThat(popup.input()).isEqualTo("/x.jar");
        assertThat(render(popup, 100, 12)).contains("no such jar or directory: /x.jar");
    }

    @Test
    void scrollsALongList() {
        JarsPopup popup = popup();
        popup.jarsLoaded(IntStream.range(0, 30)
                .mapToObj(i -> String.format("/jars/j%02d.jar", i))
                .toList());

        String screen = render(popup, 80, 12);
        assertThat(screen).contains("j00.jar", "↑↓ scroll").doesNotContain("j29.jar");

        popup.handleKey(key(KeyCode.PAGE_DOWN));
        popup.handleKey(key(KeyCode.DOWN));
        assertThat(render(popup, 80, 12)).doesNotContain("j00.jar");
        for (int i = 0; i < 40; i++) {
            popup.handleKey(key(KeyCode.DOWN));
        }
        assertThat(render(popup, 80, 12)).contains("j29.jar");
        popup.handleKey(key(KeyCode.UP));
        assertThat(render(popup, 80, 12)).doesNotContain("j29.jar");
    }
}
