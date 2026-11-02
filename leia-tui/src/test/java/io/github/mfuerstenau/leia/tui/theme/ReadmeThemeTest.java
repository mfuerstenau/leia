package io.github.mfuerstenau.leia.tui.theme;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The example theme in both READMEs reads, and is the same in both. */
class ReadmeThemeTest {

    private static final Pattern EXAMPLE = Pattern.compile("```\\n(# harbor:.*?)```", Pattern.DOTALL);

    @ParameterizedTest
    @ValueSource(strings = {"leia.test.doc.readme", "leia.test.doc.distReadme"})
    void theExampleThemeParses(String property) throws Exception {
        String text = example(property);

        Palette palette = ThemeParser.parse("README", text);

        assertThat(palette.heading().addModifiers()).contains(dev.tamboui.style.Modifier.BOLD);
        assertThat(palette.logo()).isEqualTo(Icons.bundled().find("", "gazelle"));
    }

    @Test
    void bothReadmesHaveTheSameExample() throws Exception {
        assertThat(example("leia.test.doc.readme")).isEqualTo(example("leia.test.doc.distReadme"));
    }

    private static String example(String property) throws IOException {
        Path file = Path.of(System.getProperty(property));
        Matcher matcher = EXAMPLE.matcher(Files.readString(file));
        assertThat(matcher.find())
                .as("a ``` block starting with '# harbor:' in %s", file)
                .isTrue();
        return matcher.group(1);
    }
}
