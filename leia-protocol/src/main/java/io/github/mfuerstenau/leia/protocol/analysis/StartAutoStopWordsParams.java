package io.github.mfuerstenau.leia.protocol.analysis;

import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * Params of {@code startAutoStopWords} (ADR 0039), as the arguments of Lucene's {@code
 * QueryAutoStopWordAnalyzer}: exactly one of {@code maxDocFreq} and {@code maxPercentDocs} is
 * present.
 *
 * @param fields the indexed fields to scan; none for every indexed field
 * @param maxDocFreq a term in more documents than this, at least 0, is a stop word
 * @param maxPercentDocs a term in more than this fraction of the documents, from 0 to 1, is a stop
 *     word; Lucene's default is 0.4
 */
public record StartAutoStopWordsParams(List<String> fields, OptionalInt maxDocFreq, OptionalDouble maxPercentDocs) {

    /** The most stop words of one field; with more, the job fails and asks for a higher threshold. */
    public static final int MAX_WORDS = 10_000;
}
