package io.github.mfuerstenau.leia.tui.documents;

import static io.github.mfuerstenau.leia.tui.TestFilters.filterParams;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.mfuerstenau.leia.protocol.filter.FilterMatch;
import io.github.mfuerstenau.leia.protocol.filter.FilterResult;
import io.github.mfuerstenau.leia.protocol.filter.GetFilterResult;
import io.github.mfuerstenau.leia.tui.TestFilters;
import io.github.mfuerstenau.leia.tui.documents.DocFilter.Built;
import io.github.mfuerstenau.leia.tui.documents.DocFilter.FilterStep;
import io.github.mfuerstenau.leia.tui.documents.DocFilter.Stepped;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/** The tests of {@code doc_filter.rs}, ported. */
class DocFilterTest {

    private static GetFilterResult built(long filterId, int matchCount) {
        return TestFilters.built(filterId, matchCount, 10);
    }

    private static FilterMatch found(int docId, int index) {
        return new FilterMatch(docId, index);
    }

    @Test
    void buildsThenStepsAndLocates() {
        DocFilter filter = new DocFilter(1, "red", filterParams(false), 4);
        filter.started(7);
        assertThat(filter.jobId()).hasValue(7);
        assertThat(filter.progress(new GetFilterResult(5, Optional.empty()))).isEmpty();
        assertThat(filter.title(0, 10).text()).isEqualTo("filter red: finding matches… 50%");

        assertThat(filter.progress(built(7, 3))).contains(new Built(7, 4, FilterStep.NEAREST));
        assertThat(filter.jobId()).isEmpty();
        assertThat(filter.filterId()).hasValue(7);
        assertThat(filter.needsLocating(4)).isTrue();
        assertThat(filter.stepped(4, true, FilterStep.NEAREST, found(6, 1))).isEqualTo(new Stepped.Show(6));
        assertThat(filter.needsLocating(6)).isFalse();
        assertThat(filter.title(6, 10).text()).isEqualTo("filter red: 2 of 3");

        assertThat(filter.stepped(2, true, FilterStep.LOCATE, found(3, 1))).isEqualTo(new Stepped.Nothing());
        assertThat(filter.title(2, 10).text()).isEqualTo("filter red: not a match, 3 in all");
        assertThat(filter.title(9, 10).text()).isEqualTo("filter red: 3 matches");
    }

    @Test
    void looksBackWhenNothingMatchesForwardAndSaysWhenNothingIsLeft() {
        DocFilter filter = new DocFilter(1, "red", filterParams(true), 8);
        filter.progress(built(2, 1));

        assertThat(filter.stepped(8, true, FilterStep.NEAREST, null)).isEqualTo(new Stepped.LookBack(8));
        assertThat(filter.stepped(9, true, FilterStep.MOVE, null)).isEqualTo(new Stepped.Nothing());
        assertThat(filter.title(9, 10).text()).isEqualTo("filter red, deleted too: 1 match · no next match");
        filter.clearNote();
        assertThat(filter.title(9, 10).text()).isEqualTo("filter red, deleted too: 1 match");
    }

    @Test
    void isBuiltAgainAfterAReopenAndThenOnlyLocates() {
        DocFilter filter = new DocFilter(1, "red", filterParams(true), 4);
        assertThat(filter.toRebuild()).isEmpty();
        filter.started(7);
        filter.progress(built(7, 3));

        DocFilter rebuilt = filter.rebuild(2);
        assertThat(rebuilt.serial()).isEqualTo(2);
        assertThat(rebuilt.toRebuild()).contains(filterParams(true));
        assertThat(rebuilt.filterId()).isEmpty();
        assertThat(rebuilt.title(0, 10).text()).isEqualTo("filter red, deleted too: finding matches… 0%");
        rebuilt.started(8);
        assertThat(rebuilt.toRebuild()).isEmpty();
        assertThat(rebuilt.progress(built(8, 2))).contains(new Built(8, 4, FilterStep.LOCATE));

        // A failed filter would fail again.
        DocFilter failed = new DocFilter(3, "red", filterParams(false), 0);
        failed.failed("Invalid query");
        assertThat(failed.rebuild(4)).isSameAs(failed);
        assertThat(failed.rebuild(4).toRebuild()).isEmpty();
    }

    @Test
    void saysHowManyValuesOfAPatternTimedOut() {
        DocFilter filter = new DocFilter(1, "/a+b/", filterParams(false), 0);
        filter.progress(new GetFilterResult(
                10, Optional.of(new FilterResult(3, 2, "body:red", "body:red", 1, OptionalLong.of(4)))));

        assertThat(filter.title(0, 10).text()).isEqualTo("filter /a+b/: 2 matches · 4 values timed out");
    }

    @Test
    void saysOnceThatNothingMatches() {
        DocFilter filter = new DocFilter(1, "red", filterParams(false), 3);
        filter.progress(built(2, 0));

        assertThat(filter.stepped(3, true, FilterStep.NEAREST, null)).isEqualTo(new Stepped.LookBack(3));
        assertThat(filter.stepped(3, false, FilterStep.MOVE, null)).isEqualTo(new Stepped.Nothing());
        assertThat(filter.title(3, 10).text()).isEqualTo("filter red: no matches");
    }

    @Test
    void saysWhyItFailed() {
        DocFilter filter = new DocFilter(1, "title:(", filterParams(false), 0);
        filter.failed("Invalid query");

        assertThat(filter.jobId()).isEmpty();
        assertThat(filter.filterId()).isEmpty();
        assertThat(filter.title(0, 10)).isEqualTo(new DocFilter.Title("filter title:(: Invalid query", true));
    }
}
