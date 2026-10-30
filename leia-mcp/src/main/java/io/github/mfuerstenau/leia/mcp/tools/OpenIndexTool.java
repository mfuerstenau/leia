package io.github.mfuerstenau.leia.mcp.tools;

import io.github.mfuerstenau.leia.mcp.Call;
import io.github.mfuerstenau.leia.mcp.Description;
import io.github.mfuerstenau.leia.mcp.Jobs;
import io.github.mfuerstenau.leia.mcp.McpServer;
import io.github.mfuerstenau.leia.mcp.McpTool;
import io.github.mfuerstenau.leia.mcp.ToolException;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.index.DirectoryImpl;
import io.github.mfuerstenau.leia.protocol.index.GetIndexStatsParams;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexResult;
import java.util.Optional;

/** {@code open_index}: opens an index in place of the open one (ADR 0100 §7), below the roots if any (ADR 0102). */
final class OpenIndexTool implements McpTool<OpenIndexTool.Arguments, IndexInfoTool.Result> {

    record Arguments(
            @Description("The index directory, or a directory with indexes below it, which open as one. A leading ~"
                    + " is the user's home; a relative path is against leia's working directory")
            String path,

            @Description("The Lucene Directory to read it through: FS lets Lucene choose, MMAP is MMapDirectory, NIO"
                    + " is NIOFSDirectory. Defaults to leia's --directory")
            Optional<DirectoryImpl> directory) {}

    @Override
    public String name() {
        return "open_index";
    }

    @Override
    public String description() {
        return "Open a Lucene index, read-only, in place of the open one, and describe it as index_info does. If it"
                + " can't be opened, the index that was open stays open.";
    }

    @Override
    public Class<Arguments> arguments() {
        return Arguments.class;
    }

    @Override
    public Class<IndexInfoTool.Result> result() {
        return IndexInfoTool.Result.class;
    }

    /** It replaces the index, which stops the jobs of the other calls. */
    @Override
    public boolean exclusive() {
        return true;
    }

    @Override
    public IndexInfoTool.Result call(Arguments arguments, Call call) throws ToolException, Jobs.Cancelled {
        String path = call.session().allowed(arguments.path());
        OpenIndexResult opened =
                call.call(Methods.OPEN_INDEX, McpServer.openParams(call.session(), path, arguments.directory()));
        call.session().opened(opened);
        return IndexInfoTool.info(opened, call.call(Methods.GET_INDEX_STATS, new GetIndexStatsParams()), call);
    }
}
