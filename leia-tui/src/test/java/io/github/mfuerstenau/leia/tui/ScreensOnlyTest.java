package io.github.mfuerstenau.leia.tui;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * leia-tui is the screens (ADR 0126): what any frontend needs that isn't drawing or keys is in
 * leia-client, so that another UI uses it as it is. No class of the TUI downloads, hashes, writes
 * JSON, finds the platform's folders or starts a thread of its own for the runtimes' install.
 */
class ScreensOnlyTest {

    /** What belongs to leia-client, and the classes of the TUI that may use it anyway. */
    private static final List<Rule> RULES = List.of(
            new Rule(Pattern.compile("^import java\\.net\\.http\\."), "a download: leia-client's runtimes", Set.of()),
            new Rule(
                    Pattern.compile("^import java\\.security\\.(MessageDigest|DigestInputStream)"),
                    "a hash: leia-client's runtimes",
                    Set.of()),
            new Rule(
                    Pattern.compile("^import tools\\.jackson\\.databind\\.(ObjectWriter|json\\.JsonMapper)"),
                    "JSON: leia-client's export",
                    // The value viewer reads and pretty-prints the JSON it shows (ADR 0034 §10).
                    Set.of("documents/Viewer.java")),
            new Rule(
                    Pattern.compile("System\\.getenv\\(\"XDG_|\"user\\.home\""),
                    "the platform's folders: leia-client's Platform",
                    Set.of()),
            new Rule(
                    Pattern.compile("\"leia-runtime-(install|plan)\""),
                    "the install's threads: leia-client's InstallJob and RuntimeService",
                    Set.of()));

    private record Rule(Pattern pattern, String what, Set<String> allowed) {}

    @Test
    void theTuiLeavesWhatIsntDrawingOrKeysToTheClient() throws IOException {
        Path main = Path.of("src/main/java/io/github/mfuerstenau/leia/tui");
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(main)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String relative = main.relativize(file).toString().replace('\\', '/');
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i).strip();
                    if (line.startsWith("*") || line.startsWith("//")) {
                        continue;
                    }
                    for (Rule rule : RULES) {
                        Matcher matcher = rule.pattern().matcher(line);
                        if (matcher.find() && !rule.allowed().contains(relative)) {
                            found.add(relative + ":" + (i + 1) + ": " + rule.what() + ": " + line);
                        }
                    }
                }
            }
        }

        assertThat(found).as("non-UI logic in the TUI (ADR 0126)").isEmpty();
    }
}
