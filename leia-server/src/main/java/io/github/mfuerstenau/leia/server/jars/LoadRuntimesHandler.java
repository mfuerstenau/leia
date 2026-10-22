package io.github.mfuerstenau.leia.server.jars;

import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.jars.LoadRuntimesParams;
import io.github.mfuerstenau.leia.protocol.jars.LoadRuntimesResult;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.runtime.RuntimeFolders;
import io.github.mfuerstenau.leia.server.runtime.Runtimes;
import jakarta.inject.Inject;
import java.util.List;

/**
 * {@code loadRuntimes}: loads the folders of {@code runtimes/} that aren't loaded yet, such as those
 * just downloaded (ADR 0080). It reads only the jars, as {@code addJars} does, so it is no job.
 */
public final class LoadRuntimesHandler implements MethodHandler<LoadRuntimesParams, LoadRuntimesResult> {

    private final Runtimes runtimes;

    @Inject
    LoadRuntimesHandler(Runtimes runtimes) {
        this.runtimes = runtimes;
    }

    @Override
    public RpcMethod<LoadRuntimesParams, LoadRuntimesResult> method() {
        return Methods.LOAD_RUNTIMES;
    }

    @Override
    public LoadRuntimesResult handle(LoadRuntimesParams params) {
        List<RuntimeFolders.Known> before = loaded();
        RuntimeFolders.reload();
        // Lucene 10's folder may have become the chain's first link; job threads inherit this loader.
        Thread.currentThread().setContextClassLoader(JarChain.classLoader());
        List<String> fresh = loaded().stream()
                .filter(known -> !before.contains(known))
                .map(RuntimeFolders.Known::folder)
                .toList();
        return new LoadRuntimesResult(fresh, GetRuntimesHandler.folders(runtimes));
    }

    private static List<RuntimeFolders.Known> loaded() {
        return RuntimeFolders.KNOWN.stream()
                .filter(known -> RuntimeFolders.state(known) instanceof RuntimeFolders.State.Loaded)
                .toList();
    }
}
