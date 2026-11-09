package io.github.mfuerstenau.leia.server;

import static io.github.mfuerstenau.leia.server.RealBackend.BM25;
import static io.github.mfuerstenau.leia.server.RealBackend.STANDARD;
import static io.github.mfuerstenau.leia.server.RealBackend.classic;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.search.DocumentHighlight;
import io.github.mfuerstenau.leia.protocol.search.FieldMatches;
import io.github.mfuerstenau.leia.protocol.search.HighlightOptions;
import io.github.mfuerstenau.leia.protocol.search.HighlightPassage;
import io.github.mfuerstenau.leia.protocol.search.Hit;
import io.github.mfuerstenau.leia.protocol.search.HitHighlight;
import io.github.mfuerstenau.leia.protocol.search.OffsetSource;
import io.github.mfuerstenau.leia.protocol.search.SearchQuery;
import io.github.mfuerstenau.leia.protocol.search.SearchResults;
import io.github.mfuerstenau.leia.protocol.search.StartHighlightParams;
import io.github.mfuerstenau.leia.protocol.search.StartSearchParams;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Highlighting on an index a runtime opened (ADR 0063), with a text field {@code body} whose
 * postings have offsets and in which some documents have {@code beta}.
 */
final class RuntimeHighlights {

    private static final HighlightOptions OPTIONS = new HighlightOptions(2, false, STANDARD);

    private RuntimeHighlights() {}

    /**
     * A search for {@code body:beta} marks {@code beta} in a passage of {@code body} of each hit, with
     * its offsets from the postings; {@code startHighlight} marks it in the document, in {@code body}
     * alone or among all its stored text fields. Wrong params fail at once.
     */
    static void highlightsTheMatches(RealBackend backend, int maxDoc) throws Exception {
        SearchResults results = backend.search(new StartSearchParams(
                classic("beta"), BM25, List.of(), 0, 10, true, Optional.empty(), Optional.of(OPTIONS)));
        assertThat(results.hits()).isNotEmpty().allSatisfy(hit -> {
            HitHighlight body = body(hit);
            assertThat(body.offsetSource()).isEqualTo(OffsetSource.POSTINGS);
            assertThat(body.passages()).singleElement().satisfies(passage -> {
                assertThat(passage.valueIndex()).isZero();
                assertThat(passage.snippet().text()).contains("beta");
                assertThat(marked(passage)).containsExactly("beta");
            });
        });

        int docId = results.hits().getFirst().docId();
        DocumentHighlight all = highlight(backend, classic("beta"), docId, null);
        assertThat(all.fields()).extracting(FieldMatches::field).contains("id", "body");
        DocumentHighlight body = highlight(backend, classic("beta"), docId, "body");
        assertThat(body.fields()).singleElement().satisfies(matches -> {
            assertThat(matches.field()).isEqualTo("body");
            assertThat(matches.offsetSource()).isEqualTo(OffsetSource.POSTINGS);
            assertThat(matches.truncated()).isFalse();
            assertThat(matches.values()).singleElement().satisfies(value -> {
                assertThat(value.matchCount()).isEqualTo(1);
                // "beta" comes last in the value, in UTF-8 bytes.
                String text = bodyText(results.hits().getFirst());
                int end = text.getBytes(StandardCharsets.UTF_8).length;
                assertThat(value.spans())
                        .singleElement()
                        .satisfies(span ->
                                assertThat(List.of(span.start(), span.end())).isEqualTo(List.of(end - 4, end)));
            });
        });
        assertThat(all.fields()).contains(body.fields().getFirst());

        backend.fails(
                Methods.START_HIGHLIGHT,
                new StartHighlightParams(classic("beta"), docId, Optional.of("nothing"), OPTIONS),
                ErrorCode.FIELD_NOT_FOUND);
        backend.fails(
                Methods.START_HIGHLIGHT,
                new StartHighlightParams(classic("beta"), maxDoc, Optional.empty(), OPTIONS),
                ErrorCode.DOC_NOT_FOUND);
        backend.fails(
                Methods.START_SEARCH,
                new StartSearchParams(
                        classic("beta"),
                        BM25,
                        List.of(),
                        0,
                        10,
                        true,
                        Optional.empty(),
                        Optional.of(new HighlightOptions(0, false, STANDARD))),
                ErrorCode.INVALID_PARAMS);
    }

    /** A query without terms, such as a KNN query, marks nothing in a document. */
    static void marksNothingWithoutTerms(RealBackend backend, SearchQuery query, int docId) throws Exception {
        assertThat(highlight(backend, query, docId, null).fields()).isEmpty();
    }

    private static DocumentHighlight highlight(
            RealBackend backend, SearchQuery query, int docId, @Nullable String field) throws Exception {
        return backend.highlight(new StartHighlightParams(query, docId, Optional.ofNullable(field), OPTIONS));
    }

    private static HitHighlight body(Hit hit) {
        return hit.highlights().stream()
                .filter(highlight -> highlight.field().equals("body"))
                .findFirst()
                .orElseThrow();
    }

    private static String bodyText(Hit hit) {
        return hit.fields().stream()
                .filter(field -> field.name().equals("body"))
                .findFirst()
                .orElseThrow()
                .values()
                .getFirst()
                .value();
    }

    /** The marked text of {@code passage}. */
    private static List<String> marked(HighlightPassage passage) {
        byte[] text = passage.snippet().text().getBytes(StandardCharsets.UTF_8);
        int start = passage.snippet().start();
        return passage.spans().stream()
                .map(span -> new String(
                        Arrays.copyOfRange(text, span.start() - start, span.end() - start), StandardCharsets.UTF_8))
                .toList();
    }
}
