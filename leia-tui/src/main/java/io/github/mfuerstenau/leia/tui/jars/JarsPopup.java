package io.github.mfuerstenau.leia.tui.jars;

import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Layout;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.Clear;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.paragraph.Paragraph;
import io.github.mfuerstenau.leia.protocol.jars.AddJarsResult;
import io.github.mfuerstenau.leia.protocol.jars.JarComponents;
import io.github.mfuerstenau.leia.tui.prompt.LineInput;
import io.github.mfuerstenau.leia.tui.prompt.PathCompletion;
import io.github.mfuerstenau.leia.tui.text.TextFormat;
import io.github.mfuerstenau.leia.tui.widgets.Panels;
import java.io.File;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The jars popup (ADR 0024): lists the loaded jars and the default folder, and adds more jars
 * while leia runs. The backend loads them; the popup only asks for a path.
 */
public final class JarsPopup {

    /** The popup is at most this wide, borders included. */
    private static final int MAX_WIDTH = 100;

    /** What the input line starts with. */
    private static final String INPUT_LABEL = "Add: ";

    /** What a key did to the popup. */
    public sealed interface Event {}

    /** Nothing for the caller to do. */
    public record Nothing() implements Event {}

    /** Enter on a non-blank input: add the jar or directory at this path, exactly as typed. */
    public record Submit(String path) implements Event {}

    /** Esc: close the popup. */
    public record Close() implements Event {}

    private final DefaultJars defaultJars;

    /** The directory that {@code ~} stands for. */
    private final Optional<Path> home;

    /** The loaded jars; null until they are fetched, or if they couldn't be. */
    private List<String> jars;

    /** Why the jars couldn't be listed; null otherwise. */
    private String jarsError;

    private final LineInput input = new LineInput("");

    /** The names that the last Tab found, if it found more than one. */
    private List<String> candidates = List.of();

    /** What the last addition did; null before one, or if it failed. */
    private AddJarsResult added;

    /** Why the last addition failed; null otherwise. */
    private String addError;

    /** Set while the jars are being added: then the popup takes no key. */
    private boolean busy;

    /** The first jar shown. */
    private int scroll;

    /** How far the list could scroll when it was last drawn. */
    private int maxScroll;

    /** How many jars the list showed when it was last drawn. */
    private int page = 1;

    public JarsPopup(DefaultJars defaultJars, Optional<Path> home) {
        this.defaultJars = defaultJars;
        this.home = home;
    }

    public String input() {
        return input.text();
    }

    /** Whether the popup waits for the jars to be listed or added. */
    public boolean waiting() {
        return busy || (jars == null && jarsError == null);
    }

    public void jarsLoaded(List<String> loaded) {
        jars = new ArrayList<>(loaded);
        jarsError = null;
    }

    public void jarsFailed(String error) {
        jars = null;
        jarsError = error;
    }

    public void setBusy() {
        busy = true;
        added = null;
        addError = null;
        candidates = List.of();
    }

    /** Shows what the addition did, lists the new jars and empties the input. */
    public void added(AddJarsResult result) {
        busy = false;
        if (jars != null) {
            jars.addAll(result.jars());
            // The new jars are last; the next draw clamps this to the end of the list.
            scroll = Integer.MAX_VALUE;
        }
        input.set("");
        added = result;
        addError = null;
    }

    /** Shows why the jars couldn't be added; the input can be edited again. */
    public void addFailed(String error) {
        busy = false;
        added = null;
        addError = error;
    }

    public Event handleKey(KeyEvent key) {
        // The Rust TUI blocked while it added the jars, so no key came in between.
        if (busy) {
            return new Nothing();
        }
        switch (key.code()) {
            case ENTER -> {
                if (!input.text().isBlank()) {
                    return new Submit(input.text());
                }
            }
            case ESCAPE -> {
                return new Close();
            }
            case UP -> scrollTo(shownScroll() - 1);
            case DOWN -> scrollTo(shownScroll() + 1);
            case PAGE_UP -> scrollTo(shownScroll() - page);
            case PAGE_DOWN -> scrollTo(shownScroll() + page);
            case TAB -> {
                PathCompletion.Completion completion =
                        PathCompletion.completeWithFiles(input.text(), home, DefaultJars::isJarName);
                candidates = completion.candidates();
                if (!completion.input().equals(input.text())) {
                    clearOutcome();
                }
                input.set(completion.input());
            }
            default -> {
                if (input.handleKey(key, PathCompletion::componentStart) == LineInput.Edit.CHANGED) {
                    // The outcome and the candidates describe the input before the edit.
                    clearOutcome();
                    candidates = List.of();
                }
            }
        }
        return new Nothing();
    }

    private void clearOutcome() {
        added = null;
        addError = null;
    }

    /** The first jar that the last draw showed. */
    private int shownScroll() {
        return Math.min(scroll, maxScroll);
    }

    private void scrollTo(int to) {
        scroll = Math.clamp(to, 0, maxScroll);
    }

    /** Draws the popup, centered in {@code area}. */
    public void render(Frame frame, Rect area) {
        int width = Math.min(Math.max(area.width() - 4, 0), MAX_WIDTH);
        int innerWidth = Math.max(width - 2, 1);
        List<Line> outcome = outcome(innerWidth);
        int candidateLines = candidates.isEmpty() ? 0 : 1;
        Line header = header();
        List<Line> jarLines = jarLines();
        // Default folder, header, input, candidates, outcome, hint, and the top and bottom border.
        int fixed = 1 + 1 + 1 + candidateLines + outcome.size() + 1 + 2;
        int height = Math.min(fixed + jarLines.size(), area.height());
        int listHeight = Math.clamp(height - fixed, 0, jarLines.size());
        Rect popup = Panels.centered(area, width, height);
        frame.renderWidget(Clear.INSTANCE, popup);
        Block block = Block.builder().borders(Borders.ALL).title(" Jars ").build();
        Rect inner = block.inner(popup);
        frame.renderWidget(block, popup);

        List<Rect> parts = Layout.vertical()
                .constraints(
                        Constraint.length(1),
                        Constraint.length(1),
                        Constraint.length(listHeight),
                        Constraint.length(1),
                        Constraint.length(candidateLines),
                        Constraint.fill(),
                        Constraint.length(1))
                .split(inner);
        frame.renderWidget(Paragraph.from(defaultLine()), parts.get(0));
        frame.renderWidget(Paragraph.from(header), parts.get(1));
        page = Math.max(listHeight, 1);
        maxScroll = Math.max(jarLines.size() - listHeight, 0);
        scroll = shownScroll();
        List<Line> shown = jarLines.subList(scroll, scroll + listHeight);
        frame.renderWidget(Paragraph.builder().text(Text.from(shown)).build(), parts.get(2));

        Rect inputRow = parts.get(3);
        int label = Math.min(INPUT_LABEL.length(), inputRow.width());
        Rect inputArea = new Rect(inputRow.x() + label, inputRow.y(), inputRow.width() - label, 1);
        frame.renderWidget(Paragraph.from(Line.from(INPUT_LABEL)), inputRow);
        LineInput.Visible visible = input.visible(inputArea.width());
        frame.renderWidget(Paragraph.from(Line.from(visible.text())), inputArea);
        if (!busy) {
            frame.setCursorPosition(inputArea.x() + visible.cursorColumn(), inputArea.y());
        }
        frame.renderWidget(
                Paragraph.from(Line.from(Span.raw(String.join("  ", candidates)).dim())), parts.get(4));
        if (!outcome.isEmpty()) {
            frame.renderWidget(Paragraph.from(Text.from(outcome)), parts.get(5));
        }
        String hint;
        if (busy) {
            hint = "Loading…";
        } else if (maxScroll > 0) {
            hint = "Enter add   Tab complete   ↑↓ scroll   Esc close";
        } else {
            hint = "Enter add   Tab complete   Esc close";
        }
        frame.renderWidget(Paragraph.from(Line.from(Span.raw(hint).dim())), parts.get(6));
    }

    private Line defaultLine() {
        if (defaultJars.dir().isEmpty()) {
            return Line.from(
                    Span.raw("Default folder: "),
                    Span.raw("none (no home directory)").dim());
        }
        String note = switch (defaultJars.state()) {
            case LOADED -> "";
            case NO_JARS -> "  (no jars in it)";
            case SKIPPED -> "  (left out: --no-default-jars)";
        };
        return Line.from(
                Span.raw("Default folder: "),
                Span.raw(shorten(defaultJars.dir().get())),
                Span.raw(note).dim());
    }

    /** The line above the list. */
    private Line header() {
        if (jarsError != null) {
            return Line.styled("Cannot list the jars: " + jarsError, Style.EMPTY.red());
        }
        if (jars == null) {
            return Line.from("Listing the jars…");
        }
        if (jars.isEmpty()) {
            return Line.from("No jars loaded");
        }
        return Line.from("Loaded: " + TextFormat.count(jars.size(), "jar"));
    }

    /** A line for each jar: its file name, then where it is. */
    private List<Line> jarLines() {
        if (jars == null) {
            return List.of();
        }
        Optional<Path> defaultDir = defaultJars.loadedDir();
        List<Line> lines = new ArrayList<>();
        for (String jar : jars) {
            String name = jar;
            String place = "";
            try {
                Path path = Path.of(jar);
                if (path.getFileName() != null) {
                    name = path.getFileName().toString();
                }
                Path parent = path.getParent();
                if (parent != null) {
                    place = defaultDir.isPresent() && defaultDir.get().equals(parent)
                            ? "default folder"
                            : shorten(parent);
                }
            } catch (InvalidPathException e) {
                // Shown as it came.
            }
            lines.add(Line.from(Span.raw("  " + name + "  "), Span.raw(place).dim()));
        }
        return lines;
    }

    /** {@code path} with the home directory as {@code ~}. */
    private String shorten(Path path) {
        if (home.isPresent() && path.startsWith(home.get())) {
            Path rest = home.get().relativize(path);
            return rest.toString().isEmpty() ? "~" : "~" + File.separator + rest;
        }
        return path.toString();
    }

    /** What the last addition did, or why it failed, wrapped at {@code width}; no line before one. */
    private List<Line> outcome(int width) {
        if (addError != null) {
            return Panels.wrap(List.of(Line.styled(addError, Style.EMPTY.red())), width);
        }
        return added != null ? Panels.wrap(outcomeLines(added), width) : List.of();
    }

    /** What an addition did: how many jars were loaded, then the names they added by kind. */
    static List<Line> outcomeLines(AddJarsResult result) {
        int already = result.alreadyLoaded().size();
        Style yellow = Style.EMPTY.yellow();
        if (result.jars().isEmpty()) {
            String text = already == 1
                    ? "The jar is already loaded; nothing was loaded"
                    : "All " + already + " jars are already loaded; nothing was loaded";
            return List.of(Line.styled(text, yellow));
        }
        String summary = "Loaded " + TextFormat.count(result.jars().size(), "jar");
        if (already > 0) {
            summary += " (" + already + " already loaded)";
        }
        JarComponents components = result.added();
        List<String> presets = components.analyzers().stream()
                .map(className -> className.substring(className.lastIndexOf('.') + 1))
                .toList();
        List<Map.Entry<String, List<String>>> kinds = List.of(
                Map.entry("preset", presets),
                Map.entry("char filter", components.charFilters()),
                Map.entry("tokenizer", components.tokenizers()),
                Map.entry("token filter", components.tokenFilters()),
                Map.entry("codec", components.codecs()),
                Map.entry("postings format", components.postingsFormats()),
                Map.entry("doc values format", components.docValuesFormats()),
                Map.entry("KNN vectors format", components.knnVectorsFormats()));
        List<Line> details = new ArrayList<>();
        for (Map.Entry<String, List<String>> kind : kinds) {
            List<String> names = kind.getValue();
            if (!names.isEmpty()) {
                details.add(Line.from(
                        "  " + TextFormat.count(names.size(), kind.getKey()) + ": " + String.join(", ", names)));
            }
        }
        if (details.isEmpty()) {
            summary += result.jars().size() == 1
                    ? ", which adds nothing new: each of its names was taken"
                    : ", which add nothing new: each of their names was taken";
            return List.of(Line.styled(summary, yellow));
        }
        List<Line> lines = new ArrayList<>();
        lines.add(Line.styled(summary + ":", Style.EMPTY.green()));
        lines.addAll(details);
        return lines;
    }
}
