package io.github.mfuerstenau.leia.tui.analysis;

import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.tui.event.KeyEvent;
import io.github.mfuerstenau.leia.protocol.analysis.AutoStopWords;
import io.github.mfuerstenau.leia.protocol.analysis.FieldStopWords;
import io.github.mfuerstenau.leia.protocol.analysis.GetAutoStopWordsResult;
import io.github.mfuerstenau.leia.protocol.analysis.StartAutoStopWordsParams;
import io.github.mfuerstenau.leia.tui.Optionals;
import io.github.mfuerstenau.leia.tui.text.TextFormat;
import io.github.mfuerstenau.leia.tui.theme.Palette;
import io.github.mfuerstenau.leia.tui.widgets.Form;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.jspecify.annotations.Nullable;

/**
 * The popup that finds auto stop words (ADR 0039): the terms of fields in more documents than a
 * threshold, as Lucene's {@code QueryAutoStopWordAnalyzer} finds them. It picks the threshold, a
 * percentage of the documents or a document frequency, and the fields, none for every indexed one.
 * Enter runs the search as a job, Esc stops it; the words it finds become the analysis screen's, and
 * the popup shows how many each field has.
 */
public final class AutoStopWordsForm {

    /** Why the search can't run without an index, or with one opened without a reader. */
    public static final String NO_INDEX = "needs an index opened with a reader";

    /** Lucene's default: a term in more than 40 % of the documents. */
    static final int DEFAULT_PERCENT = 40;

    private static final int WIDTH = 72;

    /** The longest list of words shown for a field. */
    private static final int SHOWN_WORDS = 200;

    /** What a key did. */
    public sealed interface Event {

        /** Nothing for the screen to do. */
        record None() implements Event {}

        /** The popup closes. */
        record Close() implements Event {}

        /** Start the search with these params. */
        record Start(StartAutoStopWordsParams params) implements Event {}

        /** Stop the running job. */
        record Cancel(long jobId) implements Event {}

        /** The words found are dropped. */
        record Clear() implements Event {}
    }

    private enum Row {
        KIND,
        VALUE
    }

    private final List<String> fields;
    private final List<Boolean> chosen;
    private final @Nullable String unsupported;

    private boolean percent = true;
    private int percentValue = DEFAULT_PERCENT;
    private int docFreqValue = 1;

    /** The row: 0 the kind, 1 the value, then the fields. */
    private int selected;

    /** Whether a search is asked for or runs, and its job once it started. */
    private boolean running;

    private @Nullable Long jobId;
    private long scanned;

    /** The words of the analysis screen, found here or before. */
    private @Nullable AutoStopWords found;

    /** The field whose words are listed; null for none. */
    private @Nullable String listed;

    private String message;

    /**
     * @param fields the indexed fields of the open index
     * @param unsupported why the open index can't run the search, such as {@link #NO_INDEX}; null if
     *     it can
     * @param found the words that the analysis screen has
     */
    public AutoStopWordsForm(List<String> fields, @Nullable String unsupported, @Nullable AutoStopWords found) {
        this.fields = List.copyOf(fields);
        this.chosen = new ArrayList<>(Collections.nCopies(fields.size(), false));
        this.unsupported = unsupported;
        this.found = found;
        // The fields of the words found before, unless that was every field.
        if (found != null && found.fields().size() < this.fields.size()) {
            found.fields().forEach(field -> {
                int index = this.fields.indexOf(field.field());
                if (index >= 0) {
                    chosen.set(index, true);
                }
            });
        }
    }

    /** Whether a search is asked for or runs. */
    public boolean running() {
        return running;
    }

    /** The running job; empty before it started. */
    public OptionalLong jobId() {
        return Optionals.optionalLong(jobId);
    }

    /** The params that Enter starts the search with; empty without a reader. */
    StartAutoStopWordsParams params() {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < fields.size(); i++) {
            if (chosen.get(i).booleanValue()) {
                names.add(fields.get(i));
            }
        }
        return percent
                ? new StartAutoStopWordsParams(names, OptionalInt.empty(), OptionalDouble.of(percentValue / 100.0))
                : new StartAutoStopWordsParams(names, OptionalInt.of(docFreqValue), OptionalDouble.empty());
    }

    /** The job of the search has started. */
    public void started(long jobId) {
        if (running) {
            this.jobId = jobId;
        }
    }

    /** How far the search has got, and its words once it is done. */
    public void progress(GetAutoStopWordsResult result) {
        scanned = result.termsScanned();
        if (result.stopWords().isPresent()) {
            running = false;
            jobId = null;
            found = result.stopWords().orElse(null);
            message = null;
        }
    }

    /** The search failed, or was stopped. */
    public void failed(String error) {
        running = false;
        jobId = null;
        message = error;
    }

    /** The index changed, and the backend stopped the job; the words are the screen's. */
    public void indexChanged(@Nullable AutoStopWords words) {
        if (running) {
            failed("Stopped: the index changed.");
        }
        found = words;
    }

    /**
     * Enter starts the search, Esc stops it or closes the popup; ↑↓ select a row, Space or ←→
     * change it, digits type the value, {@code v} lists a field's words, {@code d} drops the words.
     */
    public Event handleKey(KeyEvent key) {
        int last = 1 + fields.size();
        switch (key.code()) {
            case ESCAPE -> {
                return escape();
            }
            case ENTER -> {
                return start();
            }
            case UP -> selected = Math.max(selected - 1, 0);
            case DOWN -> selected = Math.min(selected + 1, last);
            case HOME -> selected = 0;
            case END -> selected = last;
            case LEFT, RIGHT -> change();
            case BACKSPACE -> {
                if (selected == Row.VALUE.ordinal()) {
                    setValue(value() / 10);
                }
            }
            case CHAR -> {
                return charKey(key.codePoint(), last);
            }
            default -> {}
        }
        return new Event.None();
    }

    /** Esc stops the running job, or else closes the form. */
    private Event escape() {
        if (!running) {
            return new Event.Close();
        }
        if (jobId != null) {
            long job = jobId;
            failed("Stopped.");
            return new Event.Cancel(job);
        }
        return new Event.None();
    }

    private Event start() {
        if (running) {
            return new Event.None();
        }
        if (unsupported != null) {
            message = "Auto stop words: " + unsupported;
            return new Event.None();
        }
        running = true;
        scanned = 0;
        message = null;
        return new Event.Start(params());
    }

    private Event charKey(int c, int last) {
        if (selected == Row.VALUE.ordinal() && c >= '0' && c <= '9') {
            setValue(Form.typeDigit(value(), (char) c, percent ? 100 : Integer.MAX_VALUE));
            return new Event.None();
        }
        switch (c) {
            case 'k' -> selected = Math.max(selected - 1, 0);
            case 'j' -> selected = Math.min(selected + 1, last);
            case ' ' -> change();
            case 'a' -> Collections.fill(chosen, true);
            case 'n' -> Collections.fill(chosen, false);
            case 'v' -> {
                String field = selectedField().orElse(null);
                listed = Objects.equals(field, listed) ? null : field;
            }
            case 'd' -> {
                if (!running && found != null) {
                    found = null;
                    listed = null;
                    return new Event.Clear();
                }
            }
            default -> {}
        }
        return new Event.None();
    }

    private Optional<String> selectedField() {
        int index = selected - 2;
        return index >= 0 && index < fields.size() ? Optional.of(fields.get(index)) : Optional.empty();
    }

    private void change() {
        if (selected == Row.KIND.ordinal()) {
            percent = !percent;
        } else if (selected > Row.VALUE.ordinal()) {
            int index = selected - 2;
            chosen.set(index, !chosen.get(index));
        }
    }

    private int value() {
        return percent ? percentValue : docFreqValue;
    }

    private void setValue(int value) {
        if (percent) {
            percentValue = value;
        } else {
            docFreqValue = value;
        }
    }

    /** The keys for the footer while the popup is open. */
    public String keys() {
        if (running) {
            return " Esc stop";
        }
        return " ⏎ find   Esc close   ↑↓ select   Space change   0–9 value   a all   n none   v words   d drop";
    }

    public void render(Frame frame, Rect area) {
        List<Line> lines = new ArrayList<>();
        lines.add(Form.row(
                "Threshold", percent ? "% of the documents" : "document frequency", selected == Row.KIND.ordinal()));
        lines.add(Form.row(
                percent ? "More than (%)" : "More than (documents)",
                Integer.toString(value()),
                selected == Row.VALUE.ordinal()));
        lines.add(Line.empty());
        boolean none = !chosen.contains(true);
        lines.add(Line.styled(
                none ? "Fields: none chosen, so every indexed field" : "Fields",
                Palette.current().heading()));
        int selectedLine = selected < 2 ? selected : 0;
        for (int i = 0; i < fields.size(); i++) {
            String field = fields.get(i);
            if (selected == i + 2) {
                selectedLine = lines.size();
            }
            lines.add(Form.row(TextFormat.escapeControl(field), fieldValue(i), selected == i + 2));
            if (field.equals(listed) && found != null) {
                lines.addAll(wordLines(field));
            }
        }
        if (fields.isEmpty()) {
            lines.add(Line.styled("  No indexed fields: open an index with a reader.", Form.muted()));
        }
        lines.add(Line.empty());
        lines.add(stateLine());
        if (message != null) {
            for (String line : TextFormat.wrap(message, WIDTH - 2, Integer.MAX_VALUE)) {
                lines.add(Line.styled(line, Palette.current().error()));
            }
        }
        Form.renderPopup(frame, area, " Auto stop words ", lines, selectedLine, WIDTH);
    }

    /** Whether field {@code i} is chosen, and how many stop words were found in it. */
    private String fieldValue(int i) {
        String field = fields.get(i);
        String words = found != null && hasField(field) ? "   " + count(field) : "";
        return (Boolean.TRUE.equals(chosen.get(i)) ? "[x]" : "[ ]") + words;
    }

    /** The first stop words found in {@code field}. */
    private List<Line> wordLines(String field) {
        List<String> fieldWords = words(field);
        String more = fieldWords.size() > SHOWN_WORDS ? ", …" : "";
        String text = fieldWords.isEmpty()
                ? "no words"
                : String.join(", ", fieldWords.subList(0, Math.min(fieldWords.size(), SHOWN_WORDS))) + more;
        List<Line> lines = new ArrayList<>();
        for (String line : TextFormat.wrap(TextFormat.escapeControl(text), WIDTH - 6, Integer.MAX_VALUE)) {
            lines.add(Line.styled("    " + line, Form.muted()));
        }
        return lines;
    }

    private Line stateLine() {
        if (running) {
            return Line.from("Finding the stop words: " + scanned + " terms scanned");
        }
        if (found != null) {
            return Line.from("Found: more than " + found.maxDocFreq() + " documents");
        }
        return Line.styled("A term in more documents than this is a stop word of its field.", Form.muted());
    }

    private boolean hasField(String field) {
        return found.fields().stream().anyMatch(words -> words.field().equals(field));
    }

    private List<String> words(String field) {
        return found.fields().stream()
                .filter(words -> words.field().equals(field))
                .findFirst()
                .map(FieldStopWords::words)
                .orElse(List.of());
    }

    private String count(String field) {
        return AnalysisSettings.count(words(field).size(), "word");
    }
}
