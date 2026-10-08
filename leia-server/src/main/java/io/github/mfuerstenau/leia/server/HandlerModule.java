package io.github.mfuerstenau.leia.server;

import dagger.Binds;
import dagger.Module;
import dagger.multibindings.IntoSet;
import io.github.mfuerstenau.leia.server.analysis.AnalyzeHandler;
import io.github.mfuerstenau.leia.server.analysis.GetAnalysisComponentsHandler;
import io.github.mfuerstenau.leia.server.commits.GetCommitHandler;
import io.github.mfuerstenau.leia.server.commits.GetCommitsHandler;
import io.github.mfuerstenau.leia.server.document.GetDocumentHandler;
import io.github.mfuerstenau.leia.server.document.GetStoredValueHandler;
import io.github.mfuerstenau.leia.server.document.GetTermVectorHandler;
import io.github.mfuerstenau.leia.server.index.CloseIndexHandler;
import io.github.mfuerstenau.leia.server.index.GetIndexStatsHandler;
import io.github.mfuerstenau.leia.server.index.OpenIndexHandler;
import io.github.mfuerstenau.leia.server.index.ReopenIndexHandler;
import io.github.mfuerstenau.leia.server.job.CancelJobHandler;
import io.github.mfuerstenau.leia.server.search.GetExplainHandler;
import io.github.mfuerstenau.leia.server.search.GetSearchHandler;
import io.github.mfuerstenau.leia.server.search.StartExplainHandler;
import io.github.mfuerstenau.leia.server.search.StartSearchHandler;
import io.github.mfuerstenau.leia.server.terms.GetFieldTermScanHandler;
import io.github.mfuerstenau.leia.server.terms.GetPositionsHandler;
import io.github.mfuerstenau.leia.server.terms.GetPostingsHandler;
import io.github.mfuerstenau.leia.server.terms.GetTermsHandler;
import io.github.mfuerstenau.leia.server.terms.StartFieldTermScanHandler;
import io.github.mfuerstenau.leia.server.version.VersionHandler;

/** One binding per JSON-RPC method. {@link Dispatcher} checks that every method in {@code Methods} has one. */
@Module
interface HandlerModule {

    @Binds
    @IntoSet
    MethodHandler<?, ?> version(VersionHandler handler);

    @Binds
    @IntoSet
    MethodHandler<?, ?> openIndex(OpenIndexHandler handler);

    @Binds
    @IntoSet
    MethodHandler<?, ?> reopenIndex(ReopenIndexHandler handler);

    @Binds
    @IntoSet
    MethodHandler<?, ?> getIndexStats(GetIndexStatsHandler handler);

    @Binds
    @IntoSet
    MethodHandler<?, ?> closeIndex(CloseIndexHandler handler);

    @Binds
    @IntoSet
    MethodHandler<?, ?> startFieldTermScan(StartFieldTermScanHandler handler);

    @Binds
    @IntoSet
    MethodHandler<?, ?> getFieldTermScan(GetFieldTermScanHandler handler);

    @Binds
    @IntoSet
    MethodHandler<?, ?> cancelJob(CancelJobHandler handler);

    @Binds
    @IntoSet
    MethodHandler<?, ?> getDocument(GetDocumentHandler handler);

    @Binds
    @IntoSet
    MethodHandler<?, ?> getStoredValue(GetStoredValueHandler handler);

    @Binds
    @IntoSet
    MethodHandler<?, ?> getTermVector(GetTermVectorHandler handler);

    @Binds
    @IntoSet
    MethodHandler<?, ?> getTerms(GetTermsHandler handler);

    @Binds
    @IntoSet
    MethodHandler<?, ?> getPostings(GetPostingsHandler handler);

    @Binds
    @IntoSet
    MethodHandler<?, ?> getPositions(GetPositionsHandler handler);

    @Binds
    @IntoSet
    MethodHandler<?, ?> getCommits(GetCommitsHandler handler);

    @Binds
    @IntoSet
    MethodHandler<?, ?> getCommit(GetCommitHandler handler);

    @Binds
    @IntoSet
    MethodHandler<?, ?> startSearch(StartSearchHandler handler);

    @Binds
    @IntoSet
    MethodHandler<?, ?> getSearch(GetSearchHandler handler);

    @Binds
    @IntoSet
    MethodHandler<?, ?> startExplain(StartExplainHandler handler);

    @Binds
    @IntoSet
    MethodHandler<?, ?> getExplain(GetExplainHandler handler);

    @Binds
    @IntoSet
    MethodHandler<?, ?> getAnalysisComponents(GetAnalysisComponentsHandler handler);

    @Binds
    @IntoSet
    MethodHandler<?, ?> analyze(AnalyzeHandler handler);
}
