package io.github.mfuerstenau.leia.server.search;

import io.github.mfuerstenau.leia.core.DocumentHighlight;
import io.github.mfuerstenau.leia.core.SearchResults.FieldHighlight;
import io.github.mfuerstenau.leia.core.StoredScan;
import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.search.FieldMatches;
import io.github.mfuerstenau.leia.protocol.search.HighlightOptions;
import io.github.mfuerstenau.leia.protocol.search.HighlightPassage;
import io.github.mfuerstenau.leia.protocol.search.HitHighlight;
import io.github.mfuerstenau.leia.protocol.search.MatchSpan;
import io.github.mfuerstenau.leia.protocol.search.OffsetSource;
import io.github.mfuerstenau.leia.protocol.search.SearchQuery;
import io.github.mfuerstenau.leia.protocol.search.Snippet;
import io.github.mfuerstenau.leia.protocol.search.ValueHighlight;
import io.github.mfuerstenau.leia.server.RpcException;
import java.util.List;

/**
 * Turns the highlight options of {@code startSearch} and {@code startHighlight} into core's, and back
 * (ADR 0042), also for a runtime (ADR 0063).
 */
public final class Highlighting {

    private Highlighting() {}

    /**
     * Whether {@code query} has terms to highlight: a doc values or KNN query has none, so it is not
     * highlighted at all.
     */
    public static boolean hasTerms(SearchQuery query) {
        return query.docValues().isEmpty() && query.knn().isEmpty();
    }

    /**
     * Checks {@code maxPassages}.
     *
     * @throws RpcException {@code INVALID_PARAMS} if it is not from 1 to {@link HighlightOptions#MAX_PASSAGES}
     */
    public static void checkPassages(HighlightOptions options) throws RpcException {
        int maxPassages = options.maxPassages();
        if (maxPassages < 1 || maxPassages > HighlightOptions.MAX_PASSAGES) {
            throw new RpcException(
                    ErrorCode.INVALID_PARAMS,
                    "Invalid params: maxPassages must be between 1 and " + HighlightOptions.MAX_PASSAGES + ", not "
                            + maxPassages);
        }
    }

    public static HitHighlight toHighlight(FieldHighlight highlight) {
        return new HitHighlight(
                highlight.field(),
                toSource(highlight.offsetSource()),
                highlight.passages().stream()
                        .map(passage -> new HighlightPassage(
                                passage.valueIndex(),
                                new Snippet(passage.start(), passage.text()),
                                toSpans(passage.spans()),
                                passage.score()))
                        .toList());
    }

    public static io.github.mfuerstenau.leia.protocol.search.DocumentHighlight toHighlight(
            DocumentHighlight highlight) {
        return new io.github.mfuerstenau.leia.protocol.search.DocumentHighlight(
                highlight.fields().stream().map(Highlighting::toMatches).toList());
    }

    private static FieldMatches toMatches(DocumentHighlight.FieldMatches matches) {
        return new FieldMatches(
                matches.field(),
                toSource(matches.offsetSource()),
                matches.values().stream()
                        .map(value ->
                                new ValueHighlight(value.valueIndex(), value.matchCount(), toSpans(value.spans())))
                        .toList(),
                matches.truncated());
    }

    private static List<MatchSpan> toSpans(List<StoredScan.Span> spans) {
        return spans.stream()
                .map(span -> new MatchSpan(span.start(), span.end()))
                .toList();
    }

    private static OffsetSource toSource(io.github.mfuerstenau.leia.core.OffsetSource source) {
        return OffsetSource.valueOf(source.name());
    }
}
