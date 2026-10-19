package io.github.mfuerstenau.leia.server;

import dagger.BindsInstance;
import dagger.Component;
import io.github.mfuerstenau.leia.server.index.CurrentIndex;
import io.github.mfuerstenau.leia.server.index.Routing;
import jakarta.inject.Singleton;

/** Root of the server's object graph. */
@Singleton
@Component(modules = {ServerModule.class, HandlerModule.class})
interface ServerComponent {

    Handlers handlers();

    CurrentIndex currentIndex();

    @Component.Factory
    interface Factory {
        ServerComponent create(@BindsInstance Routing routing);
    }
}
