package io.github.mfuerstenau.leia.server.commits;

import io.github.mfuerstenau.leia.core.CommitInfo;
import io.github.mfuerstenau.leia.core.CommitList;
import io.github.mfuerstenau.leia.core.OpenedIndex;
import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.commits.Commit;
import io.github.mfuerstenau.leia.protocol.commits.GetCommitsParams;
import io.github.mfuerstenau.leia.protocol.commits.GetCommitsResult;
import io.github.mfuerstenau.leia.protocol.commits.UnreadableCommit;
import io.github.mfuerstenau.leia.runtime.LuceneRuntime;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.index.CurrentIndex;
import io.github.mfuerstenau.leia.server.index.IndexDirectories;
import io.github.mfuerstenau.leia.server.runtime.RuntimeResults;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@code getCommits}: the commits of an index directory of the {@link CurrentIndex} (ADR 0009), read
 * by the Lucene that reads the directory (ADR 0045).
 */
public final class GetCommitsHandler implements MethodHandler<GetCommitsParams, GetCommitsResult> {

    private static final Logger LOG = Logger.getLogger(GetCommitsHandler.class.getName());

    private final CurrentIndex currentIndex;
    private final IndexDirectories directories;

    @Inject
    GetCommitsHandler(CurrentIndex currentIndex, IndexDirectories directories) {
        this.currentIndex = currentIndex;
        this.directories = directories;
    }

    @Override
    public RpcMethod<GetCommitsParams, GetCommitsResult> method() {
        return Methods.GET_COMMITS;
    }

    @Override
    public GetCommitsResult handle(GetCommitsParams params) throws RpcException {
        IndexDirectories.IndexDirectory dir = directories.opened(params.index());
        switch (dir.route()) {
            case IndexDirectories.Route.Runtime(LuceneRuntime runtime) -> {
                return RuntimeResults.commits(runtime, dir);
            }
            case IndexDirectories.Route.Missing missing -> throw missing.error(dir.path());
            case IndexDirectories.Route.Core() -> {}
        }
        OpenedIndex opened = currentIndex.opened();
        int index = dir.position().orElseThrow();
        CommitList list;
        try {
            list = opened.commits(index);
        } catch (IOException e) {
            throw new RpcException(ErrorCode.INDEX_NOT_READABLE, "Cannot list the commits: " + e.getMessage(), e);
        }
        for (CommitList.Unreadable unreadable : list.unreadable()) {
            LOG.log(
                    Level.WARNING,
                    unreadable.error(),
                    () -> "Skipped the unreadable commit " + unreadable.segmentsFile() + ": "
                            + unreadable.error().getMessage());
        }
        return new GetCommitsResult(
                list.commits().stream().map(GetCommitsHandler::commit).toList(),
                list.unreadable().stream()
                        .map(unreadable -> new UnreadableCommit(unreadable.segmentsFile(), unreadable.message()))
                        .toList());
    }

    static Commit commit(CommitInfo commit) {
        return new Commit(
                commit.generation(),
                commit.segmentsFile(),
                commit.current(),
                commit.version(),
                commit.segmentCount(),
                commit.maxDoc(),
                commit.delCount(),
                commit.luceneVersion().toString(),
                commit.userData());
    }
}
