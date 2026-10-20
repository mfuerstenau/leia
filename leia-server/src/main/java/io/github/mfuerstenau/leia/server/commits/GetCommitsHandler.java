package io.github.mfuerstenau.leia.server.commits;

import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.commits.GetCommitsParams;
import io.github.mfuerstenau.leia.protocol.commits.GetCommitsResult;
import io.github.mfuerstenau.leia.runtime.LuceneRuntime;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.index.CurrentIndex;
import io.github.mfuerstenau.leia.server.index.IndexDirectories;
import io.github.mfuerstenau.leia.server.runtime.RuntimeResults;
import jakarta.inject.Inject;

/**
 * {@code getCommits}: the commits of an index directory of the {@link CurrentIndex} (ADR 0009), read
 * by the Lucene that reads the directory (ADR 0045).
 */
public final class GetCommitsHandler implements MethodHandler<GetCommitsParams, GetCommitsResult> {

    private final IndexDirectories directories;

    @Inject
    GetCommitsHandler(IndexDirectories directories) {
        this.directories = directories;
    }

    @Override
    public RpcMethod<GetCommitsParams, GetCommitsResult> method() {
        return Methods.GET_COMMITS;
    }

    @Override
    public GetCommitsResult handle(GetCommitsParams params) throws RpcException {
        IndexDirectories.IndexDirectory dir = directories.opened(params.index());
        return switch (dir.route()) {
            case IndexDirectories.Route.Runtime(LuceneRuntime runtime) -> RuntimeResults.commits(runtime, dir);
            case IndexDirectories.Route.Missing missing -> throw missing.error(dir.path());
        };
    }
}
