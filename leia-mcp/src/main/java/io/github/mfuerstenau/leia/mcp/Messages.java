package io.github.mfuerstenau.leia.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The messages of MCP mode (ADR 0100 §1, §2): the records of MCP's results, the JSON-RPC envelope
 * around them, and the mapper that binds them and the tools' records.
 */
public final class Messages {

    /** As {@code DocumentExport}'s mapper: unknown properties fail, absent ones are left out. */
    public static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .changeDefaultPropertyInclusion(inclusion -> inclusion.withValueInclusion(JsonInclude.Include.NON_ABSENT))
            .build();

    /** The JSON-RPC errors that leia sends (ADR 0100 §1). */
    static final int PARSE_ERROR = -32700;

    static final int INVALID_REQUEST = -32600;
    static final int METHOD_NOT_FOUND = -32601;
    static final int INVALID_PARAMS = -32602;
    static final int INTERNAL_ERROR = -32603;

    private Messages() {}

    /** The result of {@code initialize}. */
    record InitializeResult(
            String protocolVersion, ServerCapabilities capabilities, ServerInfo serverInfo, String instructions) {}

    record ServerCapabilities(ToolsCapability tools) {}

    record ToolsCapability(boolean listChanged) {}

    record ServerInfo(String name, String version) {}

    /** The result of {@code tools/list}. */
    record ListToolsResult(List<Tool> tools) {}

    record Tool(
            String name,
            String description,
            JsonNode inputSchema,
            JsonNode outputSchema,
            ToolAnnotations annotations) {}

    /** leia only reads, and only the index it has open. */
    record ToolAnnotations(boolean readOnlyHint, boolean openWorldHint) {}

    /** The result of {@code tools/call}: the result record in {@code structuredContent} and as text. */
    record CallToolResult(
            List<TextContent> content,
            @Nullable JsonNode structuredContent,
            @JsonProperty("isError") boolean isError) {}

    record TextContent(String type, String text) {

        TextContent(String text) {
            this("text", text);
        }
    }

    /** The {@code tools/list} answer for {@code tools}. */
    static ListToolsResult toolsList(List<McpTool<?, ?>> tools) {
        return new ListToolsResult(tools.stream()
                .map(tool -> new Tool(
                        tool.name(),
                        tool.description(),
                        Schemas.of(tool.arguments()),
                        Schemas.of(tool.result()),
                        new ToolAnnotations(true, false)))
                .toList());
    }

    static ObjectNode response(JsonNode id, Object result) {
        ObjectNode message = envelope();
        message.set("id", id);
        message.set("result", MAPPER.valueToTree(result));
        return message;
    }

    static ObjectNode error(JsonNode id, int code, String text) {
        ObjectNode message = envelope();
        message.set("id", id);
        ObjectNode error = message.putObject("error");
        error.put("code", code);
        error.put("message", text);
        return message;
    }

    static ObjectNode notification(String method, ObjectNode params) {
        ObjectNode message = envelope();
        message.put("method", method);
        message.set("params", params);
        return message;
    }

    private static ObjectNode envelope() {
        ObjectNode message = MAPPER.createObjectNode();
        message.put("jsonrpc", "2.0");
        return message;
    }
}
