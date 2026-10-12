package io.github.mfuerstenau.leia.tui.prompt;

import static io.github.mfuerstenau.leia.tui.TestScreens.ctrl;
import static io.github.mfuerstenau.leia.tui.TestScreens.key;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import io.github.mfuerstenau.leia.tui.TestScreens;
import io.github.mfuerstenau.leia.tui.prompt.ExportPrompt.ExportRequest;
import io.github.mfuerstenau.leia.tui.prompt.ExportPrompt.ExportTarget;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The document tests of {@code export.rs}, ported. */
class ExportPromptTest {

    @TempDir
    Path dir;

    private static void typed(ExportPrompt prompt, String text) {
        text.codePoints().forEach(c -> prompt.handleKey(KeyEvent.ofChar(c)));
    }

    private static void clear(ExportPrompt prompt) {
        prompt.handleKey(ctrl('u'));
    }

    private static String render(ExportPrompt prompt) {
        return TestScreens.render(frame -> prompt.render(frame, frame.area()), 100, 10);
    }

    @Test
    void exportsADocumentToAFileOrTheClipboard() {
        ExportPrompt prompt = ExportPrompt.document(7, List.of(), Optional.empty(), Optional.of(dir));
        assertThat(prompt.input()).isEqualTo("doc-7.json");
        String screen = render(prompt);
        assertThat(screen).contains("Export document 7 as JSON").contains("Tab indexed terms: on");

        assertThat(prompt.handleKey(key(KeyCode.ENTER)))
                .isEqualTo(new ExportPrompt.Submit(new ExportRequest.Document(
                        7, true, new ExportTarget.File(dir.resolve("doc-7.json").toString(), false))));

        prompt.handleKey(key(KeyCode.TAB));
        assertThat(render(prompt)).contains("Tab indexed terms: off");
        assertThat(prompt.handleKey(ctrl('y')))
                .isEqualTo(new ExportPrompt.Submit(new ExportRequest.Document(7, false, new ExportTarget.Clipboard())));
    }

    @Test
    void resolvesTheHomeDirectoryAndRelativePaths() throws IOException {
        Path home = Files.createDirectories(dir.resolve("home"));
        ExportPrompt prompt = ExportPrompt.document(1, List.of(), Optional.of(home), Optional.of(dir));
        clear(prompt);
        typed(prompt, "~/d.json");

        assertThat(prompt.handleKey(key(KeyCode.ENTER)))
                .isEqualTo(new ExportPrompt.Submit(new ExportRequest.Document(
                        1, true, new ExportTarget.File(home.resolve("d.json").toString(), false))));
        clear(prompt);
        typed(prompt, "out/d.json");
        assertThat(prompt.handleKey(key(KeyCode.ENTER))).isEqualTo(new ExportPrompt.Nothing());
        assertThat(prompt.error()).contains("No such directory: " + dir.resolve("out"));
    }

    @Test
    void asksBeforeOverwritingAFile() throws IOException {
        Files.writeString(dir.resolve("d.json"), "old");
        ExportPrompt prompt = ExportPrompt.document(1, List.of(), Optional.empty(), Optional.of(dir));
        clear(prompt);
        typed(prompt, "d.json");

        assertThat(prompt.handleKey(key(KeyCode.ENTER))).isEqualTo(new ExportPrompt.Nothing());
        String path = dir.resolve("d.json").toString();
        assertThat(prompt.error()).contains("File exists: " + path + ". ⏎ again overwrites it.");
        assertThat(prompt.handleKey(key(KeyCode.ENTER)))
                .isEqualTo(new ExportPrompt.Submit(
                        new ExportRequest.Document(1, true, new ExportTarget.File(path, true))));

        // Editing the path asks again.
        typed(prompt, "x");
        prompt.handleKey(key(KeyCode.BACKSPACE));
        assertThat(prompt.error()).isEmpty();
        assertThat(prompt.handleKey(key(KeyCode.ENTER))).isEqualTo(new ExportPrompt.Nothing());
    }

    @Test
    void doesNotWriteADocumentIntoAnIndexDirectory() {
        ExportPrompt prompt = ExportPrompt.document(1, List.of(dir.toString()), Optional.empty(), Optional.of(dir));

        assertThat(prompt.handleKey(key(KeyCode.ENTER))).isEqualTo(new ExportPrompt.Nothing());
        assertThat(prompt.error())
                .contains("Not writing into the index directory " + dir + ": leia never changes an index");
        assertThat(render(prompt)).contains("Not writing into the index directory");
    }

    @Test
    void ignoresKeysWhileBusyAndShowsErrors() {
        ExportPrompt prompt = ExportPrompt.document(1, List.of(), Optional.empty(), Optional.of(dir));
        prompt.setBusy();
        assertThat(prompt.handleKey(key(KeyCode.ESCAPE))).isEqualTo(new ExportPrompt.Nothing());
        assertThat(render(prompt)).contains("Exporting…");

        prompt.setError("Cannot write doc-1.json: disk full");
        assertThat(render(prompt)).contains("Cannot write doc-1.json: disk full");
        assertThat(prompt.handleKey(key(KeyCode.ESCAPE))).isEqualTo(new ExportPrompt.Cancel());
    }
}
