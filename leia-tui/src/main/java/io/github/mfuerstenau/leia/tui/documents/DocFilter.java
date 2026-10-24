package io.github.mfuerstenau.leia.tui.documents;

import io.github.mfuerstenau.leia.protocol.filter.FilterMatch;
import io.github.mfuerstenau.leia.protocol.filter.FilterResult;
import io.github.mfuerstenau.leia.protocol.filter.GetFilterResult;
import io.github.mfuerstenau.leia.protocol.filter.StartFilterParams;
import io.github.mfuerstenau.leia.tui.text.TextFormat;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * A filter that narrows the documents screen to the documents that match a query (ADR 0031) or a
 * pattern over stored values (ADR 0032): ←/→ step to the previous or next match in document id
 * order, and the screen's title says which match is shown. The backend keeps the matches; this
 * tracks the filter's state, and what it was built from, so that it can be built again after a
 * reopen.
 *
 * <p>Each filter has a serial, new for one built again, that its calls carry: a reply for a filter
 * that was replaced or cleared meanwhile is dropped by it.
 */
public final class DocFilter {

    /** What the documents screen does with the match that {@code stepFilter} finds. */
    public enum FilterStep {
        /** Show it: ←/→. */
        MOVE,
        /**
         * Show it, or if there is none forward, the last match before it: {@code i}, and the first
         * document once the filter is built.
         */
        NEAREST,
        /** Only note whether the shown document matches, and which match it is. */
        LOCATE
    }

    /** What follows a step. */
    public sealed interface Stepped {
        /** Show this document. */
        record Show(int docId) implements Stepped {}

        /** Look back from this document id: there is no match forward. */
        record LookBack(int docId) implements Stepped {}

        record Nothing() implements Stepped {}
    }

    /**
     * The documents a step stays in: those of a segment (ADR 0051), from {@code from} up to but not
     * including {@code to}. A match outside counts as none.
     */
    public record Range(String name, int from, int to) {

        boolean contains(int docId) {
            return docId >= from && docId < to;
        }
    }

    /** What a built filter steps from: its id, the document to start from and the step to take. */
    public record Built(long filterId, int startDoc, FilterStep step) {}

    private sealed interface State {
        /** The backend finds the matches; {@code jobId} is empty until it has started the job. */
        record Building(OptionalLong jobId, long searched) implements State {}

        /** @param timedOut for a pattern, the values skipped for taking too long, whose documents may be missing */
        record Ready(long filterId, int matchCount, long timedOut) implements State {}

        record Failed(String error) implements State {}
    }

    /** The document last located, and which match it is: empty if it doesn't match. */
    private record Located(int docId, OptionalInt index) {}

    private final long serial;

    /** The query as typed, for the title. */
    private final String label;

    /** What the filter is built from. */
    private final StartFilterParams params;

    /** The document to start on once the filter is built. */
    private final int startDoc;

    /**
     * Whether the filter is built again after a reopen: once built, it only locates the shown
     * document rather than going to a match.
     */
    private final boolean rebuilt;

    private State state = new State.Building(OptionalLong.empty(), 0);

    /** Or null. */
    private Located located;

    /** Said until the next key, such as that there is no next match; or null. */
    private String note;

    /**
     * A filter about to be built from {@code params}, which starts on {@code startDoc} if it matches,
     * else on the first match after it, else on the last match.
     */
    public DocFilter(long serial, String label, StartFilterParams params, int startDoc) {
        this(serial, label, params, startDoc, false);
    }

    private DocFilter(long serial, String label, StartFilterParams params, int startDoc, boolean rebuilt) {
        this.serial = serial;
        this.label = label;
        this.params = params;
        this.startDoc = startDoc;
        this.rebuilt = rebuilt;
    }

    public long serial() {
        return serial;
    }

    /**
     * The same filter under {@code serial}, about to be built again for a reopened index (ADR 0032),
     * whose backend forgot it. A filter that failed stays as it is: it would fail again.
     */
    public DocFilter rebuild(long serial) {
        if (state instanceof State.Failed) {
            return this;
        }
        return new DocFilter(serial, label, params, startDoc, true);
    }

    /** What to build the filter from again after a reopen, until the job is started. */
    public Optional<StartFilterParams> toRebuild() {
        boolean pending =
                state instanceof State.Building building && building.jobId().isEmpty();
        return rebuilt && pending ? Optional.of(params) : Optional.empty();
    }

    /** The job that builds the filter, while it runs. */
    public OptionalLong jobId() {
        return state instanceof State.Building building ? building.jobId() : OptionalLong.empty();
    }

    /** The filter the backend keeps, once it is built. */
    public OptionalLong filterId() {
        return state instanceof State.Ready ready ? OptionalLong.of(ready.filterId()) : OptionalLong.empty();
    }

    public boolean building() {
        return state instanceof State.Building;
    }

    public void started(long jobId) {
        if (state instanceof State.Building building) {
            state = new State.Building(OptionalLong.of(jobId), building.searched());
        }
    }

    /**
     * Takes the progress of the job. Once the filter is built, returns its id, the document to start
     * from and the step to take from it: to the nearest match for a new filter, only locating it for
     * one built again.
     */
    public Optional<Built> progress(GetFilterResult result) {
        if (!(state instanceof State.Building building)) {
            return Optional.empty();
        }
        Optional<FilterResult> done = result.result();
        if (done.isEmpty()) {
            state = new State.Building(building.jobId(), result.docsSearched());
            return Optional.empty();
        }
        FilterResult built = done.get();
        state = new State.Ready(
                built.filterId(), built.matchCount(), built.timedOutValues().orElse(0));
        return Optional.of(new Built(built.filterId(), startDoc, rebuilt ? FilterStep.LOCATE : FilterStep.NEAREST));
    }

    public void failed(String error) {
        state = new State.Failed(error);
        located = null;
    }

    /** Takes what {@code stepFilter} found from {@code fromDoc} for a {@code step}. */
    public Stepped stepped(int fromDoc, boolean forward, FilterStep step, Optional<FilterMatch> found) {
        return stepped(fromDoc, forward, step, found, Optional.empty());
    }

    /**
     * Takes what {@code stepFilter} found from {@code fromDoc} for a {@code step} that stays {@code
     * within} a range, if one is given: a match outside it is no match.
     */
    public Stepped stepped(
            int fromDoc, boolean forward, FilterStep step, Optional<FilterMatch> found, Optional<Range> within) {
        if (step == FilterStep.LOCATE) {
            located = new Located(
                    fromDoc,
                    found.filter(match -> match.docId() == fromDoc)
                            .map(match -> OptionalInt.of(match.index()))
                            .orElse(OptionalInt.empty()));
            return new Stepped.Nothing();
        }
        Optional<FilterMatch> inside = found.filter(
                match -> within.map(range -> range.contains(match.docId())).orElse(true));
        if (inside.isPresent()) {
            located = new Located(
                    inside.get().docId(), OptionalInt.of(inside.get().index()));
            return new Stepped.Show(inside.get().docId());
        }
        if (step == FilterStep.NEAREST && forward) {
            return new Stepped.LookBack(fromDoc);
        }
        // Without matches, the title says so already.
        if (!(state instanceof State.Ready ready && ready.matchCount() == 0)) {
            note = (forward ? "no next match" : "no previous match")
                    + within.map(range -> " in " + range.name()).orElse("");
        }
        return new Stepped.Nothing();
    }

    /** Whether {@code docId} has to be located: the filter is built and doesn't know about it yet. */
    public boolean needsLocating(int docId) {
        return filterId().isPresent() && (located == null || located.docId() != docId);
    }

    public void clearNote() {
        note = null;
    }

    /** What the title says about the filter while {@code docId} is shown, and whether that is an error. */
    public record Title(String text, boolean error) {}

    public Title title(int docId, int maxDoc) {
        String deleted = params.includeDeleted() ? ", deleted too" : "";
        String shown = TextFormat.firstChars(TextFormat.escapeControl(label), 40);
        String text;
        boolean error = false;
        String timedOut = "";
        switch (state) {
            case State.Building building -> {
                long percent = maxDoc == 0 ? 0 : building.searched() * 100 / maxDoc;
                text = "finding matches… " + percent + "%";
            }
            case State.Ready ready when ready.matchCount() == 0 -> text = "no matches";
            case State.Ready ready -> text = matchesText(ready, docId);
            case State.Failed failed -> {
                text = TextFormat.firstChars(failed.error(), 80);
                error = true;
            }
        }
        if (state instanceof State.Ready ready) {
            timedOut = timedOutText(ready.timedOut());
        }
        String noted = note != null ? " · " + note : "";
        return new Title("filter " + shown + deleted + ": " + text + timedOut + noted, error);
    }

    /** Which match document {@code docId} is, if it was located, or else how many there are. */
    private String matchesText(State.Ready ready, int docId) {
        if (located != null && located.docId() == docId) {
            return located.index().isPresent()
                    ? (located.index().getAsInt() + 1) + " of " + ready.matchCount()
                    : "not a match, " + ready.matchCount() + " in all";
        }
        return ready.matchCount() == 1 ? "1 match" : ready.matchCount() + " matches";
    }

    private static String timedOutText(long timedOut) {
        if (timedOut == 1) {
            return " · 1 value timed out";
        }
        return timedOut > 1 ? " · " + timedOut + " values timed out" : "";
    }
}
