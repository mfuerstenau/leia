package io.github.mfuerstenau.leia.tui.theme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The icon files' format, and where they come from (ADR 0099). */
class IconTest {

    @TempDir
    Path dir;

    private static Icon.Cell cell(String character, Style style) {
        return new Icon.Cell(character, style);
    }

    private static Icon parse(String chars, String colours) throws ThemeParser.Invalid {
        return Icon.parse("i.chars", chars, "i.colours", colours);
    }

    @Test
    void readsAForegroundABackgroundAndNeither() throws Exception {
        Icon icon = parse("▄▀x\n", "#010203/#040506 red -\n");

        assertThat(icon.rows())
                .containsExactly(List.of(
                        cell("▄", Style.EMPTY.fg(Color.hex("#010203")).bg(Color.hex("#040506"))),
                        cell("▀", Style.EMPTY.red()),
                        cell("x", Style.EMPTY)));
        assertThat(parse("ab\n", "-/130 dark-gray/-\n").rows().getFirst())
                .containsExactly(
                        cell("a", Style.EMPTY.bg(Color.indexed(130))), cell("b", Style.EMPTY.fg(Color.DARK_GRAY)));
    }

    @Test
    void cutsTheColumnsThatAreBlankInEveryRowAtTheRight() throws Exception {
        Icon icon = parse("a  \n b \n", "red - -\n- red -\n");

        assertThat(icon.width()).isEqualTo(2);
        assertThat(icon.height()).isEqualTo(2);
        // A space with a background is drawn, so it stays.
        assertThat(parse("a \n", "- -/red\n").width()).isEqualTo(2);
    }

    @Test
    void aShortRowOfCharactersEndsInSpaces() throws Exception {
        // As an editor that strips trailing spaces leaves it.
        Icon icon = parse("a\nbc\n", "- -/red\n- -\n");

        assertThat(icon.rows().getFirst()).containsExactly(cell("a", Style.EMPTY), cell(" ", Style.EMPTY.onRed()));
    }

    @Test
    void aFirstLineNamesTheCharactersToColour() throws Exception {
        assertThat(Icon.charsOf("chars = gazelle\n- -\n")).hasValue("gazelle");
        assertThat(Icon.charsOf("- -\n")).isEmpty();
        assertThat(parse("ab\n", "chars=other\nred red\n").height()).isEqualTo(1);
    }

    @Test
    void refusesABrokenIcon() {
        assertThat(refusal("a\nb\n", "red\n"))
                .isEqualTo("i.colours: 1 rows of cells, but i.chars has 2 rows of characters");
        assertThat(refusal("abc\n", "red red\n"))
                .isEqualTo("i.colours:1: 2 cells, but row 1 of i.chars has 3 characters");
        assertThat(refusal("ab\na\n", "chars = x\nred red\nred\n"))
                .isEqualTo("i.colours:3: 1 cells, but the first row has 2");
        assertThat(refusal("a\n", "teal\n")).startsWith("i.colours:1: unknown colour 'teal'");
        assertThat(refusal("a\n", "red/\n")).startsWith("i.colours:1: unknown colour ''");
        assertThat(refusal("  \n", "- -\n")).isEqualTo("i.colours: the icon is empty");
    }

    private static String refusal(String chars, String colours) {
        return assertThatThrownBy(() -> parse(chars, colours))
                .isInstanceOf(ThemeParser.Invalid.class)
                .actual()
                .getMessage();
    }

    @Test
    void theUsersIconsComeFirstAndMayColourLeiasCharacters() throws Exception {
        Files.writeString(dir.resolve("own.chars"), "ab\n");
        Files.writeString(dir.resolve("own.colours"), "red blue\n");
        Files.writeString(dir.resolve("gazelle-red.colours"), "chars = gazelle\n" + "red ".repeat(38) + "\n");
        Icons icons = Icons.bundled().withUserDir(dir);

        assertThat(icons.find("t:1: ", "own").rows().getFirst())
                .containsExactly(cell("a", Style.EMPTY.red()), cell("b", Style.EMPTY.blue()));
        assertThat(Icons.bundled().find("t:1: ", "gazelle").height()).isEqualTo(30);
        // A colours file of the user's over leia's characters: its rows must still match them.
        assertThatThrownBy(() -> icons.find("t:1: ", "gazelle-red"))
                .isInstanceOf(ThemeParser.Invalid.class)
                .hasMessage(
                        "t:1: icon gazelle-red: gazelle-red.colours: 1 rows of cells, but gazelle.chars has 30 rows of characters");
    }

    @Test
    void bundlesTheHalfBlockGazelles() throws Exception {
        // The lower half in the foreground, the upper one in the background.
        List<Icon.Cell> row = Icons.bundled().find("", "gazelle").rows().get(1);

        assertThat(row.get(18))
                .isEqualTo(cell("▄", Style.EMPTY.fg(Color.hex("#523D38")).bg(Color.hex("#14070C"))));
        assertThat(row.get(17)).isEqualTo(cell("▄", Style.EMPTY.fg(Color.hex("#14070C"))));
        assertThat(row.get(35)).isEqualTo(cell("▀", Style.EMPTY.fg(Color.hex("#14070C"))));
        // nord's colours the same characters; mono draws in the terminal's own foreground.
        assertThat(Icons.bundled().find("", "gazelle-aurora").rows().get(1).get(18))
                .isEqualTo(cell("▄", Style.EMPTY.fg(Color.hex("#B48EAD")).bg(Color.hex("#BF616A"))));
        List<Icon.Cell> mono = Icons.bundled().find("", "gazelle-mono").rows().get(1);
        assertThat(mono.subList(17, 20))
                .containsExactly(cell("▄", Style.EMPTY), cell("█", Style.EMPTY), cell("█", Style.EMPTY));
    }

    @Test
    void refusesAMissingIconOrABadName() {
        Icons icons = Icons.bundled().withUserDir(dir);

        assertThatThrownBy(() -> icons.find("t:1: ", "zebra"))
                .isInstanceOf(ThemeParser.Invalid.class)
                .hasMessage("t:1: no icon file zebra.colours in " + dir + " or among leia's own");
        assertThatThrownBy(() -> Icons.bundled().find("t:1: ", "zebra"))
                .hasMessage("t:1: no icon file zebra.colours among leia's own");
        assertThatThrownBy(() -> icons.find("t:1: ", "../gazelle"))
                .hasMessage("t:1: no icon '../gazelle': its name is lower-case letters, digits and -");
    }
}
