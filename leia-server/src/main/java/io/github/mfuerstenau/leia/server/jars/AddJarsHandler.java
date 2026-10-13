package io.github.mfuerstenau.leia.server.jars;

import io.github.mfuerstenau.leia.core.AddedJars;
import io.github.mfuerstenau.leia.core.ExtensionJarException;
import io.github.mfuerstenau.leia.core.ExtensionJars;
import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.jars.AddJarsParams;
import io.github.mfuerstenau.leia.protocol.jars.AddJarsResult;
import io.github.mfuerstenau.leia.protocol.jars.JarComponents;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import jakarta.inject.Inject;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * {@code addJars}: loads more jars while the server runs (ADR 0024). It reads only the jars, so it
 * is no job.
 */
public final class AddJarsHandler implements MethodHandler<AddJarsParams, AddJarsResult> {

    private static final Logger LOG = Logger.getLogger(AddJarsHandler.class.getName());

    @Inject
    AddJarsHandler() {}

    @Override
    public RpcMethod<AddJarsParams, AddJarsResult> method() {
        return Methods.ADD_JARS;
    }

    @Override
    public AddJarsResult handle(AddJarsParams params) throws RpcException {
        if (params.paths().isEmpty()) {
            throw new RpcException(ErrorCode.INVALID_PARAMS, "Invalid params: no jars to add");
        }
        List<Path> paths = new ArrayList<>();
        for (String path : params.paths()) {
            try {
                paths.add(Path.of(path));
            } catch (InvalidPathException e) {
                throw new RpcException(ErrorCode.INVALID_PARAMS, "Invalid params: not a path: " + e.getMessage(), e);
            }
        }
        AddedJars added;
        try {
            added = ExtensionJars.add(paths);
        } catch (ExtensionJarException e) {
            throw new RpcException(ErrorCode.INVALID_JAR, "Cannot add the jars: " + e.getMessage(), e);
        }
        if (!added.jars().isEmpty()) {
            LOG.info(() -> "Added the jars " + added.jars());
            // Job threads are started from this thread, and inherit its loader (as in LeiaBackend.start).
            Thread.currentThread().setContextClassLoader(ExtensionJars.classLoader());
        }
        io.github.mfuerstenau.leia.core.JarComponents components = added.added();
        return new AddJarsResult(
                strings(added.jars()),
                strings(added.alreadyLoaded()),
                new JarComponents(
                        components.analyzers(),
                        components.charFilters(),
                        components.tokenizers(),
                        components.tokenFilters(),
                        components.codecs(),
                        components.postingsFormats(),
                        components.docValuesFormats(),
                        components.knnVectorsFormats()));
    }

    static List<String> strings(List<Path> paths) {
        return paths.stream().map(Path::toString).toList();
    }
}
