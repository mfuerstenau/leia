package io.github.mfuerstenau.leia.server;

import dagger.Binds;
import dagger.Module;
import dagger.multibindings.IntoSet;
import io.github.mfuerstenau.leia.server.index.CloseIndexHandler;
import io.github.mfuerstenau.leia.server.index.GetIndexStatsHandler;
import io.github.mfuerstenau.leia.server.index.OpenIndexHandler;
import io.github.mfuerstenau.leia.server.job.CancelJobHandler;
import io.github.mfuerstenau.leia.server.terms.GetFieldTermScanHandler;
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
}
