package io.github.mfuerstenau.leia.server.commits;

import io.github.mfuerstenau.leia.core.CommitInfo;
import io.github.mfuerstenau.leia.core.CommitList;
import io.github.mfuerstenau.leia.core.ReadOnlyIndex;
import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.commits.Commit;
import io.github.mfuerstenau.leia.protocol.commits.GetCommitsParams;
import io.github.mfuerstenau.leia.protocol.commits.GetCommitsResult;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.document.GetDocumentHandler;
import io.github.mfuerstenau.leia.server.index.CurrentIndex;
import jakarta.inject.Inject;
import java.io.IOException;
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
        ReadOnlyIndex index = GetDocumentHandler.openIndex(currentIndex);
        CommitList list;
        try {
            list = index.commits();
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
                list.commits().stream().map(GetCommitsHandler::commit).toList());
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
