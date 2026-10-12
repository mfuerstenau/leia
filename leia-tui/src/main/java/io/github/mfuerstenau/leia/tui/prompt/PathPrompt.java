package io.github.mfuerstenau.leia.tui.prompt;

import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Layout;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.Clear;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.block.Title;
import dev.tamboui.widgets.paragraph.Paragraph;
import io.github.mfuerstenau.leia.protocol.index.DirectoryImpl;
import io.github.mfuerstenau.leia.protocol.index.OpenMode;
import io.github.mfuerstenau.leia.tui.widgets.Panels;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The open-index prompt: a one-line path editor with directory completion and the recent paths,
 * drawn as a popup. It also shows the {@code Directory} to open with, and why the last open failed.
 */
public final class PathPrompt {

    /** The popup is at most this wide, borders included. */
    private static final int MAX_WIDTH = 100;

    /** What a key did to the prompt. */
    public sealed interface Event {}

    /** Nothing for the caller to do: the key edited the input, or was ignored. */
    public record Nothing() implements Event {}

    /** Enter on a non-blank input: open this path, exactly as typed. */
    public record Submit(String path) implements Event {}

    /** Esc: close the prompt. */
    public record Cancel() implements Event {}

    private final LineInput input;
    private final Optional<Path> home;

    /** The directories that the last Tab found, if it found more than one. */
    private List<String> candidates = List.of();

    /** Why the last open failed, or null. */
    private String error;

    /** Set while the path is being opened. */
    private boolean busy;

    /** The recently opened paths, newest first (ADR 0020). */
    private List<String> recent = List.of();

    /**
     * While Up and Down step through {@link #recent}: the input before the first Up, and the
     * position among the recent paths other than that input. Null otherwise.
     */
    private Browsing browsing;

    /** The {@code Directory} to open with; Ctrl+D cycles it. */
    private DirectoryImpl directory = DirectoryImpl.FS;

    /** How to open the path; Ctrl+N switches between with and without a reader (ADR 0021). */
    private OpenMode mode = OpenMode.READER;

    private record Browsing(String typed, int position) {}

    /** {@code home} is the directory that {@code ~} stands for. */
    public PathPrompt(String input, Optional<Path> home) {
        this.input = new LineInput(input);
        this.home = home;
    }

    /** Up and Down step through {@code recent}, newest first. */
    public PathPrompt withRecent(List<String> recent) {
        this.recent = List.copyOf(recent);
        return this;
    }

    public PathPrompt withDirectory(DirectoryImpl directory) {
        this.directory = directory;
        return this;
    }

    public PathPrompt withMode(OpenMode mode) {
        this.mode = mode;
        return this;
    }

    public DirectoryImpl directory() {
        return directory;
    }

    public OpenMode mode() {
        return mode;
    }

    public String input() {
        return input.text();
    }

    public Optional<String> error() {
        return Optional.ofNullable(error);
    }

    public void setBusy() {
        busy = true;
        error = null;
        candidates = List.of();
    }

    public void setError(String error) {
        busy = false;
        this.error = error;
    }

    /**
     * Shows {@code error}, and that opening without a reader may still show the commits (ADR 0021),
     * unless this open was without one.
     */
    public void setErrorOfferingNoReader(String error) {
        if (mode == OpenMode.READER) {
            setError(error + "\n^N and Enter open it without a reader.");
        } else {
            setError(error);
        }
    }

    public Event handleKey(KeyEvent key) {
        boolean ctrl = key.hasCtrl() && key.code() == KeyCode.CHAR;
        switch (key.code()) {
            case ENTER -> {
                if (!input.text().isBlank()) {
                    return new Submit(input.text());
                }
            }
            case ESCAPE -> {
                return new Cancel();
            }
            case UP -> {
                browse(true);
                return new Nothing();
            }
            case DOWN -> {
                browse(false);
                return new Nothing();
            }
            case TAB -> {
                browsing = null;
                PathCompletion.Completion completion = PathCompletion.complete(input.text(), home);
                candidates = completion.candidates();
                if (!completion.input().equals(input.text())) {
                    error = null;
                }
                input.set(completion.input());
                return new Nothing();
            }
            default -> {
                if (ctrl && key.codePoint() == 'n') {
                    mode = mode == OpenMode.READER ? OpenMode.DIRECTORY_ONLY : OpenMode.READER;
                    return new Nothing();
                }
                if (ctrl && key.codePoint() == 'd') {
                    directory = switch (directory) {
                        case FS -> DirectoryImpl.MMAP;
                        case MMAP -> DirectoryImpl.NIO;
                        case NIO -> DirectoryImpl.FS;
                    };
                    return new Nothing();
                }
            }
        }
        if (input.handleKey(key, PathCompletion::componentStart) == LineInput.Edit.CHANGED) {
            // The error and the candidates describe the input before the edit. The next Up starts
            // from the edited input.
            error = null;
            candidates = List.of();
            browsing = null;
        }
        return new Nothing();
    }

    /**
     * Steps to an older ({@code older}) or newer recent path, like a shell's history. The path that
     * was in the input before the first Up is skipped, and Down past the newest brings it back.
     */
    private void browse(boolean older) {
        String typed = browsing == null ? input.text() : browsing.typed();
        Integer position = browsing == null ? null : browsing.position();
        List<String> entries =
                recent.stream().filter(path -> !path.equals(typed)).toList();
        Integer next;
        if (older) {
            next = position == null
                    ? (entries.isEmpty() ? null : 0)
                    : Integer.valueOf(Math.min(position + 1, entries.size() - 1));
        } else {
            next = position == null || position == 0 ? null : position - 1;
        }
        if (position != null || next != null) {
            String text = next == null ? typed : entries.get(next);
            if (!text.equals(input.text())) {
                error = null;
                candidates = List.of();
            }
            input.set(text);
        }
        browsing = next == null ? null : new Browsing(typed, next);
    }

    /** Draws the popup, centered in {@code area}. */
    public void render(Frame frame, Rect area) {
        int width = Math.min(Math.max(area.width() - 4, 0), MAX_WIDTH);
        int innerWidth = Math.max(width - 2, 1);
        List<Line> errorLines = error == null ? List.of() : errorLines(error, innerWidth);
        int candidateLines = candidates.isEmpty() ? 0 : 1;
        // Input, candidates, error, hint, and the top and bottom border.
        int height = Math.min(1 + candidateLines + errorLines.size() + 1 + 2, area.height());
        Rect popup = Panels.centered(area, width, height);
        frame.renderWidget(Clear.INSTANCE, popup);
        Span reader = mode == OpenMode.READER ? Span.raw("on") : Span.styled("off", Style.EMPTY.yellow());
        Line bottom = Line.from(
                Span.raw(" ^N reader: "), reader, Span.raw("   ^D directory: " + directoryLabel(directory) + " "));
        Block block = Block.builder()
                .borders(Borders.ALL)
                .title(" Open index ")
                .titleBottom(Title.from(bottom).right())
                .build();
        Rect inner = block.inner(popup);
        frame.renderWidget(block, popup);

        List<Rect> parts = Layout.vertical()
                .constraints(
                        Constraint.length(1),
                        Constraint.length(candidateLines),
                        Constraint.fill(),
                        Constraint.length(1))
                .split(inner);
        Rect inputArea = parts.get(0);
        LineInput.Visible visible = input.visible(inputArea.width());
        frame.renderWidget(Paragraph.from(Line.from(visible.text())), inputArea);
        if (!busy) {
            frame.setCursorPosition(inputArea.x() + visible.cursorColumn(), inputArea.y());
        }
        frame.renderWidget(
                Paragraph.from(Line.from(Span.raw(String.join("  ", candidates)).dim())), parts.get(1));
        if (!errorLines.isEmpty()) {
            frame.renderWidget(Paragraph.from(Text.from(errorLines)), parts.get(2));
        }
        String hint;
        if (busy) {
            hint = "Opening…";
        } else if (browsing != null || recent.stream().anyMatch(path -> !path.equals(input.text()))) {
            hint = "Enter open   Tab complete   ↑↓ recent   Esc cancel";
        } else {
            hint = "Enter open   Tab complete   Esc cancel";
        }
        frame.renderWidget(Paragraph.from(Line.from(Span.raw(hint).dim())), parts.get(3));
    }

    /** The error in red, wrapped at {@code width}. */
    private static List<Line> errorLines(String error, int width) {
        List<Line> lines = new ArrayList<>();
        for (String line : error.split("\n", -1)) {
            lines.add(Line.styled(line, Style.EMPTY.red()));
        }
        return Panels.wrap(lines, width);
    }

    /** How the prompt names a {@code Directory}. */
    private static String directoryLabel(DirectoryImpl directory) {
        return switch (directory) {
            case FS -> "FS (auto)";
            case MMAP -> "MMap";
            case NIO -> "NIO";
        };
    }
}
