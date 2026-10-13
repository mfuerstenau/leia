package io.github.mfuerstenau.leia.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.version.VersionParams;
import io.github.mfuerstenau.leia.protocol.version.VersionResult;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HandlersTest {

    @Test
    void rejectsMissingHandler() {
        assertThatThrownBy(() -> new Handlers(Set.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("version");
    }

    @Test
    void findsTheHandlerByMethod() {
        MethodHandler<VersionParams, VersionResult> version = TestHandlers.handler(Methods.VERSION, params -> null);
        Handlers handlers = TestHandlers.with(version);

        assertThat(handlers.get(Methods.VERSION)).isSameAs(version);
    }

    @Test
    void passesOnTheHandlersRpcException() {
        RpcException failure = new RpcException(ErrorCode.NO_INDEX_OPEN, "No index is open");
        MethodHandler<VersionParams, VersionResult> failing = TestHandlers.handler(Methods.VERSION, params -> {
            throw failure;
        });

        assertThatThrownBy(() -> Handlers.run(failing, new VersionParams())).isSameAs(failure);
    }

    @Test
    void turnsAnyOtherRuntimeExceptionIntoAnInternalError() {
        IllegalStateException boom = new IllegalStateException("boom");
        MethodHandler<VersionParams, VersionResult> failing = TestHandlers.handler(Methods.VERSION, params -> {
            throw boom;
        });

        assertThatThrownBy(() -> Handlers.run(failing, new VersionParams()))
                .isInstanceOfSatisfying(
                        RpcException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INTERNAL_ERROR))
                .hasMessageContaining("boom")
                .hasCause(boom);
    }
}
