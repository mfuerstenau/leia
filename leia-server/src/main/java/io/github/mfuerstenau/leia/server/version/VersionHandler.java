package io.github.mfuerstenau.leia.server.version;

import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.version.JavaRuntime;
import io.github.mfuerstenau.leia.protocol.version.VersionParams;
import io.github.mfuerstenau.leia.protocol.version.VersionResult;
import io.github.mfuerstenau.leia.server.BuildInfo;
import io.github.mfuerstenau.leia.server.MethodHandler;
import jakarta.inject.Inject;

/** {@code version}: the server's name, version and build, and the JVM it runs on. */
public final class VersionHandler implements MethodHandler<VersionParams, VersionResult> {

    static final String SERVER_NAME = "leia-server";

    private final BuildInfo buildInfo;

    @Inject
    VersionHandler(BuildInfo buildInfo) {
        this.buildInfo = buildInfo;
    }

    @Override
    public RpcMethod<VersionParams, VersionResult> method() {
        return Methods.VERSION;
    }

    @Override
    public VersionResult handle(VersionParams params) {
        return new VersionResult(
                SERVER_NAME,
                buildInfo.version(),
                buildInfo.commit(),
                buildInfo.commitTime(),
                new JavaRuntime(
                        Runtime.version().toString(),
                        System.getProperty("java.vendor"),
                        System.getProperty("java.home")));
    }
}
