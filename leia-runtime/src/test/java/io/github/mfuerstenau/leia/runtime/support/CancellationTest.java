package io.github.mfuerstenau.leia.runtime.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

class CancellationTest {

    @Test
    void linesAreCountedForProgress() {
        List<Long> seen = new ArrayList<>();
        Cancellation cancellation = new Cancellation(seen::add);

        cancellation.check();
        cancellation.line();
        cancellation.line();
        cancellation.check();

        assertThat(seen).containsExactly(0L, 1L, 2L, 2L);
    }

    @Test
    void whatProgressThrewIsThrownAgain() {
        CancellationException stop = new CancellationException("stop");
        List<Long> seen = new ArrayList<>();
        Cancellation cancellation = new Cancellation(lines -> {
            seen.add(lines);
            if (lines == 1) {
                throw stop;
            }
        });

        cancellation.rethrow();
        assertThatThrownBy(cancellation::line).isSameAs(stop);
        assertThatThrownBy(cancellation::rethrow).isSameAs(stop);
        assertThatThrownBy(cancellation::check).isSameAs(stop);
        assertThatThrownBy(cancellation::line).isSameAs(stop);

        assertThat(seen).containsExactly(1L);
    }
}
