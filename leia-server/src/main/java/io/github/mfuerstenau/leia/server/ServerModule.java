package io.github.mfuerstenau.leia.server;

import dagger.Module;
import dagger.Provides;
import jakarta.inject.Singleton;

@Module
interface ServerModule {

    @Provides
    @Singleton
    static BuildInfo buildInfo() {
        return BuildInfo.load();
    }
}
