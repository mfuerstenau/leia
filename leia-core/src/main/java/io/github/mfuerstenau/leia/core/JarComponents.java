package io.github.mfuerstenau.leia.core;

import java.util.List;

/**
 * The names that jars added to Lucene's lists and to the presets (ADR 0024), each sorted, ignoring
 * case. A name that was taken before isn't one of them.
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
public record JarComponents(
        List<String> analyzers,
        List<String> charFilters,
        List<String> tokenizers,
        List<String> tokenFilters,
        List<String> codecs,
        List<String> postingsFormats,
        List<String> docValuesFormats,
        List<String> knnVectorsFormats) {

    public static final JarComponents NONE =
            new JarComponents(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

    public JarComponents {
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
