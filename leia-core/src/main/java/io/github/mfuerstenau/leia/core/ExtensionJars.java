package io.github.mfuerstenau.leia.core;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.lucene.analysis.AnalysisSPILoader;
import org.apache.lucene.analysis.CharFilterFactory;
import org.apache.lucene.analysis.TokenFilterFactory;
import org.apache.lucene.analysis.TokenizerFactory;
import org.apache.lucene.codecs.Codec;
import org.apache.lucene.codecs.DocValuesFormat;
import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.codecs.PostingsFormat;
import org.apache.lucene.util.NamedSPILoader;

/**
 * Lucene's lists of factories, codecs and formats, reloaded with jars that bring their own: Lucene's
 * other analysis modules, or the user's factories, analyzers, codecs and formats (ADR 0016, ADR 0018,
 * ADR 0024). leia-server builds the class loader of the jars (ADR 0068). Lucene's lists are global to
 * the JVM, so this is too.
 */
public final class ExtensionJars {

    // Written while holding the class's lock, read without it: PresetAnalyzers reads them while
    // holding its own lock, which reload() takes while holding this one. The jars are written first,
    // so a reader that sees a loader also sees its jars.
    // An immutable value, published through volatile (ADR 0088)
    @SuppressWarnings("java:S3077")
    private static volatile ClassLoader classLoader = ExtensionJars.class.getClassLoader();

    @SuppressWarnings("java:S3077")
    private static volatile List<Path> jars = List.of();

    private ExtensionJars() {}

    /**
     * Builds private copies of Lucene's lists from {@code loader}, changing nothing, so that a jar
     * that fails is found before anything changes.
     *
     * @throws ExtensionJarException if the factories, codecs or formats can't be loaded
     */
    public static void check(ClassLoader loader) throws ExtensionJarException {
        reload(loader, true);
    }

    /**
     * Reloads Lucene's char filters, tokenizers, token filters, codecs, postings formats, doc values
     * formats and KNN vectors formats from {@code loader}, and finds out what that added (ADR 0024):
     * the names of the lists and the presets that weren't there before. A factory, codec or format
     * whose name is taken is ignored.
     *
     * @param loader the loader of the jars, a child of {@link #classLoader()}
     * @param jars every jar that {@code loader} sees, in the order they were loaded
     * @throws ExtensionJarException as {@link #check} does; the names of the lists reloaded before
     *     the one that failed stay
     */
    public static synchronized JarComponents reload(ClassLoader loader, List<Path> jars) throws ExtensionJarException {
        Names before = Names.now();
        reload(loader, false);
        ExtensionJars.jars = List.copyOf(jars);
        classLoader = loader;
        return before.added(Names.now());
    }

    /** The class loader that sees every loaded jar: the one that analysis loads classes by name with. */
    public static ClassLoader classLoader() {
        return classLoader;
    }

    /** The loaded jars, as absolute paths, in the order they were loaded. */
    public static List<Path> jars() {
        return jars;
    }

    /** Reloads Lucene's seven lists from {@code loader}, or only builds copies of them in a dry run. */
    private static void reload(ClassLoader loader, boolean dryRun) throws ExtensionJarException {
        try {
            if (dryRun) {
                new AnalysisSPILoader<>(CharFilterFactory.class, loader);
                new AnalysisSPILoader<>(TokenizerFactory.class, loader);
                new AnalysisSPILoader<>(TokenFilterFactory.class, loader);
            } else {
                CharFilterFactory.reloadCharFilters(loader);
                TokenizerFactory.reloadTokenizers(loader);
                TokenFilterFactory.reloadTokenFilters(loader);
            }
        } catch (ServiceConfigurationError | LinkageError | RuntimeException e) {
            // A NoClassDefFoundError is most often a dependency that wasn't given.
            throw new ExtensionJarException("cannot load the analysis factories: " + e, e);
        }
        try {
            if (dryRun) {
                new NamedSPILoader<>(Codec.class, loader);
                new NamedSPILoader<>(PostingsFormat.class, loader);
                new NamedSPILoader<>(DocValuesFormat.class, loader);
                new NamedSPILoader<>(KnnVectorsFormat.class, loader);
            } else {
                Codec.reloadCodecs(loader);
                PostingsFormat.reloadPostingsFormats(loader);
                DocValuesFormat.reloadDocValuesFormats(loader);
                KnnVectorsFormat.reloadKnnVectorsFormat(loader);
            }
        } catch (ServiceConfigurationError | LinkageError | RuntimeException e) {
            throw new ExtensionJarException("cannot load the codecs and formats: " + e, e);
        }
    }

    /** The names in Lucene's lists and the presets at one moment. */
    private record Names(
            Set<String> analyzers,
            Set<String> charFilters,
            Set<String> tokenizers,
            Set<String> tokenFilters,
            Set<String> codecs,
            Set<String> postingsFormats,
            Set<String> docValuesFormats,
            Set<String> knnVectorsFormats) {

        static Names now() {
            return new Names(
                    PresetAnalyzers.list().stream()
                            .map(AnalysisComponents.AvailableAnalyzer::className)
                            .collect(Collectors.toSet()),
                    CharFilterFactory.availableCharFilters(),
                    TokenizerFactory.availableTokenizers(),
                    TokenFilterFactory.availableTokenFilters(),
                    Codec.availableCodecs(),
                    PostingsFormat.availablePostingsFormats(),
                    DocValuesFormat.availableDocValuesFormats(),
                    KnnVectorsFormat.availableKnnVectorsFormats());
        }

        /** The names in {@code after} that aren't in this. */
        JarComponents added(Names after) {
            return new JarComponents(
                    added(analyzers, after.analyzers),
                    added(charFilters, after.charFilters),
                    added(tokenizers, after.tokenizers),
                    added(tokenFilters, after.tokenFilters),
                    added(codecs, after.codecs),
                    added(postingsFormats, after.postingsFormats),
                    added(docValuesFormats, after.docValuesFormats),
                    added(knnVectorsFormats, after.knnVectorsFormats));
        }

        private static List<String> added(Set<String> before, Set<String> after) {
            return after.stream()
                    .filter(name -> !before.contains(name))
                    .sorted(String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder()))
                    .toList();
        }
    }
}
