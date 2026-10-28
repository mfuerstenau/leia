package io.github.mfuerstenau.leia.tui.about;

import static io.github.mfuerstenau.leia.tui.TestScreens.key;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tamboui.text.Line;
import dev.tamboui.tui.event.KeyCode;
import io.github.mfuerstenau.leia.protocol.version.JavaRuntime;
import io.github.mfuerstenau.leia.protocol.version.VersionResult;
import io.github.mfuerstenau.leia.tui.TestScreens;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AboutWindowTest {

    private static final VersionResult VERSION = new VersionResult(
            "leia-server", "1.2.3", "abc1234", "2026-10-24T10:00:00Z", new JavaRuntime("25", "v", "/h"));

    private static final String NOTICE = "leia\nCopyright 2026 mfuerstenau\n\nThis product includes Lucene.";

    private static AboutWindow window(String notice, String... environment) {
        return new AboutWindow(
                VERSION, java.util.Arrays.stream(environment).map(Line::from).toList(), notice);
    }

    private static String render(AboutWindow window, int width, int height) {
        return TestScreens.render(frame -> window.render(frame, frame.area()), width, height);
    }

    @Test
    void theHeaderHasTheVersionAuthorAndProject() {
        String screen = render(window(NOTICE), 120, 40);

        assertThat(screen).contains(" About leia").contains("leia 1.2.3", "build abc1234", "by mfuerstenau", About.URL);
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
        assertThat(top).contains(" About leia, lines 1–").contains("leia 1.2.3");

        window.handleKey(key('G'));
        String bottom = render(window, 50, 10);
        assertThat(bottom).contains("This product includes Lucene.").doesNotContain("by mfuerstenau");

        window.handleKey(key('g'));
        assertThat(render(window, 50, 10)).contains("leia 1.2.3");
    }

    @Test
    void yCopiesTheProjectUrl() {
        AboutWindow window = window(NOTICE);

        assertThat(window.handleKey(key('y'))).isEqualTo(new AboutWindow.Copy(About.URL));
        assertThat(render(window, 100, 30)).contains("Copied " + About.URL);
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
