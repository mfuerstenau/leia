package io.github.mfuerstenau.leia.server;

import static io.github.mfuerstenau.leia.server.RealBackend.STANDARD;
import static io.github.mfuerstenau.leia.server.RealBackend.classic;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.analysis.Analyzer;
import io.github.mfuerstenau.leia.protocol.analysis.AutoStopWords;
import io.github.mfuerstenau.leia.protocol.analysis.FieldStopWords;
import io.github.mfuerstenau.leia.protocol.analysis.GetAutoStopWordsResult;
import io.github.mfuerstenau.leia.protocol.analysis.StartAutoStopWordsParams;
import io.github.mfuerstenau.leia.protocol.index.GetIndexStatsParams;
import io.github.mfuerstenau.leia.protocol.index.GetIndexStatsResult;
import io.github.mfuerstenau.leia.protocol.index.IndexField;
import io.github.mfuerstenau.leia.protocol.index.IndexOptions;
import io.github.mfuerstenau.leia.protocol.terms.GetTermsParams;
import io.github.mfuerstenau.leia.protocol.terms.GetTermsResult;
import io.github.mfuerstenau.leia.protocol.terms.TermEntry;
import io.github.mfuerstenau.leia.runtime.support.Optionals;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import org.jspecify.annotations.Nullable;

/** Auto stop words on an index of the runtimes' writers (ADR 0061), with any text field. */
final class RuntimeAutoStopWords {

    private RuntimeAutoStopWords() {}

    /**
     * Finds the stop words of {@code field} and compares them with its terms: every term above 0, the
     * terms of the highest document frequency just below it, and the threshold of a fraction from
     * the live documents. Without fields every indexed field is scanned; an unknown field fails.
     */
    static void findsTheWords(RealBackend backend, String field) throws Exception {
        GetTermsResult page =
                backend.call(Methods.GET_TERMS, new GetTermsParams(field, Optional.empty(), GetTermsParams.MAX_LIMIT));
        assertThat(page.next()).isEmpty();
        List<TermEntry> terms = page.terms();
        assertThat(terms).isNotEmpty();

        GetAutoStopWordsResult every = find(backend, List.of(field), 0, null);
        assertThat(every.stopWords())
                .hasValue(new AutoStopWords(
                        0,
                        List.of(new FieldStopWords(
                                field, terms.stream().map(TermEntry::term).toList()))));
        assertThat(every.termsScanned()).isEqualTo(terms.size());

        int top = terms.stream().mapToInt(TermEntry::docFreq).max().orElseThrow();
        assertThat(find(backend, List.of(field), top - 1, null).stopWords())
                .hasValue(new AutoStopWords(
                        top - 1,
                        List.of(new FieldStopWords(
                                field,
                                terms.stream()
                                        .filter(term -> term.docFreq() == top)
                                        .map(TermEntry::term)
                                        .toList()))));

        GetIndexStatsResult stats = backend.call(Methods.GET_INDEX_STATS, new GetIndexStatsParams());
        AutoStopWords all = find(backend, List.of(), null, 1.0).stopWords().orElseThrow();
        assertThat(all.maxDocFreq()).isEqualTo(stats.numDocs());
        assertThat(all.fields())
                .extracting(FieldStopWords::field)
                .containsExactlyElementsOf(stats.fields().stream()
                        .filter(info -> info.indexOptions() != IndexOptions.NONE)
                        .map(IndexField::name)
                        .sorted()
                        .toList());

        backend.fails(
                Methods.START_AUTO_STOP_WORDS,
                new StartAutoStopWordsParams(List.of("nothing"), OptionalInt.of(0), OptionalDouble.empty()),
                ErrorCode.FIELD_NOT_FOUND);
    }

    /**
     * A classic search for {@code word} in {@code field} finds it, and none once it is an auto stop
     * word of the field.
     */
    static void searchesWithoutTheWords(RealBackend backend, int maxDoc, String field, String word) throws Exception {
        String expression = field + ":" + word;
        assertThat(backend.search(classic(expression), maxDoc).totalHits()).isPositive();

        Analyzer withoutWord = new Analyzer(
                STANDARD.standard(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                List.of(new FieldStopWords(field, List.of(word))));
        assertThat(backend.search(classic(expression, withoutWord), maxDoc).totalHits())
                .isZero();
    }

    private static GetAutoStopWordsResult find(
            RealBackend backend, List<String> fields, @Nullable Integer maxDocFreq, @Nullable Double maxPercentDocs)
            throws Exception {
        return backend.job(
                Methods.START_AUTO_STOP_WORDS,
                new StartAutoStopWordsParams(
                        fields, Optionals.optionalInt(maxDocFreq), Optionals.optionalDouble(maxPercentDocs)),
                Methods.GET_AUTO_STOP_WORDS,
                result -> result.stopWords().map(_ -> result));
    }
}
