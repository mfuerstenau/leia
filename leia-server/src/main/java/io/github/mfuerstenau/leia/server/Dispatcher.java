package io.github.mfuerstenau.leia.server;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.ProtocolJson;
import io.github.mfuerstenau.leia.protocol.Request;
import io.github.mfuerstenau.leia.protocol.Response;
import io.github.mfuerstenau.leia.protocol.RpcError;
import jakarta.inject.Inject;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns one request line into one response.
 *
 * <ol>
 *   <li>Scan the line leniently for syntax and the id: {@code PARSE_ERROR} with a {@code null} id.
 *   <li>Read the envelope strictly, without params: {@code INVALID_REQUEST}.
 *   <li>Look up the handler: {@code METHOD_NOT_FOUND}.
 *   <li>Read the whole request with the method's params type: {@code INVALID_PARAMS} for errors
 *       inside {@code params}, {@code INVALID_REQUEST} otherwise.
 *   <li>Run the handler: its {@link RpcException}'s code, or {@code INTERNAL_ERROR} for anything
 *       unexpected.
 * </ol>
 */
final class Dispatcher {

    private static final JsonMapper MAPPER = ProtocolJson.mapper();

    private final Handlers handlers;

    @Inject
    Dispatcher(Handlers handlers) {
        this.handlers = handlers;
    }

    Response<?> dispatch(String line) {
        Optional<Long> scannedId;
        try {
            scannedId = RequestScanner.idOf(line);
        } catch (StreamReadException e) {
            return failure(Optional.empty(), ErrorCode.PARSE_ERROR, "Parse error: " + e.getOriginalMessage());
        }

        RequestEnvelope envelope;
        try {
            envelope = MAPPER.readValue(line, RequestEnvelope.class);
        } catch (JacksonException e) {
            return failure(scannedId, ErrorCode.INVALID_REQUEST, "Invalid request: " + describe(e));
        }

        Optional<MethodHandler<?, ?>> handler = handlers.named(envelope.method());
        if (handler.isEmpty()) {
            return failure(
                    Optional.of(envelope.id()), ErrorCode.METHOD_NOT_FOUND, "Method not found: " + envelope.method());
        }
        return invoke(handler.get(), line, envelope.id());
    }

    private static <P, R> Response<?> invoke(MethodHandler<P, R> handler, String line, long id) {
        Request<P> request;
        try {
            request = MAPPER.readValue(line, ProtocolJson.requestType(handler.method()));
        } catch (JacksonException e) {
            return isInParams(e)
                    ? failure(Optional.of(id), ErrorCode.INVALID_PARAMS, "Invalid params: " + describe(e))
                    : failure(Optional.of(id), ErrorCode.INVALID_REQUEST, "Invalid request: " + describe(e));
        }

        try {
            return Response.success(id, Handlers.run(handler, request.params()));
        } catch (RpcException e) {
            return failure(Optional.of(id), e.code(), e.getMessage());
        }
    }

    private static boolean isInParams(JacksonException e) {
        return !e.getPath().isEmpty() && "params".equals(e.getPath().getFirst().getPropertyName());
    }

    private static String describe(JacksonException e) {
        if (e instanceof UnrecognizedPropertyException unrecognized) {
            return "unknown property '" + unrecognized.getPropertyName() + "'";
        }
        return e.getOriginalMessage();
    }

    private static Response<?> failure(Optional<Long> id, ErrorCode code, String message) {
        return Response.failure(id, new RpcError(code, message));
    }
}
