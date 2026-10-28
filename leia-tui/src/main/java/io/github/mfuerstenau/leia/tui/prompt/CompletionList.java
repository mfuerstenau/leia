package io.github.mfuerstenau.leia.tui.prompt;

import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.paragraph.Paragraph;
import io.github.mfuerstenau.leia.tui.theme.Palette;
import io.github.mfuerstenau.leia.tui.widgets.Panels;
import io.github.mfuerstenau.leia.tui.widgets.Selection;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * The drop-down of the names that Tab found, under the input of a path prompt: one row for each,
 * one of them selected. Typing narrows it while the directory part stays the same.
 */
public final class CompletionList {

    /** The list shows at most this many rows. */
    public static final int MAX_ROWS = 8;

    private final Optional<Path> home;
    private final Predicate<String> file;

    /** The directory part of the input that the names follow. */
    private final String base;

    private List<PathCompletion.Candidate> candidates;
    private int selected;

    /** The first row shown. */
    private int top;

    /** {@code completion} has more than one candidate; {@code file} says which files are listed. */
    public CompletionList(PathCompletion.Completion completion, Optional<Path> home, Predicate<String> file) {
        this.home = home;
        this.file = file;
        this.base = completion.base();
        this.candidates = completion.candidates();
    }

    /** Moves the selection for ↑, ↓, PageUp and PageDown; returns whether it moved. */
    public boolean move(KeyEvent key) {
        switch (key.code()) {
            case UP, DOWN, PAGE_UP, PAGE_DOWN -> {
                int to = Selection.moved(selected, candidates.size(), key).orElse(selected);
                boolean moved = to != selected;
                selected = to;
                return moved;
            }
            default -> {
                return false;
            }
        }
    }

    /** The input that choosing the selected row gives. */
    public String chosen() {
        return base + candidates.get(selected).text();
    }

    /**
     * Narrows the list to what {@code input} still matches. Returns false when the list should
     * close: the directory part changed, or fewer than two names are left.
     */
    public boolean narrow(String input) {
        if (!PathCompletion.base(input).equals(base)) {
            return false;
        }
        List<PathCompletion.Candidate> matches = PathCompletion.matches(input, home, file);
        if (matches.size() < 2) {
            return false;
        }
        PathCompletion.Candidate before = candidates.get(selected);
        int kept = matches.indexOf(before);
        candidates = matches;
        selected = Math.max(kept, 0);
        return true;
    }

    /** How many rows the list takes when {@code max} fit. */
    public int rows(int max) {
        return Math.max(Math.min(Math.min(candidates.size(), MAX_ROWS), max), 0);
    }

    /** Whether not every name fits into {@code max} rows. */
    public boolean scrolls(int max) {
        return candidates.size() > rows(max);
    }

    /** The selected row and the count, as {@code n/total}. */
    public String position() {
        return (selected + 1) + "/" + candidates.size();
    }

    /** Draws the rows that fit in {@code area}, the selected one in view. */
    public void render(Frame frame, Rect area) {
        int rows = Math.min(rows(area.height()), area.height());
        if (rows == 0) {
            return;
        }
        top = Math.clamp(top, 0, candidates.size() - rows);
        if (selected < top) {
            top = selected;
        } else if (selected >= top + rows) {
            top = selected - rows + 1;
        }
        List<Line> lines = new ArrayList<>();
        for (int i = top; i < top + rows; i++) {
            String text = candidates.get(i).text();
            if (i == selected) {
                String padded = text + " ".repeat(Math.max(area.width() - text.length(), 0));
                lines.add(Line.from(Span.styled(padded, Panels.highlight(true))));
            } else {
                Style muted = Palette.current().muted();
                lines.add(Line.from(Span.styled(text, muted)));
            }
        }
        frame.renderWidget(Paragraph.builder().text(Text.from(lines)).build(), area);
    }
}
