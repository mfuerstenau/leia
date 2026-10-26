package io.github.mfuerstenau.leia.runtime.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
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

    @Test
    void anUnreadableCommitNamesAMissingFileByItsName() {
        assertThat(Failures.unreadableCommit(new NoSuchFileException("/a/b/_0.si")))
                .isEqualTo("Corrupt index, missing file: _0.si");
        assertThat(Failures.unreadableCommit(new IOException("corrupt", new NoSuchFileException("C:\\a\\_1.fnm"))))
                .isEqualTo("Corrupt index, missing file: _1.fnm");
        assertThat(Failures.unreadableCommit(new FileNotFoundException("/a/b/_0.si (No such file or directory)")))
                .isEqualTo("Corrupt index, missing file: _0.si");
    }

    @Test
    void anUnreadableCommitKeepsAnotherMessage() {
        assertThat(Failures.unreadableCommit(new IOException("checksum failed")))
                .isEqualTo("checksum failed");
    }
}
