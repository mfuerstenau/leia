package io.github.mfuerstenau.leia.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TermsToolTest {

    @Test
    void matchesATextTermByItsText() {
        TermsTool.Prefix prefix = new TermsTool.Prefix("ab");

        assertThat(prefix.matches("abé", false)).isTrue();
        assertThat(prefix.matches("ac", false)).isFalse();
        assertThat(prefix.matches("6162", false)).isFalse();
    }

    @Test
    void matchesABinaryTermByThePrefixsBytes() {
        // ab, then 0x80, which isn't UTF-8: in term order before abé, and still in the prefix.
        assertThat(new TermsTool.Prefix("ab").matches("616280", true)).isTrue();
        assertThat(new TermsTool.Prefix("ab").matches("616380", true)).isFalse();
        assertThat(new TermsTool.Prefix("é").matches("c3a9ff", true)).isTrue();
        assertThat(new TermsTool.Prefix("é").matches("c3", true)).isFalse();
    }
}
