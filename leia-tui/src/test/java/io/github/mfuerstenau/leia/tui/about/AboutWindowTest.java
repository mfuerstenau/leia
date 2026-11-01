package io.github.mfuerstenau.leia.tui.about;

import static io.github.mfuerstenau.leia.tui.TestScreens.key;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tamboui.text.Line;
import dev.tamboui.tui.event.KeyCode;
import io.github.mfuerstenau.leia.protocol.version.JavaRuntime;
import io.github.mfuerstenau.leia.protocol.version.VersionResult;
import io.github.mfuerstenau.leia.tui.TestScreens;
import io.github.mfuerstenau.leia.tui.widgets.Keys;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AboutWindowTest {

    private static final VersionResult VERSION = new VersionResult(
            "leia-server", "1.2.3", "abc1234", "2026-10-24T10:00:00Z", new JavaRuntime("25", "v", "/h"));

    private static final String NOTICE = "leia\nCopyright 2026 mfuerstenau\n\nThis product includes Lucene.";

    private static final Keys KEYS = Keys.builder()
            .key(Keys.Group.SORT, "s/S", "sort")
            .more(Keys.Group.SHOW, "n", "number format")
            .key(Keys.Group.MOVE, "←→", "doc")
            .build();

    /** A window on its About tab. */
    private static AboutWindow window(String notice, String... environment) {
        AboutWindow window = keysWindow(notice, environment);
        window.handleKey(key(KeyCode.TAB));
        return window;
    }

    /** A window on its Keys tab, as it opens. */
    private static AboutWindow keysWindow(String notice, String... environment) {
        return new AboutWindow(
                VERSION, java.util.Arrays.stream(environment).map(Line::from).toList(), notice, "Documents", KEYS);
    }

    private static String render(AboutWindow window, int width, int height) {
        return TestScreens.render(frame -> window.render(frame, frame.area()), width, height);
    }

    @Test
    void theHeaderHasTheVersionAuthorAndProject() {
        String screen = render(window(NOTICE), 120, 40);

        assertThat(screen)
                .contains(" About · leia 1.2.3")
                .contains("leia 1.2.3", "build abc1234", "by mfuerstenau", About.URL);
    }

    @Test
    void theLicenceAndTheNoticeFollow() {
        String screen = render(window(NOTICE), 120, 60);

        assertThat(screen)
                .contains("Apache License, Version 2.0: " + About.LICENSE_URL)
                .contains("This product includes Lucene.");
    }

    @Test
    void aMissingNoticeIsSaidAndLeavesTheAuthorOut() {
        String screen = render(window(About.NOTICE_MISSING), 120, 60);

        assertThat(screen).contains("NOTICE not found").doesNotContain("by ");
    }

    @Test
    void theEnvironmentIsShown() {
        String screen = render(window(NOTICE, "Java 25 (v) in /h", "Runtimes: Lucene 10.2"), 120, 60);

        assertThat(screen).contains("Java 25 (v) in /h", "Runtimes: Lucene 10.2");
    }

    @Test
    void theAuthorIsTheRestOfTheCopyrightLine() {
        assertThat(About.author(NOTICE)).hasValue("mfuerstenau");
        assertThat(About.author("Copyright 2001-2026 The Apache Software Foundation"))
                .hasValue("The Apache Software Foundation");
        assertThat(About.author("no line here")).isEqualTo(Optional.empty());
    }

    @Test
    void scrollingMovesTheContentAndTheTitleSaysWhere() {
        // Narrow, so that the logo leaves no room beside the text and the header is its first lines.
        AboutWindow window = window(NOTICE);
        String top = render(window, 50, 10);
        assertThat(top).contains("· leia 1.2.3, lines 1–").contains("leia 1.2.3");

        window.handleKey(key('G'));
        String bottom = render(window, 50, 10);
        assertThat(bottom).contains("This product includes Lucene.").doesNotContain("by mfuerstenau");

        window.handleKey(key('g'));
        assertThat(render(window, 50, 10)).contains("leia 1.2.3");
    }

    @Test
    void yCopiesTheProjectUrlOnTheAboutTabOnly() {
        AboutWindow keys = keysWindow(NOTICE);
        assertThat(keys.handleKey(key('y'))).isEqualTo(new AboutWindow.Nothing());
        assertThat(render(keys, 100, 30)).doesNotContain("Copied");

        AboutWindow window = window(NOTICE);
        assertThat(window.handleKey(key('y'))).isEqualTo(new AboutWindow.Copy(About.URL));
        assertThat(render(window, 100, 30)).contains("Copied " + About.URL);
    }

    @Test
    void opensOnTheKeysOfTheScreenByGroupAndTabSwitches() {
        AboutWindow window = keysWindow(NOTICE);
        assertThat(window.tab()).isEqualTo(AboutWindow.Tab.KEYS);

        String screen = render(window, 100, 30);
        assertThat(screen)
                .contains(" Keys  About · Documents")
                .contains("Tab about")
                .doesNotContain("by mfuerstenau");
        // Every key, the footer's and the others, each group once and in the groups' order.
        List<String> rows = screen.lines().filter(row -> row.contains("  ")).toList();
        assertThat(String.join("\n", rows))
                .containsPattern("Move +←→ +doc")
                .containsPattern("Show +n +number format")
                .containsPattern("Sort +s/S +sort");
        assertThat(screen.indexOf("Move")).isLessThan(screen.indexOf("Show"));
        assertThat(screen.indexOf("Show")).isLessThan(screen.indexOf("Sort"));

        window.handleKey(key(KeyCode.TAB));
        assertThat(window.tab()).isEqualTo(AboutWindow.Tab.ABOUT);
        assertThat(render(window, 100, 30)).contains("by mfuerstenau").doesNotContain("number format");
        window.handleKey(key(KeyCode.TAB));
        assertThat(window.tab()).isEqualTo(AboutWindow.Tab.KEYS);
    }

    @Test
    void escAndQuestionMarkClose() {
        assertThat(window(NOTICE).handleKey(key(KeyCode.ESCAPE))).isEqualTo(new AboutWindow.Close());
        assertThat(window(NOTICE).handleKey(key('?'))).isEqualTo(new AboutWindow.Close());
        assertThat(window(NOTICE).handleKey(key('x'))).isEqualTo(new AboutWindow.Nothing());
    }

    @Test
    void theBundledNoticeIsFoundOrSaidMissing() {
        assertThat(About.notice()).isNotBlank();
    }
}
