package io.github.mfuerstenau.leia.server.jars;

import io.github.mfuerstenau.leia.core.ExtensionJarException;
import io.github.mfuerstenau.leia.core.ExtensionJars;
import io.github.mfuerstenau.leia.core.JarComponents;
import io.github.mfuerstenau.leia.runtime.Extensions;
import java.nio.file.Path;
import java.util.List;

/**
 * leia-core's lists, for the indexes the server opens with leia-core, until it routes every index to
 * a runtime (ADR 0065, ADR 0068); as leia-lucene10's {@code Lucene10Extensions}.
 */
final class CoreExtensions implements Extensions {

    @Override
    public void check(ClassLoader loader) throws InvalidJarException {
        try {
            ExtensionJars.check(loader);
        } catch (ExtensionJarException e) {
            throw new InvalidJarException(e.getMessage(), e);
        }
    }

    @Override
    public Added reload(ClassLoader loader, List<Path> jars) throws InvalidJarException {
        JarComponents added;
        try {
            added = ExtensionJars.reload(loader, jars);
        } catch (ExtensionJarException e) {
            throw new InvalidJarException(e.getMessage(), e);
        }
        return new Added(
                added.analyzers(),
                added.charFilters(),
                added.tokenizers(),
                added.tokenFilters(),
                added.codecs(),
                added.postingsFormats(),
                added.docValuesFormats(),
                added.knnVectorsFormats());
    }
}
