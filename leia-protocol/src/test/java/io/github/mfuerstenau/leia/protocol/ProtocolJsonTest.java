package io.github.mfuerstenau.leia.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mfuerstenau.leia.protocol.index.IndexOptions;
import io.github.mfuerstenau.leia.protocol.index.ReaderInfo;
import io.github.mfuerstenau.leia.protocol.terms.GetFieldTermScanResult;
import io.github.mfuerstenau.leia.protocol.version.JavaRuntime;
import io.github.mfuerstenau.leia.protocol.version.VersionParams;
import io.github.mfuerstenau.leia.protocol.version.VersionResult;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
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
    void rejectsStringAsNumber() {
        assertRejected("{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"version\",\"params\":{}}");
    }

    @Test
    void rejectsFractionAsInteger() {
        assertRejected("{\"jsonrpc\":\"2.0\",\"id\":1.5,\"method\":\"version\",\"params\":{}}");
        assertRejected("{\"jsonrpc\":\"2.0\",\"id\":1.0,\"method\":\"version\",\"params\":{}}");
    }

    @Test
    void rejectsNumberOrBooleanAsString() {
        assertRejected("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":7,\"params\":{}}");
        assertRejected("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":7.5,\"params\":{}}");
        assertRejected("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":true,\"params\":{}}");
    }

    @Test
    void rejectsStringAsOptionalNumber() {
        assertThatThrownBy(() -> MAPPER.readValue(
                        "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"error\":{\"code\":-32603,\"message\":\"m\"}}",
                        VERSION_RESPONSE))
                .isInstanceOf(JacksonException.class);
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

    @Test
    void writesAndReadsEnumsByName() {
        assertThat(MAPPER.writeValueAsString(IndexOptions.DOCS_AND_FREQS)).isEqualTo("\"DOCS_AND_FREQS\"");
        assertThat(MAPPER.readValue("\"DOCS_AND_FREQS\"", IndexOptions.class)).isEqualTo(IndexOptions.DOCS_AND_FREQS);
    }

    @Test
    void rejectsUnknownOrNumericEnumValue() {
        assertThatThrownBy(() -> MAPPER.readValue("\"POSITIONS\"", IndexOptions.class))
                .isInstanceOf(JacksonException.class);
        assertThatThrownBy(() -> MAPPER.readValue("1", IndexOptions.class)).isInstanceOf(JacksonException.class);
    }

    @Test
    void acceptsNullForOptional() {
        ReaderInfo result = MAPPER.readValue("""
                {"docBase":0,"segmentsFile":"segments_1","numDocs":0,"maxDoc":0,"createdVersionMajor":10,
                 "minSegmentLuceneVersion":null}
                """, ReaderInfo.class);

        assertThat(result.minSegmentLuceneVersion()).isEmpty();
    }

    @Test
    void rejectsNullMapValueAndListElement() {
        assertThatThrownBy(() -> MAPPER.readValue("{\"a\":null}", new TypeReference<Map<String, String>>() {}))
                .isInstanceOf(JacksonException.class);
        assertThatThrownBy(() -> MAPPER.readValue("[null]", new TypeReference<List<String>>() {}))
                .isInstanceOf(JacksonException.class);
    }

    @Test
    void writesRunningScanWithoutTerms() {
        GetFieldTermScanResult running = new GetFieldTermScanResult(2048, Optional.empty());

        String json = MAPPER.writeValueAsString(running);

        assertThat(json).isEqualTo("{\"termsScanned\":2048}");
        assertThat(MAPPER.readValue(json, GetFieldTermScanResult.class)).isEqualTo(running);
    }

    private static void assertRejected(String request) {
        assertThatThrownBy(() -> MAPPER.readValue(request, VERSION_REQUEST)).isInstanceOf(JacksonException.class);
    }
}
