package io.github.mfuerstenau.leia.server.jars;

import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.jars.GetRuntimesParams;
import io.github.mfuerstenau.leia.protocol.jars.GetRuntimesResult;
import io.github.mfuerstenau.leia.protocol.jars.RuntimeFolder;
import io.github.mfuerstenau.leia.protocol.jars.RuntimeState;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.runtime.RuntimeFolders;
import io.github.mfuerstenau.leia.server.runtime.Runtimes;
import jakarta.inject.Inject;
import java.util.Optional;

/** {@code getRuntimes}: every runtime leia knows, and whether it is loaded (ADR 0072, ADR 0073). */
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
                runtimes.known().stream().map(GetRuntimesHandler::folder).toList());
    }

    private static RuntimeFolder folder(Runtimes.KnownState known) {
        String name = known.known().folder();
        if (known.luceneVersion().isPresent()) {
            return new RuntimeFolder(
                    name, known.known().indexMajors(), RuntimeState.LOADED, known.luceneVersion(), Optional.empty());
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
        };
    }
}
