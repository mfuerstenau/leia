package io.github.mfuerstenau.leia.core;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ServiceConfigurationError;
import java.util.stream.Stream;
import org.apache.lucene.analysis.CharFilterFactory;
import org.apache.lucene.analysis.TokenFilterFactory;
import org.apache.lucene.analysis.TokenizerFactory;
import org.apache.lucene.codecs.Codec;
import org.apache.lucene.codecs.DocValuesFormat;
import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.codecs.PostingsFormat;

/**
 * Jars with analysis components, codecs or formats of their own: Lucene's other analysis modules, or
 * the user's factories, analyzers, codecs and formats (ADR 0016, ADR 0018). Lucene's lists of
 * factories, codecs and formats are global to the JVM, so these jars are too.
 */
public final class ExtensionJars {

    private static final String SUFFIX = ".jar";

    private static ClassLoader classLoader = ExtensionJars.class.getClassLoader();
    private static List<Path> jars = List.of();

    private ExtensionJars() {}

    /**
     * Loads the jars at {@code paths}, through a class loader whose parent is the one loaded before,
     * and adds their char filters, tokenizers, token filters, codecs, postings formats, doc values
     * formats and KNN vectors formats to Lucene's lists. A factory, codec or format whose name is
     * taken is ignored. A directory stands for the jars directly in it.
     *
     * @return the jars as absolute paths, in the order they were given, a directory's sorted by name
     * @throws ExtensionJarException if a path is neither a jar nor a directory with one, or the
     *     factories, codecs or formats can't be loaded
     */
    public static synchronized List<Path> load(List<Path> paths) throws ExtensionJarException {
        List<Path> loaded = new ArrayList<>();
        for (Path path : paths) {
            loaded.addAll(jars(path.toAbsolutePath().normalize()));
        }
        if (loaded.isEmpty()) {
            return List.of();
        }
        URL[] urls = new URL[loaded.size()];
        for (int i = 0; i < urls.length; i++) {
            try {
                urls[i] = loaded.get(i).toUri().toURL();
            } catch (MalformedURLException e) {
                throw new ExtensionJarException("not a jar: " + loaded.get(i), e);
            }
        }
        URLClassLoader loader = new URLClassLoader(urls, classLoader);
        try {
            CharFilterFactory.reloadCharFilters(loader);
            TokenizerFactory.reloadTokenizers(loader);
            TokenFilterFactory.reloadTokenFilters(loader);
        } catch (ServiceConfigurationError | LinkageError e) {
            // A NoClassDefFoundError is most often a dependency that wasn't given.
            throw new ExtensionJarException("cannot load the analysis factories: " + e, e);
        }
        try {
            Codec.reloadCodecs(loader);
            PostingsFormat.reloadPostingsFormats(loader);
            DocValuesFormat.reloadDocValuesFormats(loader);
            KnnVectorsFormat.reloadKnnVectorsFormat(loader);
        } catch (ServiceConfigurationError | LinkageError e) {
            throw new ExtensionJarException("cannot load the codecs and formats: " + e, e);
        }
        classLoader = loader;
        jars = Stream.concat(jars.stream(), loaded.stream()).toList();
        return List.copyOf(loaded);
    }

    /** The class loader that sees every loaded jar: the one that analysis loads classes by name with. */
    public static synchronized ClassLoader classLoader() {
        return classLoader;
    }

    /** The loaded jars, in the order they were loaded. */
    static synchronized List<Path> jars() {
        return jars;
    }

    private static List<Path> jars(Path path) throws ExtensionJarException {
        if (Files.isDirectory(path)) {
            List<Path> jars;
            try (Stream<Path> files = Files.list(path)) {
                jars = files.filter(file -> isJar(file) && Files.isRegularFile(file))
                        .sorted()
                        .toList();
            } catch (IOException e) {
                throw new ExtensionJarException("cannot read " + path + ": " + e.getMessage(), e);
            }
            if (jars.isEmpty()) {
                throw new ExtensionJarException("no jars in " + path, null);
            }
            return jars;
        }
        if (!Files.exists(path)) {
            throw new ExtensionJarException("no such jar or directory: " + path, null);
        }
        if (!isJar(path) || !Files.isRegularFile(path)) {
            throw new ExtensionJarException("not a jar: " + path, null);
        }
        return List.of(path);
    }

    private static boolean isJar(Path file) {
        Path name = file.getFileName();
        return name != null && name.toString().toLowerCase(Locale.ROOT).endsWith(SUFFIX);
    }
}
