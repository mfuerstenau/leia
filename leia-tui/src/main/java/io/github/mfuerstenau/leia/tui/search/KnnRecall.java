package io.github.mfuerstenau.leia.tui.search;

import static io.github.mfuerstenau.leia.tui.widgets.Panels.highlight;
import static io.github.mfuerstenau.leia.tui.widgets.Panels.panel;
import static io.github.mfuerstenau.leia.tui.widgets.Panels.right;

import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.table.Cell;
import dev.tamboui.widgets.table.Row;
import dev.tamboui.widgets.table.Table;
import dev.tamboui.widgets.table.TableState;
import io.github.mfuerstenau.leia.protocol.knn.GetKnnRecallResult;
import io.github.mfuerstenau.leia.protocol.knn.KnnRecallMiss;
import io.github.mfuerstenau.leia.protocol.knn.KnnRecallResult;
import io.github.mfuerstenau.leia.protocol.knn.KnnRecallTarget;
import io.github.mfuerstenau.leia.protocol.knn.StartKnnRecallParams;
import io.github.mfuerstenau.leia.tui.text.TextFormat;
import io.github.mfuerstenau.leia.tui.widgets.Form;
import io.github.mfuerstenau.leia.tui.widgets.Panels;
import io.github.mfuerstenau.leia.tui.widgets.Selection;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * The recall check of the search screen's KNN mode (ADR 0086), run as a backend job: for the line's
 * target, the exact nearest documents the approximate search missed; for a sample of the field's
 * documents, the recall over all of them and the targets with the lowest. Its rows replace the hits
 * until the next search.
 */
public final class KnnRecall {

    private final StartKnnRecallParams params;

    /** Empty until the backend has started the job. */
    private OptionalLong jobId = OptionalLong.empty();

    /** Whether the job runs: until its outcome, a failure or a cancel. */
    private boolean running = true;

    private long checked;
    private KnnRecallResult result;

    /** Why the check could not start, failed, or was stopped; or null. */
    private String error;

    private int selected;
    private final TableState table = new TableState();

    /** A check with these params, about to start. */
    public KnnRecall(StartKnnRecallParams params) {
        this.params = params;
    }

    public StartKnnRecallParams params() {
        return params;
    }

    /** Whether it checks a sample of documents rather than the line's target. */
    boolean sampled() {
        return params.sample().isPresent();
    }

    public void started(long jobId) {
        this.jobId = OptionalLong.of(jobId);
    }

    /** The job to poll, while it runs. */
    public OptionalLong job() {
        return running ? jobId : OptionalLong.empty();
    }

    /** The job, started or not, while it runs: to cancel. */
    public boolean running() {
        return running;
    }

    /** Takes the progress, and the outcome once it is there. */
    public void progress(GetKnnRecallResult poll) {
        if (!running) {
            return;
        }
        checked = poll.targetsChecked();
        poll.recall().ifPresent(outcome -> {
            result = outcome;
            running = false;
        });
    }

    /** Shows why the check could not start or failed. */
    public void failed(String error) {
        running = false;
        this.error = error;
    }

    /** Marks a running check as stopped. */
    public void cancelled() {
        if (running) {
            failed("Stopped.");
        }
    }

    /** The rows: the missed documents of a single target, or the worst targets of a sample. */
    private int rowCount() {
        if (result == null) {
            return 0;
        }
        return sampled() ? result.worst().size() : missed().size();
    }

    private List<KnnRecallMiss> missed() {
        return result == null || result.worst().isEmpty()
                ? List.of()
                : result.worst().getFirst().missed();
    }

    /** The selected worst target of a sample. */
    Optional<KnnRecallTarget> selectedTarget() {
        return sampled() && selected < rowCount() ? Optional.of(result.worst().get(selected)) : Optional.empty();
    }

    /** The selected missed document of a single target. */
    Optional<KnnRecallMiss> selectedMiss() {
        return !sampled() && selected < rowCount() ? Optional.of(missed().get(selected)) : Optional.empty();
    }

    /** Moves the selection with a navigation key; false for any other key. */
    public boolean moveSelection(KeyEvent key) {
        OptionalInt row = Selection.moved(selected, rowCount(), key);
        if (row.isEmpty()) {
            return false;
        }
        selected = row.getAsInt();
        return true;
    }

    public void render(Frame frame, Rect area, boolean focused) {
        String title = title();
        if (rowCount() == 0) {
            Line line;
            if (error != null) {
                line = Line.styled(error, Style.EMPTY.red());
            } else if (result != null) {
                line = Line.from("The approximate search found every one of the exact nearest documents.");
            } else {
                line = Line.styled("Comparing the approximate search with the exact one…", Form.dim());
            }
            frame.renderWidget(Panels.wrapped(List.of(line), panel(title, focused), area), area);
            return;
        }
        List<Row> rows = new ArrayList<>();
        Row header;
        List<Constraint> widths;
        if (sampled()) {
            int docWidth = Math.max(
                    3,
                    Selection.numberWidth(
                            result.worst().stream().mapToLong(t -> t.docId().orElse(0))));
            header = Row.from(right("Doc", docWidth), right("Recall", 6), Cell.from("Missed"));
            for (KnnRecallTarget target : result.worst()) {
                rows.add(Row.from(
                        right(
                                target.docId().isPresent()
                                        ? Integer.toString(target.docId().getAsInt())
                                        : "-",
                                docWidth),
                        right(String.format(Locale.ROOT, "%.2f", target.recall()), 6),
                        Cell.from(missedText(target.missed()))));
            }
            widths = List.of(Constraint.length(docWidth), Constraint.length(6), Constraint.fill());
        } else {
            List<KnnRecallMiss> missed = missed();
            int rankWidth = Math.max(4, Selection.numberWidth(missed.stream().mapToLong(KnnRecallMiss::rank)) + 1);
            int docWidth = Math.max(3, Selection.numberWidth(missed.stream().mapToLong(KnnRecallMiss::docId)));
            header = Row.from(right("Rank", rankWidth), right("Doc", docWidth), Cell.from("Exact score"));
            for (KnnRecallMiss miss : missed) {
                rows.add(Row.from(
                        right("#" + miss.rank(), rankWidth),
                        right(Integer.toString(miss.docId()), docWidth),
                        Cell.from(TextFormat.score(miss.score(), 9))));
            }
            widths = List.of(Constraint.length(rankWidth), Constraint.length(docWidth), Constraint.fill());
        }
        table.select(Math.min(selected, rows.size() - 1));
        Table widget = Table.builder()
                .rows(rows)
                .header(header.style(Style.EMPTY.bold()))
                .widths(widths)
                .columnSpacing(2)
                .highlightStyle(highlight(focused))
                .highlightSymbol("")
                .highlightSpacing(Table.HighlightSpacing.NEVER)
                .block(panel(title, focused))
                .build();
        frame.renderStatefulWidget(widget, area, table);
    }

    /** The exact ranks and documents a target's approximate search missed, such as {@code #9 doc 363}. */
    private static String missedText(List<KnnRecallMiss> missed) {
        if (missed.isEmpty()) {
            return "-";
        }
        List<String> parts = missed.stream()
                .map(miss -> "#" + miss.rank() + " doc " + miss.docId())
                .toList();
        return String.join(", ", parts);
    }

    String title() {
        int k = params.query().k().orElse(0);
        String what = " Recall · k " + k;
        if (running) {
            return params.sample().isPresent()
                    ? what + " · checking… " + checked + " of "
                            + params.sample().getAsInt() + " targets "
                    : what + " · checking… ";
        }
        if (result == null) {
            return what + " · stopped ";
        }
        if (sampled()) {
            return what + " · " + TextFormat.count(result.targets(), "target") + " · mean "
                    + String.format(Locale.ROOT, "%.3f", result.meanRecall()) + " · worst first ";
        }
        return what + " · " + String.format(Locale.ROOT, "%.2f", result.minRecall()) + " · missed by approximate ";
    }

    /** The outcome over all targets, and what the check compares. */
    public List<Line> detailLines() {
        List<Line> lines = new ArrayList<>();
        if (result != null) {
            String recall = String.format(Locale.ROOT, "%.3f", result.meanRecall());
            if (sampled()) {
                lines.add(Line.from("Recall     " + recall + " mean, "
                        + String.format(Locale.ROOT, "%.2f", result.minRecall()) + " lowest"));
                lines.add(Line.from("Perfect    " + result.perfectTargets() + " of "
                        + TextFormat.count(result.targets(), "target")));
            } else {
                lines.add(Line.from("Recall     " + recall + " · "
                        + TextFormat.count(missed().size(), "exact nearest document")
                        + " missed"));
            }
            if (result.maxScoreDrift().isPresent()) {
                lines.add(Line.from("Drift      "
                        + String.format(
                                Locale.ROOT, "%.3g", result.meanScoreDrift().orElse(0)) + " mean, "
                        + String.format(
                                Locale.ROOT, "%.3g", result.maxScoreDrift().getAsDouble())
                        + " most: approximate against exact score of the documents both found"));
            }
            lines.add(Line.empty());
            selectedTarget()
                    .ifPresent(target -> lines.add(Line.styled(
                            "⏎ searches the nearest to doc " + target.docId().orElse(0), Form.dim())));
            selectedMiss().ifPresent(miss -> lines.add(Line.styled("⏎ opens doc " + miss.docId(), Form.dim())));
        }
        lines.add(Line.from(
                sampled()
                        ? "Each of " + TextFormat.count(params.sample().getAsInt(), "sampled document")
                                + " is a target, left out of its own rankings; the approximate top k is"
                                + " compared with the exact top k."
                        : "The approximate top k of the line's target is compared with the exact top k."));
        params.query()
                .filter()
                .ifPresent(
                        filter -> lines.add(Line.from("Filter     " + TextFormat.escapeControl(filter.expression()))));
        if (error != null) {
            lines.add(Line.styled(error, Style.EMPTY.red()));
        }
        return lines;
    }
}
