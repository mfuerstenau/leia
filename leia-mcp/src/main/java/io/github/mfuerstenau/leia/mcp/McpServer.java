package io.github.mfuerstenau.leia.mcp;

import io.github.mfuerstenau.leia.mcp.tools.Tools;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.index.DirectoryImpl;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexParams;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexResult;
import io.github.mfuerstenau.leia.protocol.index.OpenMode;
import io.github.mfuerstenau.leia.protocol.job.JobParams;
import io.github.mfuerstenau.leia.protocol.job.JobStarted;
import io.github.mfuerstenau.leia.protocol.version.VersionResult;
import io.github.mfuerstenau.leia.server.LeiaBackend;
import io.github.mfuerstenau.leia.server.RpcException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.NullNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * MCP mode (ADR 0100): reads JSON-RPC requests from stdin, one per line, and answers on stdout.
 * The main thread reads and answers what needs no backend; the tool calls run one after the other
 * on the worker thread {@code leia-mcp}, through {@link LeiaBackend#call} as the UI's calls do.
 */
public final class McpServer {

    private static final Logger LOG = Logger.getLogger(McpServer.class.getName());

    /** The protocol versions leia knows, the latest last (ADR 0100 §1). */
    static final List<String> PROTOCOL_VERSIONS = List.of("2025-03-26", "2025-06-18", "2025-11-25");

    /** How long the end of stdin waits for the request that runs to stop. */
    private static final Duration STOP_TIMEOUT = Duration.ofMinutes(1);

    static final String INSTRUCTIONS = """
            leia reads Lucene indexes, never writes them. Call index_info to see the open index and its \
            fields, or open_index to open one. search finds documents with a query of Lucene's classic \
            syntax, or one exact term; get_document shows a document's fields, get_field_value a whole \
            stored value in slices, and reconstruct_fields the terms a document was indexed with, also \
            in the fields that aren't stored.""";

    private final LeiaBackend backend;
    private final VersionResult version;
    private final Session session;
    private final List<McpTool<?, ?>> tools;

    /** The tool calls that wait or run, by the JSON of their id. */
    private final Map<String, Request> requests = new ConcurrentHashMap<>();

    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon().name("leia-mcp").factory());

    /**
     * @param version what {@code version} says, for {@code serverInfo}
     * @param defaultDirectory the {@code Directory} of {@code --directory}, {@code open_index}'s default
     * @param home the user's home, for a path with a leading {@code ~}
     */
    public McpServer(LeiaBackend backend, VersionResult version, DirectoryImpl defaultDirectory, Optional<Path> home) {
        this(backend, version, new Session(defaultDirectory, home), Tools.ALL);
    }

    McpServer(LeiaBackend backend, VersionResult version, Session session, List<McpTool<?, ?>> tools) {
        this.backend = backend;
        this.version = version;
        this.session = session;
        this.tools = tools;
    }

    /**
     * Opens {@code path} with a reader through the default {@code Directory}, before {@link #serve}:
     * the INDEX_PATH of the command line (ADR 0100 §7).
     *
     * @throws RpcException if it can't be opened
     */
    public OpenIndexResult open(String path) throws RpcException, InterruptedException {
        try {
            OpenIndexResult result = backend.call(Methods.OPEN_INDEX, openParams(session, path, Optional.empty()))
                    .get();
            session.opened(result);
            return result;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RpcException rpc) {
                throw rpc;
            }
            throw new IllegalStateException("Cannot open " + path, e.getCause());
        }
    }

    /** The params of {@code openIndex} for {@code path} with a reader, through {@code directory} or the default. */
    public static OpenIndexParams openParams(Session session, String path, Optional<DirectoryImpl> directory) {
        return new OpenIndexParams(
                session.expandHome(path),
                Optional.of(directory.orElse(session.defaultDirectory())),
                Optional.of(OpenMode.READER));
    }

    /**
     * Answers the requests of {@code in} on {@code out} until {@code in} ends. Then the request that
     * runs is cancelled, and those that wait are dropped (ADR 0100 §5).
     *
     * @return the exit status: 0
     */
    public int serve(InputStream in, OutputStream out) throws InterruptedException {
        Output output = new Output(out);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    receive(line, output);
                }
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Cannot read stdin", e);
        }
        LOG.info("stdin ended");
        requests.values().forEach(request -> request.cancelled.set(true));
        worker.shutdown();
        if (!worker.awaitTermination(STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            LOG.warning(() -> "The last request did not stop within " + STOP_TIMEOUT.toSeconds() + " s");
        }
        return 0;
    }

    private void receive(String line, Output output) {
        JsonNode message;
        try {
            message = Messages.MAPPER.readTree(line);
        } catch (JacksonException e) {
            output.send(Messages.error(
                    NullNode.getInstance(), Messages.PARSE_ERROR, "Parse error: " + e.getOriginalMessage()));
            return;
        }
        if (message == null || !message.isObject()) {
            output.send(Messages.error(NullNode.getInstance(), Messages.INVALID_REQUEST, "Not a JSON-RPC request"));
            return;
        }
        JsonNode id = message.get("id");
        boolean idValid = id == null || id.isString() || id.isIntegralNumber();
        JsonNode method = message.get("method");
        if (!idValid || !message.path("jsonrpc").asString("").equals("2.0") || method == null || !method.isString()) {
            output.send(Messages.error(
                    idValid && id != null ? id : NullNode.getInstance(),
                    Messages.INVALID_REQUEST,
                    "Not a JSON-RPC 2.0 request: it needs \"jsonrpc\": \"2.0\" and a method"));
            return;
        }
        JsonNode params = message.path("params");
        if (id == null) {
            notification(method.stringValue(), params);
        } else {
            request(id, method.stringValue(), params, output);
        }
    }

    private void notification(String method, JsonNode params) {
        LOG.fine(() -> method + " " + params);
        if (method.equals("notifications/cancelled")) {
            JsonNode requestId = params.path("requestId");
            Request request = requests.get(requestId.toString());
            if (request != null) {
                LOG.info(() -> "Request " + requestId + " cancelled: "
                        + params.path("reason").asString(""));
                request.cancelled.set(true);
            }
        }
        // notifications/initialized and any other need nothing.
    }

    private void request(JsonNode id, String method, JsonNode params, Output output) {
        LOG.fine(() -> method + " " + id + " " + params);
        switch (method) {
            case "initialize" -> output.send(Messages.response(id, initialize(params)));
            case "ping" -> output.send(Messages.response(id, Messages.MAPPER.createObjectNode()));
            case "tools/list" -> output.send(Messages.response(id, Messages.toolsList(tools)));
            case "tools/call" -> {
                Request request = new Request(id, params.path("_meta").get("progressToken"));
                if (requests.putIfAbsent(id.toString(), request) != null) {
                    output.send(Messages.error(id, Messages.INVALID_REQUEST, "A request with this id is running"));
                    return;
                }
                worker.execute(() -> {
                    try {
                        if (!request.cancelled.get()) {
                            callTool(request, params, output);
                        }
                    } finally {
                        requests.remove(id.toString());
                    }
                });
            }
            default -> output.send(Messages.error(id, Messages.METHOD_NOT_FOUND, "Method not found: " + method));
        }
    }

    private Messages.InitializeResult initialize(JsonNode params) {
        String asked = params.path("protocolVersion").asString("");
        String answered = PROTOCOL_VERSIONS.contains(asked) ? asked : PROTOCOL_VERSIONS.getLast();
        LOG.info(() -> "MCP client " + params.path("clientInfo") + ", protocol " + asked + ", answered " + answered);
        return new Messages.InitializeResult(
                answered,
                new Messages.ServerCapabilities(new Messages.ToolsCapability(false)),
                new Messages.ServerInfo("leia", version.version()),
                INSTRUCTIONS);
    }

    /** Runs a {@code tools/call} on the worker thread, and answers it unless it was cancelled. */
    // A LinkageError of a runtime must still be answered (ADR 0088)
    @SuppressWarnings("java:S1181")
    private void callTool(Request request, JsonNode params, Output output) {
        String name = params.path("name").asString("");
        Optional<McpTool<?, ?>> tool = tools.stream()
                .filter(candidate -> candidate.name().equals(name))
                .findFirst();
        if (tool.isEmpty()) {
            output.send(Messages.error(request.id, Messages.INVALID_PARAMS, "Unknown tool: " + name));
            return;
        }
        JsonNode arguments = params.path("arguments");
        LOG.info(() -> "tools/call " + name + " " + (arguments.isMissingNode() ? "{}" : arguments));
        Messages.CallToolResult result;
        try {
            JsonNode given = arguments.isMissingNode() ? Messages.MAPPER.createObjectNode() : arguments;
            result = success(run(tool.get(), given, new RequestCall(request, output)));
        } catch (ToolException e) {
            result = failure(e.getMessage());
        } catch (Jobs.Cancelled _) {
            LOG.info(() -> "Request " + request.id + " cancelled; no answer");
            return;
        } catch (RuntimeException | Error e) {
            LOG.log(Level.SEVERE, e, () -> "tools/call " + name + " failed");
            output.send(Messages.error(request.id, Messages.INTERNAL_ERROR, "Internal error: " + e));
            return;
        }
        if (request.cancelled.get()) {
            LOG.info(() -> "Request " + request.id + " cancelled; no answer");
            return;
        }
        output.send(Messages.response(request.id, result));
    }

    private static <A extends Record, R extends Record> R run(McpTool<A, R> tool, JsonNode arguments, Call call)
            throws ToolException, Jobs.Cancelled {
        if (!arguments.isObject()) {
            throw ToolError.invalid("The arguments must be an object");
        }
        List<String> missing = Schemas.missing(tool.arguments(), arguments);
        if (!missing.isEmpty()) {
            throw ToolError.invalid("Missing argument " + String.join(", ", missing));
        }
        A bound;
        try {
            bound = Messages.MAPPER.treeToValue(arguments, tool.arguments());
        } catch (JacksonException e) {
            throw ToolError.invalid("Bad arguments: " + e.getOriginalMessage());
        }
        return tool.call(bound, call);
    }

    private static Messages.CallToolResult success(Record result) {
        JsonNode structured = Messages.MAPPER.valueToTree(result);
        return new Messages.CallToolResult(
                List.of(new Messages.TextContent(Messages.MAPPER.writeValueAsString(structured))),
                Optional.of(structured),
                false);
    }

    private static Messages.CallToolResult failure(String text) {
        return new Messages.CallToolResult(List.of(new Messages.TextContent(text)), Optional.empty(), true);
    }

    /** A tool call that waits or runs: its id, the token of its progress, and whether it was cancelled. */
    private static final class Request {
        final JsonNode id;
        final Optional<JsonNode> progressToken;
        final AtomicBoolean cancelled = new AtomicBoolean();

        Request(JsonNode id, JsonNode progressToken) {
            this.id = id;
            this.progressToken =
                    Optional.ofNullable(progressToken).filter(token -> token.isString() || token.isIntegralNumber());
        }
    }

    /** The backend for one request, which sends the progress of its jobs to {@code output} if the client asked. */
    private final class RequestCall implements Call {

        private final Request request;
        private final Output output;

        RequestCall(Request request, Output output) {
            this.request = request;
            this.output = output;
        }

        @Override
        public Session session() {
            return session;
        }

        @Override
        public <P, R> R call(RpcMethod<P, R> method, P params) throws ToolException, Jobs.Cancelled {
            try {
                return backend.call(method, params).get();
            } catch (ExecutionException e) {
                throw ToolError.of(e);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                throw new Jobs.Cancelled();
            }
        }

        @Override
        public <P, G, T> T job(
                RpcMethod<P, JobStarted> start,
                P params,
                RpcMethod<JobParams, G> get,
                Function<G, Optional<T>> done,
                Function<G, Jobs.Progress> progress)
                throws ToolException, Jobs.Cancelled {
            return Jobs.run(this, start, params, get, done, progress, this::progress, request.cancelled::get);
        }

        private void progress(Jobs.Progress progress) {
            if (request.progressToken.isEmpty()) {
                return;
            }
            ObjectNode params = Messages.MAPPER.createObjectNode();
            params.set("progressToken", request.progressToken.get());
            params.put("progress", progress.scanned());
            progress.total().ifPresent(total -> params.put("total", total));
            output.send(Messages.notification("notifications/progress", params));
        }
    }

    /** stdout: one message per line, written whole and flushed, from either thread. */
    private static final class Output {
        private final OutputStream out;

        Output(OutputStream out) {
            this.out = out;
        }

        synchronized void send(JsonNode message) {
            String line = Messages.MAPPER.writeValueAsString(message);
            LOG.fine(() -> "-> " + line);
            try {
                out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (IOException e) {
                // The client is gone; stdin ends next.
                LOG.log(Level.WARNING, "Cannot write to stdout", e);
            }
        }
    }
}
