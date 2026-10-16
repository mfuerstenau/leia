package io.github.mfuerstenau.leia.runtime.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class FailuresTest {

    @Test
    void messageFallsBackToTheClass() {
        assertThat(Failures.message(new IOException("broken"))).isEqualTo("broken");
        assertThat(Failures.message(new IllegalStateException())).isEqualTo("IllegalStateException");
    }

    @Test
    void describeNamesTheCauses() {
        Throwable t = new IOException("outer", new IllegalArgumentException("middle", new NullPointerException()));

        assertThat(Failures.describe(t))
                .isEqualTo("outer; caused by IllegalArgumentException: middle; caused by NullPointerException:"
                        + " NullPointerException");
    }

    @Test
    void describeNamesEightCausesAtMost() {
        Throwable t = new IOException("0");
        for (int i = 1; i <= 10; i++) {
            t = new IOException(Integer.toString(i), t);
        }

        assertThat(Failures.describe(t))
                .startsWith("10; caused by IOException: 9")
                .endsWith("caused by IOException: 2");
    }
}
