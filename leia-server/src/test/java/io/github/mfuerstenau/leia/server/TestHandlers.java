package io.github.mfuerstenau.leia.server;

import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** {@link Handlers} for tests, with handlers of their own. */
final class TestHandlers {

    private TestHandlers() {}

    /** The work of a test handler. */
    @FunctionalInterface
    interface Body<P, R> {
        R handle(P params) throws RpcException;
    }

    /** {@code handlers}, and for every other method one that must not be called. */
    static Handlers with(MethodHandler<?, ?>... handlers) {
        Set<MethodHandler<?, ?>> all = new HashSet<>(List.of(handlers));
        Set<RpcMethod<?, ?>> handled = new HashSet<>();
        all.forEach(handler -> handled.add(handler.method()));
        Methods.ALL.stream().filter(method -> !handled.contains(method)).forEach(method -> all.add(unused(method)));
        return new Handlers(all);
    }

    /** A handler of {@code method} that runs {@code body}. */
    static <P, R> MethodHandler<P, R> handler(RpcMethod<P, R> method, Body<P, R> body) {
        return new MethodHandler<>() {
            @Override
            public RpcMethod<P, R> method() {
                return method;
            }

            @Override
            public R handle(P params) throws RpcException {
                return body.handle(params);
            }
        };
    }

    private static <P, R> MethodHandler<P, R> unused(RpcMethod<P, R> method) {
        return handler(method, params -> {
            throw new AssertionError(method.name() + " must not be called");
        });
    }
}
