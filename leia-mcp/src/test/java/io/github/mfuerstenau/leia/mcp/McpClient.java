package io.github.mfuerstenau.leia.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.JsonNode;

/**
 * A client of an {@link McpServer} that serves on a thread of its own: writes lines to its stdin and
 * reads the messages of its stdout, each of which must be one line of JSON.
 */
final class McpClient {

    private static final long TIMEOUT_SECONDS = 30;

    private final PipedOutputStream stdin = new PipedOutputStream();
    private final BlockingQueue<JsonNode> messages = new LinkedBlockingQueue<>();
    private final List<JsonNode> received = new ArrayList<>();
    private final CompletableFuture<Integer> status;

    McpClient(McpServer server) throws IOException {
        PipedInputStream in = new PipedInputStream(stdin, 1 << 16);
        status = CompletableFuture.supplyAsync(() -> {
            try {
                return server.serve(in, new Lines());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        });
    }

    void send(String line) throws IOException {
        stdin.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        stdin.flush();
    }

    /** Sends a request and returns its answer. */
    JsonNode request(int id, String method, String params) throws Exception {
        send("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method + "\",\"params\":" + params + "}");
        return answer(id);
    }

    /** Calls {@code tool} and returns the result of its answer. */
    JsonNode tool(int id, String tool, String arguments) throws Exception {
        JsonNode answer = request(id, "tools/call", "{\"name\":\"" + tool + "\",\"arguments\":" + arguments + "}");
        assertThat(answer.has("result")).as(answer.toString()).isTrue();
        return answer.get("result");
    }

    /** Calls {@code tool}, which must succeed, and returns its structured content. */
    JsonNode structured(int id, String tool, String arguments) throws Exception {
        JsonNode result = tool(id, tool, arguments);
        assertThat(result.path("isError").asBoolean()).as(result.toString()).isFalse();
        // The text holds the same JSON, for clients without structured content.
        assertThat(Messages.MAPPER.readTree(
                        result.path("content").get(0).path("text").asString()))
                .isEqualTo(result.get("structuredContent"));
        return result.get("structuredContent");
    }

    /** Calls {@code tool}, which must fail, and returns the text of its error. */
    String error(int id, String tool, String arguments) throws Exception {
        JsonNode result = tool(id, tool, arguments);
        assertThat(result.path("isError").asBoolean()).as(result.toString()).isTrue();
        assertThat(result.has("structuredContent")).isFalse();
        return result.path("content").get(0).path("text").asString();
    }

    /** The message with {@code id}, waiting for it; the messages before it are kept in {@link #received}. */
    JsonNode answer(int id) throws Exception {
        while (true) {
            JsonNode message = messages.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(message).as("an answer to " + id).isNotNull();
            received.add(message);
            if (message.path("id").isIntegralNumber() && message.path("id").asInt() == id) {
                return message;
            }
        }
    }

    /** Every message received so far by {@link #answer}. */
    List<JsonNode> received() {
        return received;
    }

    /** Ends stdin, and returns what {@code serve} returned; no message may be left unread. */
    int end() throws Exception {
        stdin.close();
        int exit = status.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        received.addAll(messages);
        return exit;
    }

    /** Ends stdin unless {@link #end} did. */
    void close() throws Exception {
        if (!status.isDone()) {
            end();
        }
    }

    /** stdout, which hands each line to {@link #messages} as JSON. */
    private final class Lines extends OutputStream {
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();

        @Override
        public synchronized void write(int b) {
            if (b == '\n') {
                messages.add(Messages.MAPPER.readTree(line.toString(StandardCharsets.UTF_8)));
                line.reset();
            } else {
                line.write(b);
            }
        }
    }
}
