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
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.index.CurrentIndex;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.OptionalInt;
import java.util.logging.Level;
import java.util.logging.Logger;

/** {@code getCommits}: the commits of the {@link CurrentIndex}'s directory (ADR 0009). */
public final class GetCommitsHandler implements MethodHandler<GetCommitsParams, GetCommitsResult> {

    private static final Logger LOG = Logger.getLogger(GetCommitsHandler.class.getName());

    private final CurrentIndex currentIndex;

    @Inject
    GetCommitsHandler(CurrentIndex currentIndex) {
        this.currentIndex = currentIndex;
    }

    @Override
    public RpcMethod<GetCommitsParams, GetCommitsResult> method() {
        return Methods.GET_COMMITS;
    }

    @Override
    public GetCommitsResult handle(GetCommitsParams params) throws RpcException {
        OpenedIndex opened = currentIndex.opened();
        int index = directory(opened, params.index());
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

    /**
     * The index directory of the open path that {@code index} names, 0 if absent (ADR 0021).
     *
     * @throws RpcException {@code INVALID_PARAMS} if the open path has no such index directory
     */
    static int directory(OpenedIndex opened, OptionalInt index) throws RpcException {
        int position = index.orElse(0);
        if (!opened.hasDirectory(position)) {
            throw new RpcException(
                    ErrorCode.INVALID_PARAMS,
                    "Invalid params: no index " + position + "; there are "
                            + opened.directories().size());
        }
        return position;
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
