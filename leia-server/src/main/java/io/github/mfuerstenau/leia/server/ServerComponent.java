package io.github.mfuerstenau.leia.server;

import dagger.BindsInstance;
import dagger.Component;
import io.github.mfuerstenau.leia.server.index.CurrentIndex;
import io.github.mfuerstenau.leia.server.stored.StoredFieldsCache;
import jakarta.inject.Singleton;

/** Root of the server's object graph. */
@Singleton
@Component(modules = {ServerModule.class, HandlerModule.class})
interface ServerComponent {

    Handlers handlers();

    CurrentIndex currentIndex();

    @Component.Factory
    interface Factory {

        /** The graph, with the cache of the stored fields that lives as long as the backend (ADR 0113). */
        ServerComponent create(@BindsInstance StoredFieldsCache storedFieldsCache);
    }
}
