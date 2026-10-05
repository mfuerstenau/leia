package io.github.mfuerstenau.leia.server;

import dagger.Component;
import jakarta.inject.Singleton;

/** Root of the server's object graph. */
@Singleton
@Component(modules = {ServerModule.class, HandlerModule.class})
interface ServerComponent {

    RpcServer server();
}
