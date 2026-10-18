package io.github.mfuerstenau.leia.server;

import static io.github.mfuerstenau.leia.server.RealBackend.classic;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.document.BytesValue;
import io.github.mfuerstenau.leia.protocol.filter.FilterMatch;
import io.github.mfuerstenau.leia.protocol.filter.FilterPattern;
import io.github.mfuerstenau.leia.protocol.filter.FilterResult;
import io.github.mfuerstenau.leia.protocol.filter.StartFilterParams;
import io.github.mfuerstenau.leia.protocol.filter.StepFilterParams;
import io.github.mfuerstenau.leia.protocol.search.StartStoredScanParams;
import io.github.mfuerstenau.leia.protocol.search.StoredScanHit;
import io.github.mfuerstenau.leia.protocol.terms.GetPostingsParams;
import io.github.mfuerstenau.leia.protocol.terms.GetPostingsResult;
import io.github.mfuerstenau.leia.protocol.terms.Posting;
import io.github.mfuerstenau.leia.protocol.terms.PostingsFilter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeSet;

/**
 * The filter on an index of the runtimes' writers (ADR 0056): documents with a {@code body} "alpha …
 * document <id> …", beta in the odd ids, and deleted ones. What should match is read from the index
 * with a stored-values scan, since the indexes differ in their documents and deletions.
 */
final class RuntimeFilters {

    private static final BytesValue ALPHA = new BytesValue("alpha", false);

    private RuntimeFilters() {}

    /**
     * Filters by a query and by a pattern, with and without the deleted documents, steps through a
     * filter and reads postings through it. A runtime that can't match deleted documents by query
     * refuses that, unless {@code deletedByQuery}.
     */
    static void filtersTheDocuments(RealBackend backend, boolean deletedByQuery) throws Exception {
        TreeSet<Integer> beta = scanned(backend, "\\bbeta\\b", false);
        TreeSet<Integer> alpha = scanned(backend, "\\balpha\\b", false);
        TreeSet<Integer> alphaWithDeleted = scanned(backend, "\\balpha\\b", true);
        assertThat(beta).isNotEmpty();
        assertThat(alphaWithDeleted).hasSizeGreaterThan(alpha.size());

        FilterResult byQuery =
                backend.filter(new StartFilterParams(Optional.of(classic("beta")), Optional.empty(), false));
        assertThat(byQuery.query()).isEqualTo("body:beta");
        assertThat(byQuery.rewrittenQuery()).isEqualTo("body:beta");
        assertThat(byQuery.matchCount()).isEqualTo(beta.size());
        assertThat(byQuery.timedOutValues()).isEmpty();
        assertThat(matches(backend, byQuery)).containsExactlyElementsOf(beta);
        assertThat(backend.call(Methods.STEP_FILTER, new StepFilterParams(byQuery.filterId(), Integer.MAX_VALUE, false))
                        .match())
                .contains(new FilterMatch(beta.last(), beta.size() - 1));

        // The postings of alpha through the filter: marked, or only those in it, page by page.
        List<Posting> marked = postings(backend, byQuery.filterId(), false, GetPostingsParams.MAX_LIMIT);
        assertThat(marked).extracting(Posting::docId).containsAll(alpha);
        assertThat(marked)
                .allSatisfy(posting -> assertThat(posting.inFilter()).contains(beta.contains(posting.docId())));
        List<Posting> shown = postings(backend, byQuery.filterId(), true, 1);
        assertThat(shown)
                .extracting(Posting::docId)
                .containsExactlyElementsOf(marked.stream()
                        .map(Posting::docId)
                        .filter(beta::contains)
                        .toList());
        assertThat(shown).allSatisfy(posting -> assertThat(posting.inFilter()).contains(true));

        StartFilterParams alphaWithDeletedQuery =
                new StartFilterParams(Optional.of(classic("alpha")), Optional.empty(), true);
        if (deletedByQuery) {
            assertThat(matches(backend, backend.filter(alphaWithDeletedQuery)))
                    .containsExactlyElementsOf(alphaWithDeleted);
        } else {
            backend.fails(Methods.START_FILTER, alphaWithDeletedQuery, ErrorCode.NOT_SUPPORTED);
        }
        assertThat(matches(
                        backend,
                        backend.filter(new StartFilterParams(Optional.of(classic("alpha")), Optional.empty(), false))))
                .containsExactlyElementsOf(alpha);

        FilterResult byPattern = backend.filter(new StartFilterParams(
                Optional.empty(),
                Optional.of(new FilterPattern("\\bALPHA\\b", true, Optional.of(List.of("body")))),
                true));
        assertThat(byPattern.query()).isEqualTo("/\\bALPHA\\b/i");
        assertThat(byPattern.timedOutValues()).hasValue(0);
        assertThat(matches(backend, byPattern)).containsExactlyElementsOf(alphaWithDeleted);
        FilterResult liveByPattern = backend.filter(new StartFilterParams(
                Optional.empty(), Optional.of(new FilterPattern("\\bbeta\\b", true, Optional.empty())), false));
        assertThat(matches(backend, liveByPattern)).containsExactlyElementsOf(beta);

        backend.fails(
                Methods.START_FILTER,
                new StartFilterParams(
                        Optional.empty(),
                        Optional.of(new FilterPattern("x", false, Optional.of(List.of("nothing")))),
                        false),
                ErrorCode.FIELD_NOT_FOUND);
    }

    /**
     * The documents with a value of {@code body} in which {@code pattern} is found, case aside as the
     * analyzer sets it aside, in id order.
     */
    private static TreeSet<Integer> scanned(RealBackend backend, String pattern, boolean includeDeleted)
            throws Exception {
        TreeSet<Integer> docIds = new TreeSet<>();
        for (StoredScanHit hit : backend.storedScan(
                        new StartStoredScanParams(pattern, true, Optional.of(List.of("body")), includeDeleted))
                .hits()) {
            docIds.add(hit.docId());
        }
        return docIds;
    }

    /** The matches of {@code filter}, stepped through forward; each knows how many come before it. */
    private static List<Integer> matches(RealBackend backend, FilterResult filter) throws Exception {
        List<Integer> docIds = new ArrayList<>();
        Optional<FilterMatch> match = backend.call(
                        Methods.STEP_FILTER, new StepFilterParams(filter.filterId(), -1, true))
                .match();
        while (match.isPresent()) {
            assertThat(match.get().index()).isEqualTo(docIds.size());
            docIds.add(match.get().docId());
            match = backend.call(
                            Methods.STEP_FILTER,
                            new StepFilterParams(filter.filterId(), match.get().docId() + 1, true))
                    .match();
        }
        assertThat(docIds).hasSize(filter.matchCount());
        return docIds;
    }

    /** Every posting of alpha in {@code body} through the filter, read {@code limit} at a time. */
    private static List<Posting> postings(RealBackend backend, long filterId, boolean onlyMatches, int limit)
            throws Exception {
        List<Posting> postings = new ArrayList<>();
        OptionalInt from = OptionalInt.of(0);
        while (from.isPresent()) {
            GetPostingsResult page = backend.call(
                    Methods.GET_POSTINGS,
                    new GetPostingsParams(
                            "body",
                            ALPHA,
                            from.getAsInt(),
                            limit,
                            Optional.of(new PostingsFilter(filterId, onlyMatches))));
            assertThat(page.postings()).hasSizeLessThanOrEqualTo(limit);
            assertThat(page.docFreq()).isGreaterThanOrEqualTo(page.postings().size());
            postings.addAll(page.postings());
            from = page.nextDoc();
        }
        return postings;
    }
}
