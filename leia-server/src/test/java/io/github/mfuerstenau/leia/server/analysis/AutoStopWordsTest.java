package io.github.mfuerstenau.leia.server.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mfuerstenau.leia.server.job.ScanProgress;
import io.github.mfuerstenau.leia.server.terms.TermExport;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

/** The stop words of fields, found from their terms and document frequencies (ADR 0039, ADR 0061). */
class AutoStopWordsTest {

    @Test
    void findsTheTermsInMoreDocumentsThanTheThreshold() throws Exception {
        Map<String, TermExport.TermSource> fields = new LinkedHashMap<>();
        fields.put("title", terms(Map.of("a", 4, "title", 10)));
        fields.put("body", terms(Map.of("dog", 3, "fox", 7, "the", 6)));

        AutoStopWords found = AutoStopWords.find(fields, 5, 1000, ScanProgress.NONE);

        assertThat(found.maxDocFreq()).isEqualTo(5);
        // The fields by name, each with its words in term order.
        assertThat(found.words())
                .containsExactly(Map.entry("body", List.of("fox", "the")), Map.entry("title", List.of("title")));
    }

    @Test
    void failsAboveTheMostWordsAndCancels() throws Exception {
        Map<String, Integer> ids = new LinkedHashMap<>();
        for (int i = 0; i < 10; i++) {
            ids.put("doc" + i, 1);
        }
        Map<String, TermExport.TermSource> fields = Map.of("id", terms(ids));

        assertThatThrownBy(() -> AutoStopWords.find(fields, 0, 9, ScanProgress.NONE))
                .isInstanceOf(AutoStopWords.TooManyWordsException.class)
                .hasMessage("field 'id' has more than 9 stop words; raise the threshold");
        assertThat(AutoStopWords.find(fields, 0, 10, ScanProgress.NONE).words().get("id"))
                .hasSize(10);

        List<Long> reports = new ArrayList<>();
        AutoStopWords.find(fields, 0, 10, reports::add);
        assertThat(reports).containsExactly(10L);
        assertThatThrownBy(() -> AutoStopWords.find(Map.of("id", terms(many())), 0, 2000, scanned -> {
                    throw new CancellationException();
                }))
                .isInstanceOf(CancellationException.class);
    }

    @Test
    void computesTheThresholdFromAFractionOfTheDocuments() {
        assertThat(new AutoStopWords.Threshold.MaxPercentDocs(0.4).maxDocFreq(10))
                .isEqualTo(4);
        assertThat(new AutoStopWords.Threshold.MaxDocFreq(3).maxDocFreq(10)).isEqualTo(3);
        assertThatThrownBy(() -> new AutoStopWords.Threshold.MaxPercentDocs(1.5).maxDocFreq(10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AutoStopWords.Threshold.MaxDocFreq(-1).maxDocFreq(10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** More terms than one progress report covers. */
    private static Map<String, Integer> many() {
        Map<String, Integer> terms = new LinkedHashMap<>();
        for (int i = 0; i < AutoStopWords.PROGRESS_INTERVAL + 1; i++) {
            terms.put(String.format("t%05d", i), 1);
        }
        return terms;
    }

    /** The terms with their document frequencies, in term order, each in a larger array. */
    private static TermExport.TermSource terms(Map<String, Integer> docFreqs) {
        return sink -> {
            for (String term : docFreqs.keySet().stream().sorted().toList()) {
                byte[] bytes = ("<" + term + ">").getBytes(StandardCharsets.UTF_8);
                sink.term(bytes, 1, bytes.length - 2, docFreqs.get(term), 0);
            }
        };
    }
}
