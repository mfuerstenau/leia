package io.github.mfuerstenau.leia.mcp.tools;

import io.github.mfuerstenau.leia.mcp.McpTool;
import java.util.List;

/** Every tool of MCP mode, in the order {@code tools/list} gives them (ADR 0100). */
public final class Tools {

    public static final List<McpTool<?, ?>> ALL = List.of(
            new OpenIndexTool(),
            new IndexInfoTool(),
            new SearchTool(),
            new GetDocumentTool(),
            new GetFieldValueTool(),
            new ReconstructFieldsTool());

    private Tools() {}
}
