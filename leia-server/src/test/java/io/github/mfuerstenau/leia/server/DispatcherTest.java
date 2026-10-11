package io.github.mfuerstenau.leia.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.ProtocolJson;
import io.github.mfuerstenau.leia.protocol.Response;
import io.github.mfuerstenau.leia.protocol.RpcError;
import io.github.mfuerstenau.leia.protocol.version.JavaRuntime;
import io.github.mfuerstenau.leia.protocol.version.VersionParams;
import io.github.mfuerstenau.leia.protocol.version.VersionResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DispatcherTest {

    private static final VersionResult VERSION = new VersionResult(
            "leia-server", "1.0", "abc1234", "2026-10-05T22:00:00Z", new JavaRuntime("25", "v", "/h"));

    private final Dispatcher dispatcher = dispatcherWith(params -> VERSION);

    @Test
    void answersRequestFixture() throws IOException {
        Response<?> response = dispatcher.dispatch(fixture("version.request.json"));

        assertThat(response).isEqualTo(Response.success(1, VERSION));
    }

    @Test
    void reportsSyntaxErrorAsParseErrorWithoutId() {
        assertError(dispatcher.dispatch("{\"jsonrpc\":\"2.0\",\"id\":1"), null, ErrorCode.PARSE_ERROR);
    }

    @Test
    void reportsTrailingContentAsParseError() {
        assertError(
                dispatcher.dispatch("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"version\",\"params\":{}} {}"),
                null,
                ErrorCode.PARSE_ERROR);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            # request                                                                   | expected id
            [1, 2]                                                                      |
            {"jsonrpc":"2.0","id":2,"params":{}}                                        | 2
            {"jsonrpc":"2.0","id":2,"method":null,"params":{}}                          | 2
            {"jsonrpc":"2.0","id":2,"method":7,"params":{}}                             | 2
            {"jsonrpc":"1.0","id":2,"method":"version","params":{}}                     | 2
            {"id":2,"method":"version","params":{}}                                     | 2
            {"jsonrpc":"2.0","method":"version","params":{}}                            |
            {"jsonrpc":"2.0","id":"2","method":"version","params":{}}                   |
            {"jsonrpc":"2.0","id":-2,"method":"version","params":{}}                    |
            {"jsonrpc":"2.0","id":2.5,"method":"version","params":{}}                  |
            {"jsonrpc":"2.0","id":2,"method":"version","params":{},"extra":true}       | 2
            """)
    void reportsMalformedEnvelopeAsInvalidRequest(String request, Long expectedId) {
        assertError(dispatcher.dispatch(request), expectedId, ErrorCode.INVALID_REQUEST);
    }

    @Test
    void reportsUnknownMethod() {
        Response<?> response =
                dispatcher.dispatch("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"frobnicate\",\"params\":{}}");

        assertThat(response)
                .isEqualTo(Response.failure(
                        Optional.of(3L), new RpcError(ErrorCode.METHOD_NOT_FOUND, "Method not found: frobnicate")));
    }

    @Test
    void reportsEnvelopeErrorBeforeUnknownMethod() {
        assertError(
                dispatcher.dispatch("{\"jsonrpc\":\"1.0\",\"id\":3,\"method\":\"frobnicate\",\"params\":{}}"),
                3L,
                ErrorCode.INVALID_REQUEST);
    }

    @Test
    void reportsUnknownParamAsInvalidParams() {
        Response<?> response = dispatcher.dispatch(
                "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"version\",\"params\":{\"verbose\":true}}");

        assertThat(response)
                .isEqualTo(Response.failure(
                        Optional.of(4L),
                        new RpcError(ErrorCode.INVALID_PARAMS, "Invalid params: unknown property 'verbose'")));
    }

    @Test
    void reportsMissingParamsAsInvalidParams() {
        assertError(
                dispatcher.dispatch("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"version\"}"),
                4L,
                ErrorCode.INVALID_PARAMS);
    }

    @Test
    void reportsParamsOfWrongShapeAsInvalidParams() {
        assertError(
                dispatcher.dispatch("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"version\",\"params\":[]}"),
                4L,
                ErrorCode.INVALID_PARAMS);
    }

    @Test
    void passesOnTheHandlersErrorCode() {
        Dispatcher failing = dispatcherWith(params -> {
            throw new RpcException(ErrorCode.NO_INDEX_OPEN, "No index is open");
        });

        Response<?> response = failing.dispatch(versionRequest(5));

        assertThat(response)
                .isEqualTo(
                        Response.failure(Optional.of(5L), new RpcError(ErrorCode.NO_INDEX_OPEN, "No index is open")));
    }

    @Test
    void reportsUnexpectedHandlerFailureAsInternalError() {
        Dispatcher failing = dispatcherWith(params -> {
            throw new IllegalStateException("boom");
        });

        Response<?> response = failing.dispatch(versionRequest(6));

        assertError(response, 6L, ErrorCode.INTERNAL_ERROR);
        assertThat(response.error().orElseThrow().message()).contains("boom");
    }

    @Test
    void rpcErrorFixturesMatchWhatTheServerAnswers() throws IOException {
        assertMatchesFixture("{\"jsonrpc\":\"2.0\",\"id\":1", "rpc.error-parse-error.json");
        assertMatchesFixture("{\"jsonrpc\":\"2.0\",\"id\":2,\"params\":{}}", "rpc.error-invalid-request.json");
        assertMatchesFixture(
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"frobnicate\",\"params\":{}}",
                "rpc.error-method-not-found.json");
        assertMatchesFixture(
                "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"version\",\"params\":{\"verbose\":true}}",
                "rpc.error-invalid-params.json");
    }

    /** Fixture messages are examples; the code and the id must match. */
    private void assertMatchesFixture(String request, String fixture) throws IOException {
        Response<?> expected =
                ProtocolJson.mapper().readValue(fixture(fixture), ProtocolJson.responseType(Object.class));
        assertError(
                dispatcher.dispatch(request),
                expected.id().orElse(null),
                expected.error().orElseThrow().code());
    }

    private static void assertError(Response<?> response, Long expectedId, ErrorCode expectedCode) {
        assertThat(response.result()).isEmpty();
        assertThat(response.id()).isEqualTo(Optional.ofNullable(expectedId));
        assertThat(response.error())
                .hasValueSatisfying(error -> assertThat(error.code()).isEqualTo(expectedCode));
    }

    private static String versionRequest(long id) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"version\",\"params\":{}}";
    }

    private static String fixture(String name) throws IOException {
        return Files.readString(Path.of(System.getProperty("leia.protocol.dir"))
                .resolve("fixtures")
                .resolve(name));
    }

    /** A dispatcher whose {@code version} runs {@code body}; the other methods must not be called. */
    private static Dispatcher dispatcherWith(TestHandlers.Body<VersionParams, VersionResult> body) {
        return new Dispatcher(TestHandlers.with(TestHandlers.handler(Methods.VERSION, body)));
    }
}
