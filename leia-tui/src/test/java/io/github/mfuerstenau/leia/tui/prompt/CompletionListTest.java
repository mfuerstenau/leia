package io.github.mfuerstenau.leia.tui.prompt;

import static io.github.mfuerstenau.leia.tui.TestScreens.key;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.tui.event.KeyCode;
import io.github.mfuerstenau.leia.tui.TestScreens;
import io.github.mfuerstenau.leia.tui.widgets.Panels;
import io.github.mfuerstenau.leia.tui.widgets.Selection;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CompletionListTest {

    private static final String SEPARATOR = File.separator;

    @TempDir
    Path tmp;

    private String base() {
        return tmp + SEPARATOR;
    }

    private void directories(int count) throws IOException {
        for (int i = 1; i <= count; i++) {
            Files.createDirectory(tmp.resolve(String.format("d%02d", i)));
        }
    }

    private CompletionList list(String input) {
        PathCompletion.Completion completion =
                PathCompletion.completeWithFiles(input, null, name -> name.endsWith(".jar"));
        return new CompletionList(completion, null, name -> name.endsWith(".jar"));
    }

    private static String render(CompletionList list, int height) {
        return TestScreens.render(frame -> list.render(frame, new Rect(0, 0, 30, height)), 30, height);
    }

    @Test
    void movingClampsAtBothEnds() throws IOException {
        directories(25);
        CompletionList list = list(base() + "d");

        assertThat(list.move(key(KeyCode.UP))).isFalse();
        assertThat(list.chosen()).isEqualTo(base() + "d01" + SEPARATOR);
        assertThat(list.move(key(KeyCode.DOWN))).isTrue();
        assertThat(list.chosen()).isEqualTo(base() + "d02" + SEPARATOR);
        assertThat(list.move(key(KeyCode.PAGE_DOWN))).isTrue();
        assertThat(list.chosen()).isEqualTo(base() + "d" + (2 + Selection.PAGE + 1 - 1) + SEPARATOR);
        list.move(key(KeyCode.PAGE_DOWN));
        list.move(key(KeyCode.PAGE_DOWN));
        assertThat(list.move(key(KeyCode.DOWN))).isFalse();
        assertThat(list.chosen()).isEqualTo(base() + "d25" + SEPARATOR);
        list.move(key(KeyCode.PAGE_UP));
        assertThat(list.chosen()).isEqualTo(base() + "d15" + SEPARATOR);
    }

    @Test
    void otherKeysDoNotMoveIt() throws IOException {
        directories(5);
        CompletionList list = list(base() + "d");

        assertThat(list.move(key('j'))).isFalse();
        assertThat(list.move(key(KeyCode.END))).isFalse();
        assertThat(list.chosen()).isEqualTo(base() + "d01" + SEPARATOR);
    }

    @Test
    void theViewFollowsTheSelectionPastTheMaximumRows() throws IOException {
        directories(20);
        CompletionList list = list(base() + "d");

        assertThat(list.rows(100)).isEqualTo(CompletionList.MAX_ROWS);
        assertThat(list.rows(3)).isEqualTo(3);
        assertThat(list.scrolls(100)).isTrue();
        assertThat(render(list, 8)).contains("d01/", "d08/").doesNotContain("d09/");
        for (int i = 0; i < 9; i++) {
            list.move(key(KeyCode.DOWN));
        }
        assertThat(render(list, 8)).contains("d10/", "d03/").doesNotContain("d02/", "d11/");
        for (int i = 0; i < 9; i++) {
            list.move(key(KeyCode.UP));
        }
        assertThat(render(list, 8)).contains("d01/", "d08/");
        assertThat(list.position()).isEqualTo("1/20");
    }

    @Test
    void aDirectoryIsChosenWithItsSeparatorAndAFileWithout() throws IOException {
        Files.createDirectory(tmp.resolve("lib"));
        Files.writeString(tmp.resolve("lucene.jar"), "");
        CompletionList list = list(base() + "l");

        assertThat(list.chosen()).isEqualTo(base() + "lib" + SEPARATOR);
        list.move(key(KeyCode.DOWN));
        assertThat(list.chosen()).isEqualTo(base() + "lucene.jar");
    }

    @Test
    void narrowingKeepsTheSelectedNameAndClosesAtFewerThanTwo() throws IOException {
        directories(12);
        CompletionList list = list(base() + "d");
        for (int i = 0; i < 10; i++) {
            list.move(key(KeyCode.DOWN));
        }
        assertThat(list.chosen()).isEqualTo(base() + "d11" + SEPARATOR);

        assertThat(list.narrow(base() + "d1")).isTrue();
        assertThat(list.chosen()).isEqualTo(base() + "d11" + SEPARATOR);
        assertThat(list.narrow(base() + "d0")).as("d11 is gone").isTrue();
        assertThat(list.chosen()).isEqualTo(base() + "d01" + SEPARATOR);
        assertThat(list.narrow(base() + "d01")).as("one match").isFalse();
        assertThat(list.narrow(base() + "x")).as("no match").isFalse();
    }

    @Test
    void narrowingClosesWhenTheDirectoryPartChanges() throws IOException {
        directories(3);
        CompletionList list = list(base() + "d");

        assertThat(list.narrow(base())).isTrue();
        assertThat(list.narrow(tmp.toString())).isFalse();
        assertThat(list.narrow(base() + "d01" + SEPARATOR)).isFalse();
    }

    @Test
    void theSelectedRowIsHighlighted() throws IOException {
        directories(3);
        CompletionList list = list(base() + "d");
        list.move(key(KeyCode.DOWN));

        Buffer buffer = TestScreens.buffer(frame -> list.render(frame, new Rect(0, 0, 30, 3)), 30, 3);

        assertThat(buffer.get(0, 0).style()).isNotEqualTo(Panels.highlight(true));
        assertThat(buffer.get(0, 1).style()).isEqualTo(Panels.highlight(true));
        assertThat(buffer.get(29, 1).style()).as("the bar spans the row").isEqualTo(Panels.highlight(true));
        assertThat(buffer.get(0, 2).style()).isNotEqualTo(Panels.highlight(true));
    }
}
