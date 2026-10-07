package io.github.mfuerstenau.leia.protocol.search;

/**
 * An expression for Lucene's classic {@code QueryParser}, analyzed with {@code StandardAnalyzer}
 * (ADR 0010, ADR 0011).
 *
 * @param expression the query in the classic syntax, such as {@code title:fox AND body:qu*}
 * @param defaultField the field of terms that name none
 * @param defaultOperator how clauses without an operator are combined
 * @param options the options both parsers have
 * @param fuzzyMaxEdits the edit distance of fuzzy terms that give none, 0 to 2
 * @param splitOnWhitespace whether whitespace splits the text before it is analyzed
 * @param autoGeneratePhraseQueries whether text that analyzes to several terms becomes a phrase;
 *     needs {@code splitOnWhitespace}
 */
public record ClassicQuery(
        String expression,
        String defaultField,
        QueryOperator defaultOperator,
        ParserOptions options,
        int fuzzyMaxEdits,
        boolean splitOnWhitespace,
        boolean autoGeneratePhraseQueries) {}
