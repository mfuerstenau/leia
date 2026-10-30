package io.github.mfuerstenau.leia.mcp.tools;

import io.github.mfuerstenau.leia.mcp.McpTool;
import java.util.List;

/** Every tool of MCP mode, in the order {@code tools/list} gives them (ADR 0100). */
public final class Tools {

    public static final List<McpTool<?, ?>> ALL = List.of(
            new OpenIndexTool(),
            new IndexInfoTool(),
            new SearchTool(),
            new AnalyzeTool(),
            new TermsTool(),
            new GetDocumentTool(),
            new GetFieldValueTool(),
            new ReconstructFieldsTool());

    /** The tools of {@code --mcp-pin}: all but {@code open_index}, so that the index stays (ADR 0102). */
    public static final List<McpTool<?, ?>> PINNED =
            ALL.stream().filter(tool -> !(tool instanceof OpenIndexTool)).toList();

    private Tools() {}
}
