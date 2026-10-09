package io.github.mfuerstenau.leia.protocol.jars;

import java.util.List;

/**
 * The names that jars added (ADR 0024), each sorted, ignoring case. A name that was taken before
 * isn't one of them, so a jar that brought nothing new adds none.
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
        List<String> knnVectorsFormats) {}
