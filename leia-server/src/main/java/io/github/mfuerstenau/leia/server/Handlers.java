package io.github.mfuerstenau.leia.server;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/** The handler of every method in {@link Methods}, which {@link LeiaBackend} looks up by {@link RpcMethod}. */
final class Handlers {

    private static final Logger LOG = Logger.getLogger(Handlers.class.getName());

    private final Map<RpcMethod<?, ?>, MethodHandler<?, ?>> byMethod;

    /**
     * @throws IllegalStateException if a method in {@link Methods} has no handler
     */
    @Inject
    Handlers(Set<MethodHandler<?, ?>> handlers) {
        byMethod = handlers.stream().collect(Collectors.toUnmodifiableMap(MethodHandler::method, Function.identity()));
        List<String> unhandled = Methods.ALL.stream()
                .filter(method -> !byMethod.containsKey(method))
                .map(RpcMethod::name)
                .toList();
        if (!unhandled.isEmpty()) {
            throw new IllegalStateException("No handler for " + unhandled);
        }
    }

    /** The handler of {@code method}, which every method in {@link Methods} has. */
    <P, R> MethodHandler<P, R> get(RpcMethod<P, R> method) {
        MethodHandler<?, ?> handler = byMethod.get(method);
        if (handler == null) {
            throw new IllegalArgumentException("No handler for " + method.name());
        }
        // Safe: the handler is keyed by its own method(), which equals method.
        @SuppressWarnings("unchecked")
        MethodHandler<P, R> typed = (MethodHandler<P, R>) handler;
        return typed;
    }

    /**
     * Runs {@code handler}.
     *
     * @throws RpcException the handler's own, or {@code INTERNAL_ERROR} for any other {@link
     *     RuntimeException}, which is logged
     */
    static <P, R> R run(MethodHandler<P, R> handler, P params) throws RpcException {
        try {
            return handler.handle(params);
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, e, () -> "Method " + handler.method().name() + " failed");
            throw new RpcException(ErrorCode.INTERNAL_ERROR, "Internal error: " + e, e);
        }
    }
}
