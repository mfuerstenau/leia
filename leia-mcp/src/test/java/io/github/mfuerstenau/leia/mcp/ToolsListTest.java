package io.github.mfuerstenau.leia.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mfuerstenau.leia.mcp.tools.Tools;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.core.util.Separators;

/**
 * The answer to {@code tools/list}, pinned by {@code tools.json} (ADR 0100 §2): a change to a tool's
 * records shows here. On a mismatch, the answer is in {@code build/tools.json}.
 */
class ToolsListTest {

    @Test
    void listsTheToolsAsToolsJsonHasThem() throws Exception {
        // ": " and \n on every platform, as the file has them.
        DefaultPrettyPrinter pretty = new DefaultPrettyPrinter(
                        Separators.createDefaultInstance().withObjectNameValueSpacing(Separators.Spacing.AFTER))
                .withObjectIndenter(new DefaultIndenter("  ", "\n"))
                .withArrayIndenter(new DefaultIndenter("  ", "\n"));
        String actual = Messages.MAPPER.writer().with(pretty).writeValueAsString(Messages.toolsList(Tools.ALL)) + "\n";
        String expected;
        try (InputStream in = ToolsListTest.class.getResourceAsStream("/tools.json")) {
            expected = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (!actual.equals(expected)) {
            Files.writeString(Path.of("build/tools.json"), actual);
        }

        assertThat(actual).isEqualTo(expected);
    }
}
