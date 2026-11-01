package io.github.mfuerstenau.leia.tui.theme;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The screens take every colour and modifier from {@link Palette}'s slots (ADR 0121), so that a theme can
 * restyle all of them: only this package builds a {@code Style} of its own.
 */
class SlotsOnlyTest {

    private static final Pattern FORBIDDEN = Pattern.compile("\\bColor\\.|\\.(?:fg|bg)\\(|\\.(?:bold|dim|italic"
            + "|underlined|reversed|crossedOut|blink)\\(\\)|\\bModifier\\.");

    @Test
    void noScreenAppliesAColourOrModifierOfItsOwn() throws IOException {
        Path main = Path.of("src/main/java");
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(main)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                if (main.relativize(file).toString().contains("/tui/theme/")) {
                    continue;
                }
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (!line.stripLeading().startsWith("*")
                            && !line.stripLeading().startsWith("//")
                            && FORBIDDEN.matcher(line).find()) {
                        found.add(file + ":" + (i + 1) + ": " + line.strip());
                    }
                }
            }
        }

        assertThat(found)
                .as("a style of its own: take it from Palette.current() (ADR 0121)")
                .isEmpty();
    }
}
