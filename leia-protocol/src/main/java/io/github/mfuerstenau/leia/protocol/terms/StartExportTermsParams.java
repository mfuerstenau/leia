package io.github.mfuerstenau.leia.protocol.terms;

import java.util.Optional;

/**
 * Params of {@code startExportTerms} (ADR 0022).
 *
 * @param field the field whose terms to write
 * @param path the absolute path of the file to write; it must lie outside every index directory
 * @param delimiter what separates a term from its document frequency; absent means {@link
 *     TermDelimiter#TAB}
 * @param overwrite whether to replace a file that exists; absent means false
 * @param format the line layout; absent means {@link TermExportFormat#LUKE} (ADR 0094)
 */
public record StartExportTermsParams(
        String field,
        String path,
        Optional<TermDelimiter> delimiter,
        Optional<Boolean> overwrite,
        Optional<TermExportFormat> format) {

    /** Writes the terms of {@code field} to a new file at {@code path}, separated by tabs. */
    public StartExportTermsParams(String field, String path) {
        this(field, path, Optional.empty(), Optional.empty(), Optional.empty());
    }
}
