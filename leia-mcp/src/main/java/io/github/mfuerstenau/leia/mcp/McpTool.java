package io.github.mfuerstenau.leia.mcp;

/**
 * A tool of MCP mode (ADR 0100): its name, what it does, and the records of its arguments and its
 * result, whose schemas {@link Schemas} generates. Every tool is in {@code Tools.ALL}.
 *
 * @param <A> the arguments
 * @param <R> the result
 */
public interface McpTool<A extends Record, R extends Record> {

    String name();

    /** What the tool does, for the agent. */
    String description();

    Class<A> arguments();

    Class<R> result();

    /**
     * Calls the backend for {@code arguments}.
     *
     * @throws ToolException for an expected failure or bad arguments, which the agent sees
     * @throws Jobs.Cancelled if the request was cancelled, which gets no answer
     */
    R call(A arguments, Call call) throws ToolException, Jobs.Cancelled;
}
