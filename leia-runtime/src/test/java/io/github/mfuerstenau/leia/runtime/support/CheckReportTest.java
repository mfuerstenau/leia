package io.github.mfuerstenau.leia.runtime.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CheckReportTest {

    private final List<String> lines = new ArrayList<>();
    private final List<Long> progress = new ArrayList<>();
    private final CheckReport report = new CheckReport(lines::add, new Cancellation(progress::add));

    @Test
    void linesAreSplitAcrossWrites() {
        write("first li");
        write("ne\r\nsecond\n");
        report.write('x');
        report.write('\n');

        assertThat(lines).containsExactly("first line", "second", "x");
        assertThat(progress).containsExactly(1L, 2L, 3L);
    }

    @Test
    void endPassesOnTheRest() {
        write("done\nrest\r");
        report.end();

        assertThat(lines).containsExactly("done", "rest");
        assertThat(progress).containsExactly(1L);
    }

    @Test
    void endWithoutRestPassesNothing() {
        write("done\n");
        report.end();

        assertThat(lines).containsExactly("done");
    }

    @Test
    void writeHonoursTheOffset() {
        byte[] bytes = "skip|a\nb|skip".getBytes(StandardCharsets.UTF_8);
        report.write(bytes, 5, 3);
        report.end();

        assertThat(lines).containsExactly("a", "b");
    }

    private void write(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        report.write(bytes, 0, bytes.length);
    }
}
