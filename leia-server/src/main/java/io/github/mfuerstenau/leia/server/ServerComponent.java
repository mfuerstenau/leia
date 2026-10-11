package io.github.mfuerstenau.leia.server;

import dagger.Component;
import io.github.mfuerstenau.leia.server.index.CurrentIndex;
import jakarta.inject.Singleton;

/** Root of the server's object graph. */
@Singleton
@Component(modules = {ServerModule.class, HandlerModule.class})
interface ServerComponent {

    RpcServer server();

    Handlers handlers();

    CurrentIndex currentIndex();
}
