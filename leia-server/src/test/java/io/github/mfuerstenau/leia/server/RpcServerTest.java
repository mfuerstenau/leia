package io.github.mfuerstenau.leia.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.ProtocolJson;
import io.github.mfuerstenau.leia.protocol.Response;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;
import org.junit.jupiter.api.Test;

class RpcServerTest {

    private final RpcServer server = DaggerServerComponent.create().server();

    @Test
    void answersEachRequestOnItsOwnLineInOrder() throws IOException {
        String input = """
                {"jsonrpc":"2.0","id":1,"method":"version","params":{}}

                not json
                {"jsonrpc":"2.0","id":2,"method":"version","params":{}}
                """;

        List<String> lines = serve(input);

        assertThat(lines).hasSize(3);
        assertThat(lines.stream().map(RpcServerTest::idOf)).containsExactly("1", "null", "2");
        Response<?> first =
                ProtocolJson.mapper().readValue(lines.getFirst(), ProtocolJson.responseType(Methods.VERSION));
        assertThat(first.result()).isPresent();
    }

    @Test
    void returnsAtEndOfInput() throws IOException {
        assertThat(serve("")).isEmpty();
    }

    @Test
    void keepsResponsesOnOneLineEvenWithNewlinesInMessages() throws IOException {
        // The method name ends up in the error message; its escaped newline must stay escaped.
        List<String> lines = serve("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"a\\nb\",\"params\":{}}\n");

        assertThat(lines).singleElement().asString().contains("a\\nb");
    }

    private List<String> serve(String input) throws IOException {
        StringWriter out = new StringWriter();
        server.serve(new BufferedReader(new StringReader(input)), out);
        String output = out.toString();
        assertThat(output)
                .satisfiesAnyOf(
                        text -> assertThat(text).isEmpty(),
                        text -> assertThat(text).endsWith("\n"));
        return output.lines().toList();
    }

    private static String idOf(String responseLine) {
        Response<?> response = ProtocolJson.mapper().readValue(responseLine, ProtocolJson.responseType(Object.class));
        return response.id().map(String::valueOf).orElse("null");
    }
}
