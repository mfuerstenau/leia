package io.github.mfuerstenau.leia.core;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Loads jars into Lucene's lists as leia-server's {@code JarChain} does (ADR 0068): through a class
 * loader whose parent is the one loaded before, checked before anything changes. For the tests in
 * the {@code jarsTest} JVM.
 */
final class TestJars {

    private TestJars() {}

    /** Loads the jars at {@code paths}; a directory stands for the jars in it, sorted by name. */
    static JarComponents load(List<Path> paths) throws ExtensionJarException, IOException {
        List<Path> fresh = new ArrayList<>();
        for (Path path : paths) {
            if (Files.isDirectory(path)) {
                try (Stream<Path> files = Files.list(path)) {
                    files.filter(file -> file.getFileName().toString().endsWith(".jar"))
                            .sorted()
                            .forEach(file -> fresh.add(file.toAbsolutePath()));
                }
            } else {
                fresh.add(path.toAbsolutePath());
            }
        }
        URL[] urls = new URL[fresh.size()];
        for (int i = 0; i < urls.length; i++) {
            urls[i] = fresh.get(i).toUri().toURL();
        }
        URLClassLoader loader = new URLClassLoader(urls, ExtensionJars.classLoader());
        try {
            ExtensionJars.check(loader);
        } catch (ExtensionJarException e) {
            loader.close();
            throw e;
        }
        return ExtensionJars.reload(
                loader,
                Stream.concat(ExtensionJars.jars().stream(), fresh.stream()).toList());
    }
}
