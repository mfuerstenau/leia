package io.github.mfuerstenau.leia.server.jars;

import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.jars.GetRuntimesParams;
import io.github.mfuerstenau.leia.protocol.jars.GetRuntimesResult;
import io.github.mfuerstenau.leia.protocol.jars.RuntimeFolder;
import io.github.mfuerstenau.leia.protocol.jars.RuntimeState;
import io.github.mfuerstenau.leia.protocol.jars.ServerRuntimeInfo;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.runtime.RuntimeFolders;
import io.github.mfuerstenau.leia.server.runtime.Runtimes;
import io.github.mfuerstenau.leia.server.runtime.ServerRuntime;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * {@code getRuntimes}: every runtime leia knows, and whether it is loaded (ADR 0072, ADR 0073), and
 * the servers' runtimes (ADR 0090).
 */
public final class GetRuntimesHandler implements MethodHandler<GetRuntimesParams, GetRuntimesResult> {

    private final Runtimes runtimes;

    @Inject
    GetRuntimesHandler(Runtimes runtimes) {
        this.runtimes = runtimes;
    }

    @Override
    public RpcMethod<GetRuntimesParams, GetRuntimesResult> method() {
        return Methods.GET_RUNTIMES;
    }

    @Override
    public GetRuntimesResult handle(GetRuntimesParams params) {
        return new GetRuntimesResult(
                folders(runtimes),
                runtimes.servers().stream().map(GetRuntimesHandler::server).toList());
    }

    static ServerRuntimeInfo server(ServerRuntime server) {
        return new ServerRuntimeInfo(
                server.home().toString(),
                server.label(),
                server.luceneVersion(),
                server.indexMajors().stream().sorted().toList());
    }

    /** Every runtime leia knows, newest first, as {@code getRuntimes} and {@code loadRuntimes} return them. */
    static List<RuntimeFolder> folders(Runtimes runtimes) {
        return runtimes.known().stream().map(GetRuntimesHandler::folder).toList();
    }

    static RuntimeFolder folder(Runtimes.KnownState known) {
        String name = known.known().folder();
        if (known.luceneVersion().isPresent()) {
            return new RuntimeFolder(
                    name,
                    known.known().indexMajors(),
                    RuntimeState.LOADED,
                    known.luceneVersion(),
                    Optional.empty(),
                    RuntimeFolders.loadedFrom(known.known()).map(Path::toString));
        }
        return switch (known.folder()) {
            // Loaded, but its runtime gone from the list: not expected.
            case RuntimeFolders.State.Loaded _, RuntimeFolders.State.Missing _ ->
                new RuntimeFolder(
                        name, known.known().indexMajors(), RuntimeState.MISSING, Optional.empty(), Optional.empty());
            case RuntimeFolders.State.Incomplete incomplete ->
                new RuntimeFolder(
                        name,
                        known.known().indexMajors(),
                        RuntimeState.INCOMPLETE,
                        Optional.empty(),
                        Optional.of(incomplete.jar()));
            case RuntimeFolders.State.Refused refused ->
                new RuntimeFolder(
                        name,
                        known.known().indexMajors(),
                        RuntimeState.REFUSED,
                        Optional.empty(),
                        Optional.of(refused.reason()));
            case RuntimeFolders.State.Restart _ ->
                new RuntimeFolder(
                        name, known.known().indexMajors(), RuntimeState.RESTART, Optional.empty(), Optional.empty());
        };
    }
}
