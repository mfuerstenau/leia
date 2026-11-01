package io.github.mfuerstenau.leia.tui.widgets;

import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.CharWidth;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.paragraph.Paragraph;
import io.github.mfuerstenau.leia.tui.theme.Palette;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The menu that {@code g} opens (ADR 0120): the jumps to other screens that the selection allows,
 * and {@code g} again for the top. It shows above the footer; the next key picks or closes it.
 */
public final class GoMenu {

    /**
     * A jump the selection allows.
     *
     * @param does what it does with the selection, such as {@code terms of title}
     */
    public record Target(Jump jump, String does) {}

    /** What a key did to the menu. */
    public sealed interface Event {}

    /**
     * The letter of {@code jump}, listed or not: the screen says why it can't go there, if the
     * index lacks what it needs.
     */
    public record Picked(Jump jump) implements Event {}

    /** {@code g} again: the top of the list. */
    public record Top() implements Event {}

    /** Any other key closes the menu. */
    public record Close() implements Event {}

    private static final String TITLE = " Go to ";

    private final List<Target> targets;

    public GoMenu(List<Target> targets) {
        this.targets = List.copyOf(targets);
    }

    public List<Target> targets() {
        return targets;
    }

    public Event handleKey(KeyEvent key) {
        if (key.code() != KeyCode.CHAR || key.hasCtrl() || key.hasAlt()) {
            return new Close();
        }
        if (key.codePoint() == 'g') {
            return new Top();
        }
        Optional<Jump> jump = Jump.of(key.codePoint());
        return jump.<Event>map(Picked::new).orElseGet(Close::new);
    }

    /** The menu's lines: a letter in bold and what it does. */
    List<Line> lines() {
        List<Line> lines = new ArrayList<>();
        for (Target target : targets) {
            lines.add(line(String.valueOf(target.jump().letter()), target.does()));
        }
        lines.add(line("g", "top"));
        return lines;
    }

    private static Line line(String letter, String does) {
        return Line.from(Span.styled(" " + letter, Palette.current().label()), Span.raw("  " + does + " "));
    }

    /** Draws the menu at the bottom left of {@code area}, the screen above the footer. */
    public void render(Frame frame, Rect area) {
        List<Line> lines = lines();
        int width = CharWidth.of(TITLE);
        for (Line line : lines) {
            width = Math.max(width, line.width());
        }
        // The borders.
        int w = Math.min(width + 2, area.width());
        int h = Math.min(lines.size() + 2, area.height());
        if (w <= 2 || h <= 2) {
            return;
        }
        Rect window = new Rect(area.x(), area.y() + area.height() - h, w, h);
        Panels.clear(frame, window);
        frame.renderWidget(
                Paragraph.builder()
                        .text(Text.from(lines))
                        .block(Panels.box(TITLE))
                        .build(),
                window);
    }
}
