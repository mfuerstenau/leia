package io.github.mfuerstenau.leia.tui.prompt;

import static io.github.mfuerstenau.leia.tui.TestScreens.alt;
import static io.github.mfuerstenau.leia.tui.TestScreens.altGr;
import static io.github.mfuerstenau.leia.tui.TestScreens.ctrl;
import static io.github.mfuerstenau.leia.tui.TestScreens.key;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;
import io.github.mfuerstenau.leia.protocol.index.DirectoryImpl;
import io.github.mfuerstenau.leia.protocol.index.OpenMode;
import io.github.mfuerstenau.leia.tui.TestScreens;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PathPromptTest {

    @TempDir
    Path tmp;

    private static PathPrompt prompt(String input) {
        return new PathPrompt(input, Optional.empty());
    }

    private static void typed(PathPrompt prompt, String text) {
        text.codePoints().forEach(c -> prompt.handleKey(KeyEvent.ofChar(c)));
    }

    private static String render(PathPrompt prompt, int width, int height) {
        return TestScreens.render(frame -> prompt.render(frame, frame.area()), width, height);
    }

    @Test
    void editsAtTheCursor() {
        PathPrompt prompt = prompt("/data/idx");

        prompt.handleKey(key(KeyCode.LEFT));
        prompt.handleKey(key(KeyCode.LEFT));
        typed(prompt, "é");
        prompt.handleKey(key(KeyCode.BACKSPACE));
        prompt.handleKey(key(KeyCode.BACKSPACE));
        typed(prompt, "ü");
        prompt.handleKey(key(KeyCode.DELETE));

        assertThat(prompt.input()).isEqualTo("/data/üx");
        prompt.handleKey(key(KeyCode.HOME));
        prompt.handleKey(key(KeyCode.DELETE));
        prompt.handleKey(key(KeyCode.END));
        typed(prompt, "y");
        assertThat(prompt.input()).isEqualTo("data/üxy");
    }

    @Test
    void controlKeysDeleteByComponentAndLine() {
        PathPrompt prompt = prompt("/data/indexes/products/");

        prompt.handleKey(ctrl('w'));
        assertThat(prompt.input()).isEqualTo("/data/indexes/");
        prompt.handleKey(ctrl('w'));
        assertThat(prompt.input()).isEqualTo("/data/");
        prompt.handleKey(ctrl('a'));
        prompt.handleKey(ctrl('k'));
        assertThat(prompt.input()).isEmpty();

        prompt = prompt("/data/index");
        prompt.handleKey(key(KeyCode.LEFT));
        prompt.handleKey(ctrl('u'));
        assertThat(prompt.input()).isEqualTo("x");
    }

    @Test
    void enterSubmitsUnlessBlankAndEscCancels() {
        PathPrompt prompt = prompt("  ");
        assertThat(prompt.handleKey(key(KeyCode.ENTER))).isEqualTo(new PathPrompt.Nothing());

        typed(prompt, "x");
        assertThat(prompt.handleKey(key(KeyCode.ENTER))).isEqualTo(new PathPrompt.Submit("  x"));
        assertThat(prompt.handleKey(key(KeyCode.ESCAPE))).isEqualTo(new PathPrompt.Cancel());
    }

    @Test
    void controlCharactersAreNotTyped() {
        PathPrompt prompt = prompt("");

        prompt.handleKey(ctrl('x'));
        prompt.handleKey(alt('b'));
        prompt.handleKey(KeyEvent.ofChar('B', KeyModifiers.SHIFT));

        assertThat(prompt.input()).isEqualTo("B");
    }

    @Test
    void typesBackslashesThatWindowsReportsWithAltGr() {
        PathPrompt prompt = prompt("");

        for (char c : "C:\\data\\idx".toCharArray()) {
            prompt.handleKey(c == '\\' ? altGr(c) : key(c));
        }

        assertThat(prompt.input()).isEqualTo("C:\\data\\idx");
    }

    @Test
    void anEditClearsTheError() {
        PathPrompt prompt = prompt("/x");
        prompt.setBusy();
        prompt.setError("No such file or directory: /x");
        assertThat(prompt.error()).hasValue("No such file or directory: /x");

        prompt.handleKey(key(KeyCode.LEFT));
        assertThat(prompt.error()).isPresent();
        typed(prompt, "y");
        assertThat(prompt.error()).isEmpty();
    }

    @Test
    void tabCompletesAndShowsCandidates() throws IOException {
        for (String dir : List.of("alpha", "alps")) {
            Files.createDirectory(tmp.resolve(dir));
        }
        String base = tmp + File.separator;
        PathPrompt prompt = prompt(base + "a");

        prompt.handleKey(key(KeyCode.TAB));

        assertThat(prompt.input()).isEqualTo(base + "alp");
        assertThat(render(prompt, 200, 10)).contains("alpha  alps");
        typed(prompt, "h");
        assertThat(render(prompt, 200, 10)).doesNotContain("alpha  alps");
    }

    @Test
    void showsTheErrorAndScrollsLongInput() {
        PathPrompt prompt = prompt("/" + "x".repeat(100) + "/end");
        prompt.setError("No such file or directory (INDEX_NOT_FOUND -32001)");

        String screen = render(prompt, 60, 12);

        assertThat(screen).contains("Open index", "xxx/end", "INDEX_NOT_FOUND", "Esc cancel");
    }

    @Test
    void upAndDownStepThroughTheRecentPathsButSkipTheInput() {
        PathPrompt prompt = prompt("/b").withRecent(List.of("/a", "/b", "/c"));
        prompt.setError("Not a Lucene index");

        prompt.handleKey(key(KeyCode.UP));
        assertThat(prompt.input()).isEqualTo("/a");
        assertThat(prompt.error()).isEmpty();
        prompt.handleKey(key(KeyCode.UP));
        assertThat(prompt.input()).isEqualTo("/c");
        prompt.handleKey(key(KeyCode.UP));
        assertThat(prompt.input()).as("the oldest stays").isEqualTo("/c");
        prompt.handleKey(key(KeyCode.DOWN));
        assertThat(prompt.input()).isEqualTo("/a");
        prompt.handleKey(key(KeyCode.DOWN));
        assertThat(prompt.input()).as("back to the input").isEqualTo("/b");
        prompt.handleKey(key(KeyCode.DOWN));
        assertThat(prompt.input()).isEqualTo("/b");
        prompt.handleKey(key(KeyCode.UP));
        assertThat(prompt.input()).isEqualTo("/a");
    }

    @Test
    void anEditStartsOverFromTheEditedInput() {
        PathPrompt prompt = prompt("").withRecent(List.of("/a", "/b"));

        prompt.handleKey(key(KeyCode.UP));
        prompt.handleKey(key(KeyCode.UP));
        typed(prompt, "x");
        assertThat(prompt.input()).isEqualTo("/bx");
        prompt.handleKey(key(KeyCode.UP));
        assertThat(prompt.input()).isEqualTo("/a");
        prompt.handleKey(key(KeyCode.DOWN));
        assertThat(prompt.input()).isEqualTo("/bx");
    }

    @Test
    void upWithoutRecentPathsKeepsTheInput() {
        PathPrompt prompt = prompt("/x").withRecent(List.of("/x"));

        prompt.handleKey(key(KeyCode.UP));
        prompt.handleKey(key(KeyCode.DOWN));

        assertThat(prompt.input()).isEqualTo("/x");
        assertThat(render(prompt, 80, 8)).doesNotContain("recent");
    }

    @Test
    void ctrlDCyclesTheDirectory() {
        PathPrompt prompt = prompt("/x").withDirectory(DirectoryImpl.MMAP);
        assertThat(render(prompt, 80, 8)).contains("^D directory: MMap");

        prompt.handleKey(ctrl('d'));
        assertThat(prompt.directory()).isEqualTo(DirectoryImpl.NIO);
        assertThat(render(prompt, 80, 8)).contains("^D directory: NIO");
        prompt.handleKey(ctrl('d'));
        assertThat(prompt.directory()).isEqualTo(DirectoryImpl.FS);
        assertThat(render(prompt, 80, 8)).contains("^D directory: FS (auto)");
        assertThat(prompt.input()).isEqualTo("/x");
    }

    @Test
    void theHintFitsWithTheRecentPaths() {
        PathPrompt prompt = prompt("/x").withRecent(List.of("/a"));

        assertThat(render(prompt, 60, 8)).contains("↑↓ recent   Esc cancel");
    }

    @Test
    void thePopupFitsAWordWrappedError() {
        PathPrompt prompt = prompt("/x");
        // Word wrapping leaves gaps at line ends, so this needs more lines than its width alone
        // suggests.
        prompt.setError("abcdefghij ".repeat(12) + "LAST");

        String screen = render(prompt, 40, 30);

        assertThat(screen).contains("│LAST", "│Enter open");
    }

    @Test
    void ctrlNSwitchesTheReaderOffAndOn() {
        PathPrompt prompt = prompt("/data/idx");
        assertThat(prompt.mode()).isEqualTo(OpenMode.READER);
        assertThat(render(prompt, 70, 7)).contains("^N reader: on");

        prompt.handleKey(ctrl('n'));
        assertThat(prompt.mode()).isEqualTo(OpenMode.DIRECTORY_ONLY);
        assertThat(render(prompt, 70, 7)).contains("^N reader: off");
        prompt.handleKey(ctrl('n'));
        assertThat(prompt.mode()).isEqualTo(OpenMode.READER);
    }

    @Test
    void offersNoReaderOnlyWhenOpeningWithOne() {
        PathPrompt prompt = prompt("/data/idx");
        prompt.setErrorOfferingNoReader("Corrupt index");
        assertThat(prompt.error()).hasValue("Corrupt index\n^N and Enter open it without a reader.");

        prompt = prompt("/data/idx").withMode(OpenMode.DIRECTORY_ONLY);
        prompt.setErrorOfferingNoReader("Not readable");
        assertThat(prompt.error()).hasValue("Not readable");
    }

    @Test
    void showsTheCursorUnlessBusy() {
        PathPrompt prompt = prompt("/x");

        var buffer = TestScreens.buffer(
                frame -> {
                    prompt.render(frame, frame.area());
                    assertThat(frame.cursorPosition()).isPresent();
                },
                40,
                8);
        assertThat(TestScreens.text(buffer)).contains("/x");

        prompt.setBusy();
        TestScreens.buffer(
                frame -> {
                    prompt.render(frame, frame.area());
                    assertThat(frame.cursorPosition()).isEmpty();
                },
                40,
                8);
        assertThat(render(prompt, 40, 8)).contains("Opening…");
    }
}
