package io.github.mfuerstenau.leia.tui.search;

import static io.github.mfuerstenau.leia.tui.widgets.Panels.highlight;
import static io.github.mfuerstenau.leia.tui.widgets.Panels.panel;
import static io.github.mfuerstenau.leia.tui.widgets.Panels.right;

import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.table.Cell;
import dev.tamboui.widgets.table.Row;
import dev.tamboui.widgets.table.Table;
import dev.tamboui.widgets.table.TableState;
import io.github.mfuerstenau.leia.protocol.search.GetStoredScanResult;
import io.github.mfuerstenau.leia.protocol.search.MatchSpan;
import io.github.mfuerstenau.leia.protocol.search.Snippet;
import io.github.mfuerstenau.leia.protocol.search.StartStoredScanParams;
import io.github.mfuerstenau.leia.protocol.search.StoredScanHit;
import io.github.mfuerstenau.leia.protocol.search.StoredScanResult;
import io.github.mfuerstenau.leia.protocol.search.ValueRef;
import io.github.mfuerstenau.leia.tui.text.TextFormat;
import io.github.mfuerstenau.leia.tui.widgets.Form;
import io.github.mfuerstenau.leia.tui.widgets.Panels;
import io.github.mfuerstenau.leia.tui.widgets.Selection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.stream.Collectors;

/**
 * The regex scan of the search screen (ADR 0027): a Java regular expression over the stored values
 * of every document, run as a backend job. Its hits are values, not documents: each shows a snippet
 * around its first match, with the matches highlighted. They arrive while the scan runs, so the list
 * grows until it is done or stopped.
 */
public final class StoredScan {

    /** Marks a deleted document in the hit list. */
    private static final String DELETED_MARK = "✗";

    private final StartStoredScanParams params;

    /** Empty until the backend has started the job. */
    private OptionalLong jobId = OptionalLong.empty();

    /** Whether the job runs: until its outcome, a failure or a cancel. */
    private boolean running = true;

    private long scannedDocs;
    private long matchedValues;
    private final List<StoredScanHit> hits = new ArrayList<>();
    private StoredScanResult result;

    /** Why the scan could not start, failed, or was stopped; or null. */
    private String error;

    private int selected = -1;
    private final TableState table = new TableState();

    /** A scan with these params, about to start. */
    public StoredScan(StartStoredScanParams params) {
        this.params = params;
    }

    public StartStoredScanParams params() {
        return params;
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

    /** The first hit the next poll asks for: the hits that are in. */
    public int fromHit() {
        return hits.size();
    }

    /** How many values matched so far, or in all. */
    long matchedValues() {
        return matchedValues;
    }

    /** The hits that are in. */
    List<StoredScanHit> hits() {
        return hits;
    }

    /** Adds the hits that came in, and takes the outcome once it is there. */
    public void progress(GetStoredScanResult poll) {
        if (!running) {
            return;
        }
        scannedDocs = poll.scannedDocs();
        matchedValues = poll.matchedValues();
        hits.addAll(poll.hits());
        if (selected < 0 && !hits.isEmpty()) {
            selected = 0;
        }
        poll.result().ifPresent(outcome -> {
            matchedValues = outcome.matchedValues();
            result = outcome;
            running = false;
        });
    }

    /** Shows why the scan could not start or failed; the hits that are in stay. */
    public void failed(String error) {
        running = false;
        this.error = error;
    }

    /** Marks a running scan as stopped; the hits that are in stay. */
    public void cancelled() {
        if (running) {
            failed("Stopped.");
        }
    }

    public Optional<StoredScanHit> selected() {
        if (hits.isEmpty() || selected < 0) {
            return Optional.empty();
        }
        return Optional.of(hits.get(Math.min(selected, hits.size() - 1)));
    }

    /** The selected row, for the tests. */
    int selectedRow() {
        return selected;
    }

    /** Moves the selection with a navigation key; false for any other key. */
    public boolean moveSelection(KeyEvent key) {
        OptionalInt row = Selection.moved(Math.max(selected, 0), hits.size(), key);
        if (row.isEmpty()) {
            return false;
        }
        selected = row.getAsInt();
        return true;
    }

    /**
     * Selects the previous or next hit in another document, for {@code [}/{@code ]} on the documents
     * screen, and returns its document id.
     */
    public OptionalInt step(boolean forward) {
        if (hits.isEmpty() || selected < 0) {
            return OptionalInt.empty();
        }
        int current = Math.min(selected, hits.size() - 1);
        int docId = hits.get(current).docId();
        int row = -1;
        if (forward) {
            for (int i = current + 1; i < hits.size(); i++) {
                if (hits.get(i).docId() != docId) {
                    row = i;
                    break;
                }
            }
        } else {
            int before = -1;
            for (int i = current - 1; i >= 0; i--) {
                if (hits.get(i).docId() != docId) {
                    before = i;
                    break;
                }
            }
            if (before >= 0) {
                // The first hit of that document.
                int other = hits.get(before).docId();
                row = before;
                while (row > 0 && hits.get(row - 1).docId() == other) {
                    row--;
                }
            }
        }
        if (row < 0) {
            return OptionalInt.empty();
        }
        selected = row;
        return OptionalInt.of(hits.get(row).docId());
    }

    public void render(Frame frame, Rect area, boolean focused, int maxDoc) {
        String title = title(maxDoc);
        if (hits.isEmpty()) {
            Line line;
            if (error != null) {
                line = Line.styled(error, Style.EMPTY.red());
            } else if (result != null) {
                line = Line.from("No stored value matches.");
            } else {
                line = Line.empty();
            }
            frame.renderWidget(Panels.wrapped(List.of(line), panel(title, focused), area), area);
            return;
        }
        int rankWidth = Integer.toString(hits.size()).length();
        int docWidth = Selection.numberWidth(hits.stream().mapToLong(StoredScanHit::docId));
        List<String> fields = hits.stream().map(StoredScan::fieldText).toList();
        int fieldWidth = Math.clamp(
                fields.stream()
                        .mapToInt(field -> field.codePointCount(0, field.length()))
                        .max()
                        .orElse(0),
                5,
                32);
        int matchesWidth = Selection.numberWidth(hits.stream().mapToLong(StoredScanHit::matchCount));
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < hits.size(); i++) {
            StoredScanHit hit = hits.get(i);
            String doc = Integer.toString(hit.docId());
            rows.add(Row.from(
                    right(Integer.toString(i + 1), rankWidth),
                    Cell.from(Line.from(
                            Span.raw(" ".repeat(Math.max(docWidth - doc.length(), 0)) + doc),
                            Span.styled(hit.deleted() ? DELETED_MARK : " ", Style.EMPTY.red()))),
                    Cell.from(fields.get(i)),
                    right(Integer.toString(hit.matchCount()), matchesWidth),
                    Cell.from(snippetLine(hit))));
        }
        table.select(Math.min(Math.max(selected, 0), hits.size() - 1));
        Table widget = Table.builder()
                .rows(rows)
                .header(Row.from(
                                Cell.from("#"),
                                Cell.from("Doc"),
                                Cell.from("Field"),
                                Cell.from("Matches"),
                                Cell.from("Value"))
                        .style(Style.EMPTY.bold()))
                .widths(
                        Constraint.length(Math.max(rankWidth, 1)),
                        Constraint.length(docWidth + 1),
                        Constraint.length(fieldWidth),
                        Constraint.length(Math.max(matchesWidth, 7)),
                        Constraint.fill())
                .columnSpacing(1)
                .highlightStyle(highlight(focused))
                .highlightSymbol("")
                .highlightSpacing(Table.HighlightSpacing.NEVER)
                .block(panel(title, focused))
                .build();
        frame.renderStatefulWidget(widget, area, table);
    }

    String title(int maxDoc) {
        String values = TextFormat.count(matchedValues, "value");
        String title;
        if (result != null) {
            title = " " + values + " in " + TextFormat.count(result.matchedDocs(), "doc") + " in " + result.tookMillis()
                    + " ms ";
        } else if (running) {
            long percent = maxDoc > 0 ? scannedDocs * 100 / maxDoc : 0;
            title = " " + values + " · scanning… " + percent + "% of " + maxDoc + " docs ";
        } else {
            title = " " + values + " so far · stopped ";
        }
        if (result != null && result.hitsTruncated()) {
            title += "· first " + StartStoredScanParams.MAX_HITS + " shown ";
        }
        if (result != null && result.timedOutValues() > 0) {
            title += "· " + result.timedOutValues() + " timed out ";
        }
        return title;
    }

    /** The selected hit, the pattern and the settings, and the outcome. */
    public List<Line> detailLines() {
        List<Line> lines = new ArrayList<>();
        selected().ifPresent(hit -> {
            lines.add(Line.from("Doc " + hit.docId() + " · " + fieldText(hit) + " · " + TextFormat.size(hit.length())
                    + " · " + (hit.matchCount() == 1 ? "1 match" : hit.matchCount() + " matches")
                    + (hit.deleted() ? " · deleted" : "")));
            lines.add(Line.styled("⏎ opens the document · v views the value with its matches", Form.dim()));
            lines.add(Line.empty());
        });
        lines.add(Line.from("Pattern    /" + TextFormat.escapeControl(params.pattern()) + "/"
                + (params.caseInsensitive() ? "i" : "")));
        params.fields()
                .ifPresent(fields -> lines.add(Line.from("Fields     "
                        + fields.stream().map(TextFormat::escapeControl).collect(Collectors.joining(" · ")))));
        if (params.includeDeleted()) {
            lines.add(Line.from("Deleted    scanned too"));
        }
        if (result != null) {
            lines.add(Line.from("Scanned    " + TextFormat.count(result.scannedDocs(), "doc") + " in "
                    + result.tookMillis() + " ms"));
            if (result.hitsTruncated()) {
                lines.add(Line.styled(
                        "Only the first " + StartStoredScanParams.MAX_HITS
                                + " values are listed; the counts are of all of them.",
                        Form.dim()));
            }
            if (result.timedOutValues() > 0) {
                lines.add(Line.styled(
                        TextFormat.count(result.timedOutValues(), "value") + " took too long to match and "
                                + (result.timedOutValues() == 1 ? "was" : "were") + " skipped:",
                        Style.EMPTY.yellow()));
                for (ValueRef value : result.timedOut()) {
                    lines.add(Line.from("  doc " + value.docId() + " · " + TextFormat.escapeControl(value.field()) + "["
                            + value.valueIndex() + "]"));
                }
                long listed = result.timedOut().size();
                if (result.timedOutValues() > listed) {
                    lines.add(Line.from("  and " + (result.timedOutValues() - listed) + " more"));
                }
            }
        }
        if (error != null) {
            lines.add(Line.styled(error, Style.EMPTY.red()));
        }
        return lines;
    }

    /** {@code field[index]} of a hit. */
    static String fieldText(StoredScanHit hit) {
        return TextFormat.escapeControl(hit.field()) + "[" + hit.valueIndex() + "]";
    }

    /**
     * The snippet of a hit, with the matches in it highlighted, and {@code …} where the value goes
     * on. The spans and the snippet's start are UTF-8 byte offsets (ADR 0027).
     */
    static Line snippetLine(StoredScanHit hit) {
        return Line.from(snippetSpans(hit.snippet(), hit.spans(), hit.length()));
    }

    /**
     * {@code snippet} with the {@code matches} in it highlighted, and {@code …} where the value goes
     * on: before it unless it starts the value, and after it if it ends before {@code valueLength}.
     * The matches, the snippet's start and the length are UTF-8 byte offsets (ADR 0027, ADR 0042).
     *
     * @param valueLength the value's length in bytes; -1 if unknown, for no {@code …} after it
     */
    static List<Span> snippetSpans(Snippet snippet, List<MatchSpan> matches, long valueLength) {
        byte[] text = snippet.text().getBytes(StandardCharsets.UTF_8);
        long start = snippet.start();
        int len = text.length;
        List<Span> spans = new ArrayList<>();
        if (start > 0) {
            spans.add(Span.styled("…", Form.dim()));
        }
        int pos = 0;
        for (MatchSpan span : matches) {
            int from = Math.clamp(span.start() - start, 0, len);
            int to = Math.clamp(span.end() - start, 0, len);
            if (to <= from || from < pos || !boundary(text, from) || !boundary(text, to)) {
                continue;
            }
            if (from > pos) {
                spans.add(Span.raw(TextFormat.escapeControl(utf8(text, pos, from))));
            }
            spans.add(Span.styled(TextFormat.escapeControl(utf8(text, from, to)), TextFormat.MATCH));
            pos = to;
        }
        if (pos < len) {
            spans.add(Span.raw(TextFormat.escapeControl(utf8(text, pos, len))));
        }
        if (start + len < valueLength) {
            spans.add(Span.styled("…", Form.dim()));
        }
        return spans;
    }

    /** Whether a character starts at byte {@code at}, or the text ends there. */
    private static boolean boundary(byte[] text, int at) {
        return at >= text.length || (text[at] & 0xC0) != 0x80;
    }

    private static String utf8(byte[] text, int from, int to) {
        return new String(Arrays.copyOfRange(text, from, to), StandardCharsets.UTF_8);
    }
}
