package io.github.mfuerstenau.leia.server;

import dagger.Binds;
import dagger.Module;
import dagger.multibindings.IntoSet;
import io.github.mfuerstenau.leia.server.index.OpenIndexHandler;
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
}
