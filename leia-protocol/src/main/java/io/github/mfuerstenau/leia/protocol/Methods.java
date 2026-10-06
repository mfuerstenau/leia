package io.github.mfuerstenau.leia.protocol;

import io.github.mfuerstenau.leia.protocol.index.OpenIndexParams;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexResult;
import io.github.mfuerstenau.leia.protocol.version.VersionParams;
import io.github.mfuerstenau.leia.protocol.version.VersionResult;
import java.util.List;
import java.util.Optional;

/** The registry of all JSON-RPC methods. Each one needs fixtures in {@code protocol/fixtures/}. */
public final class Methods {

    public static final RpcMethod<VersionParams, VersionResult> VERSION =
            new RpcMethod<>("version", VersionParams.class, VersionResult.class);

    /**
     * Opens the latest commit of an index, read-only, and makes it the open index. An index that was
     * open before is closed once the new one has opened; if opening fails, it stays open.
     */
    public static final RpcMethod<OpenIndexParams, OpenIndexResult> OPEN_INDEX =
            new RpcMethod<>("openIndex", OpenIndexParams.class, OpenIndexResult.class);

    public static final List<RpcMethod<?, ?>> ALL = List.of(VERSION, OPEN_INDEX);

    private Methods() {}

    public static Optional<RpcMethod<?, ?>> byName(String name) {
        return ALL.stream().filter(method -> method.name().equals(name)).findFirst();
    }
}
