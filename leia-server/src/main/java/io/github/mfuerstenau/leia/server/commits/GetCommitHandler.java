package io.github.mfuerstenau.leia.server.commits;

import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.commits.GetCommitParams;
import io.github.mfuerstenau.leia.protocol.commits.GetCommitResult;
import io.github.mfuerstenau.leia.runtime.LuceneRuntime;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.index.CurrentIndex;
import io.github.mfuerstenau.leia.server.index.IndexDirectories;
import io.github.mfuerstenau.leia.server.runtime.RuntimeResults;
import jakarta.inject.Inject;

/**
 * {@code getCommit}: one commit of an index directory of the {@link CurrentIndex} whole (ADR 0009),
 * read by the Lucene that reads the directory (ADR 0045).
 */
public final class GetCommitHandler implements MethodHandler<GetCommitParams, GetCommitResult> {

    private final IndexDirectories directories;

    @Inject
    GetCommitHandler(IndexDirectories directories) {
        this.directories = directories;
    }

    @Override
    public RpcMethod<GetCommitParams, GetCommitResult> method() {
        return Methods.GET_COMMIT;
    }

    @Override
    public GetCommitResult handle(GetCommitParams params) throws RpcException {
        IndexDirectories.IndexDirectory dir = directories.opened(params.index());
        return switch (dir.route()) {
            case IndexDirectories.Route.Runtime(LuceneRuntime runtime) ->
                RuntimeResults.commit(runtime, dir, params.generation());
            case IndexDirectories.Route.Missing missing -> throw missing.error(dir.path());
        };
    }
}
