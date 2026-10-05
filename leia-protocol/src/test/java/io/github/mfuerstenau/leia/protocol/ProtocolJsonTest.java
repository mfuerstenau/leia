package io.github.mfuerstenau.leia.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mfuerstenau.leia.protocol.version.JavaRuntime;
import io.github.mfuerstenau.leia.protocol.version.VersionParams;
import io.github.mfuerstenau.leia.protocol.version.VersionResult;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;

/** The strictness rules of the wire format (ADR 0001, ADR 0003). */
class ProtocolJsonTest {

    private static final JsonMapper MAPPER = ProtocolJson.mapper();
    private static final JavaType VERSION_REQUEST = ProtocolJson.requestType(Methods.VERSION);
    private static final JavaType VERSION_RESPONSE = ProtocolJson.responseType(Methods.VERSION);

    @Test
    void writesRequestInDeclarationOrder() {
        String json = MAPPER.writeValueAsString(Request.of(1, Methods.VERSION, new VersionParams()));

        assertThat(json).isEqualTo("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"version\",\"params\":{}}");
    }

    @Test
    void writesNullIdAndOmitsEmptyResult() {
        String json = MAPPER.writeValueAsString(
                Response.failure(Optional.empty(), new RpcError(ErrorCode.PARSE_ERROR, "Parse error")));

        assertThat(json)
                .isEqualTo("{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32700,\"message\":\"Parse error\"}}");
    }

    @Test
    void readsSuccessResponse() {
        Response<?> response = MAPPER.readValue("""
                {"jsonrpc":"2.0","id":1,"result":{"name":"leia-server","version":"1","commit":"abc",
                 "commitTime":"t","java":{"version":"25","vendor":"v","home":"/h"}}}
                """, VERSION_RESPONSE);

        assertThat(response)
                .isEqualTo(Response.success(
                        1, new VersionResult("leia-server", "1", "abc", "t", new JavaRuntime("25", "v", "/h"))));
    }

    @Test
    void rejectsUnknownProperty() {
        assertRejected("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"version\",\"params\":{\"verbose\":true}}");
    }

    @Test
    void rejectsMissingRequiredProperty() {
        assertRejected("{\"jsonrpc\":\"2.0\",\"method\":\"version\",\"params\":{}}");
    }

    @Test
    void rejectsNullRequiredProperty() {
        assertRejected("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":null,\"params\":{}}");
    }

    @Test
    void rejectsOtherJsonRpcVersion() {
        assertRejected("{\"jsonrpc\":\"1.0\",\"id\":1,\"method\":\"version\",\"params\":{}}");
    }

    @Test
    void rejectsTrailingTokens() {
        assertRejected("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"version\",\"params\":{}} {}");
    }

    @Test
    void rejectsResponseWithBothResultAndError() {
        assertThatThrownBy(() -> MAPPER.readValue("""
                        {"jsonrpc":"2.0","id":1,"result":{"name":"n","version":"1","commit":"c","commitTime":"t",
                         "java":{"version":"25","vendor":"v","home":"/h"}},
                         "error":{"code":-32603,"message":"m"}}
                        """, VERSION_RESPONSE)).isInstanceOf(JacksonException.class);
    }

    @Test
    void rejectsResponseWithNeitherResultNorError() {
        assertThatThrownBy(() -> MAPPER.readValue("{\"jsonrpc\":\"2.0\",\"id\":1}", VERSION_RESPONSE))
                .isInstanceOf(JacksonException.class);
    }

    @Test
    void rejectsUnknownErrorCode() {
        assertThatThrownBy(() -> MAPPER.readValue(
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-31999,\"message\":\"m\"}}",
                        VERSION_RESPONSE))
                .isInstanceOf(JacksonException.class);
    }

    private static void assertRejected(String request) {
        assertThatThrownBy(() -> MAPPER.readValue(request, VERSION_REQUEST)).isInstanceOf(JacksonException.class);
    }
}
