package io.github.mfuerstenau.leia.tui;

import io.github.mfuerstenau.leia.protocol.filter.FilterPattern;
import io.github.mfuerstenau.leia.protocol.filter.FilterResult;
import io.github.mfuerstenau.leia.protocol.filter.GetFilterResult;
import io.github.mfuerstenau.leia.protocol.filter.StartFilterParams;
import java.util.Optional;
import java.util.OptionalLong;

/** Filters of the documents screen (ADR 0031) for the tests: {@code test_support.rs}'s {@code filter_params}. */
public final class TestFilters {

    private TestFilters() {}

    /** The params of a filter for the pattern {@code red}. */
    public static StartFilterParams filterParams(boolean includeDeleted) {
        return new StartFilterParams(
                Optional.empty(),
                Optional.of(new FilterPattern("red", false, Optional.empty())),
                Optional.empty(),
                includeDeleted);
    }

    /** A filter built as {@code filterId}, with {@code matchCount} matches, after searching {@code docsSearched}. */
    public static GetFilterResult built(long filterId, int matchCount, long docsSearched) {
        return new GetFilterResult(
                docsSearched,
                Optional.of(new FilterResult(filterId, matchCount, "body:red", "body:red", 1, OptionalLong.empty())));
    }
}
