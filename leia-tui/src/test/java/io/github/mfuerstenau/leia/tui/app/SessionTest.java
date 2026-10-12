package io.github.mfuerstenau.leia.tui.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.server.RpcException;
import org.junit.jupiter.api.Test;

class SessionTest {

    @Test
    void aMissingCodecHintsAtJar() {
        RpcException error = Session.withJarHint(new RpcException(
                ErrorCode.MISSING_CODEC, "Missing codec: the index needs the codec 'X', which isn't loaded"));

        assertThat(Session.describe(error))
                .isEqualTo(
                        "Missing codec: the index needs the codec 'X', which isn't loaded; add a jar that has it with "
                                + "X, or start leia with --jar (MISSING_CODEC -32014)");
    }

    @Test
    void otherErrorsGetNoHint() {
        RpcException error = Session.withJarHint(new RpcException(ErrorCode.CORRUPT_INDEX, "Corrupt index: x"));

        assertThat(Session.describe(error)).isEqualTo("Corrupt index: x (CORRUPT_INDEX -32003)");
    }
}
