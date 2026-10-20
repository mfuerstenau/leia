package io.github.mfuerstenau.leia.runtime;

import java.nio.file.Path;
import java.util.List;

/**
 * Jars with analysis components, codecs or formats of their own, compiled against the runtime's
 * Lucene (ADR 0068). leia builds the class loader of the jars, a child of the one loaded before; the
 * runtime reloads its lists of factories, codecs and formats from it. The lists are global to the
 * runtime's Lucene, so this is too.
 */
public interface Extensions {

    /**
     * Builds private copies of its lists from {@code loader}, changing nothing, so that a jar that
     * fails is found before anything changes.
     *
     * @throws InvalidJarException if the factories, codecs or formats can't be loaded, such as for a
     *     missing dependency
     */
    void check(ClassLoader loader) throws InvalidJarException;

    /**
     * Reloads its lists from {@code loader}, which sees the jars over those loaded before, and finds
     * out what that added. A factory, codec or format whose name is taken is ignored.
     *
     * @param jars every jar that {@code loader} sees, in the order they were loaded, for the presets
     * @return the names that weren't there before
     * @throws InvalidJarException as {@link #check} does; the names of the lists reloaded before the
     *     one that failed stay
     */
    Added reload(ClassLoader loader, List<Path> jars) throws InvalidJarException;

    /**
     * The names that jars added to the lists and to the presets, each sorted, ignoring case. A name
     * that was taken before isn't one of them.
     *
     * @param analyzers the class names of the new preset analyzers
     * @param charFilters the SPI names of the new char filter factories
     * @param tokenizers the SPI names of the new tokenizer factories
     * @param tokenFilters the SPI names of the new token filter factories
     * @param codecs the names of the new codecs
     * @param postingsFormats the names of the new postings formats
     * @param docValuesFormats the names of the new doc values formats
     * @param knnVectorsFormats the names of the new KNN vectors formats
     */
    record Added(
            List<String> analyzers,
            List<String> charFilters,
            List<String> tokenizers,
            List<String> tokenFilters,
            List<String> codecs,
            List<String> postingsFormats,
            List<String> docValuesFormats,
            List<String> knnVectorsFormats) {

        public static final Added NONE =
                new Added(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

        public Added {
            analyzers = List.copyOf(analyzers);
            charFilters = List.copyOf(charFilters);
            tokenizers = List.copyOf(tokenizers);
            tokenFilters = List.copyOf(tokenFilters);
            codecs = List.copyOf(codecs);
            postingsFormats = List.copyOf(postingsFormats);
            docValuesFormats = List.copyOf(docValuesFormats);
            knnVectorsFormats = List.copyOf(knnVectorsFormats);
        }
    }

    /** Jars whose factories, codecs or formats can't be loaded, such as for a missing dependency. */
    final class InvalidJarException extends Exception {
        private static final long serialVersionUID = 1L;

        public InvalidJarException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
