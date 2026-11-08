package io.github.mfuerstenau.leia.tui.runtimes;

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
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.paragraph.Paragraph;
import io.github.mfuerstenau.leia.client.runtimes.Mirror;
import io.github.mfuerstenau.leia.client.runtimes.Mirrors;
import io.github.mfuerstenau.leia.client.runtimes.RuntimeInstall;
import io.github.mfuerstenau.leia.tui.prompt.LineInput;
import io.github.mfuerstenau.leia.tui.theme.Palette;
import io.github.mfuerstenau.leia.tui.widgets.Panels;
import io.github.mfuerstenau.leia.tui.widgets.Selection;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The list of mirrors in the window of {@code D} (ADR 0124), to choose the one to download from, or
 * to retry a failed download from: a row per mirror, with the reason it failed if it did, and a last
 * row that takes a URL of the user's own.
 */
final class MirrorPicker {

    /** The keys while the list shows. */
    static final String KEYS = "↑↓ move   ⏎ choose   u own URL";

    /** The keys while a URL is typed. */
    static final String TYPING_KEYS = "⏎ use   Esc back";

    private static final String OWN_ROW = "Own URL…";

    /** What a key did to the picker. */
    sealed interface Result {}

    /** Nothing for the window to do. */
    record Nothing() implements Result {}

    /** The user chose {@code mirror}. */
    record Chosen(Mirror mirror) implements Result {}

    /** Esc on the list. */
    record Back() implements Result {}

    private final List<Mirror> mirrors;
    private final Map<URI, String> failed;
    private int cursor;
    private LineInput input;
    private Optional<String> problem = Optional.empty();

    /**
     * @param mirrors the mirrors, in order
     * @param failed why the mirrors that failed did, by their URIs
     * @param cursor the row the cursor starts on
     */
    MirrorPicker(List<Mirror> mirrors, Map<URI, String> failed, int cursor) {
        this.mirrors = List.copyOf(mirrors);
        this.failed = new LinkedHashMap<>(failed);
        this.cursor = Math.clamp(cursor, 0, Math.max(this.mirrors.size() - 1, 0));
    }

    /** A picker with the cursor on the first mirror that hasn't failed. */
    static MirrorPicker afterFailures(List<Mirror> mirrors, Map<URI, String> failed) {
        int first = 0;
        while (first < mirrors.size() && failed.containsKey(mirrors.get(first).uri())) {
            first++;
        }
        return new MirrorPicker(mirrors, failed, first < mirrors.size() ? first : 0);
    }

    /** Whether a URL is typed now. */
    boolean typing() {
        return input != null;
    }

    /** The keys for the legend. */
    String keys() {
        return typing() ? TYPING_KEYS : KEYS;
    }

    Result handleKey(KeyEvent key) {
        if (input != null) {
            return typed(key);
        }
        if (key.code() == KeyCode.ESCAPE || key.isChar('q')) {
            return new Back();
        }
        if (key.isChar('u') || (key.code() == KeyCode.ENTER && cursor == mirrors.size())) {
            input = new LineInput("https://");
            problem = Optional.empty();
            return new Nothing();
        }
        if (key.code() == KeyCode.ENTER) {
            return new Chosen(mirrors.get(cursor));
        }
        OptionalInt moved = Selection.moved(cursor, mirrors.size() + 1, key);
        if (moved.isPresent()) {
            cursor = moved.getAsInt();
        }
        return new Nothing();
    }

    private Result typed(KeyEvent key) {
        if (key.code() == KeyCode.ESCAPE) {
            input = null;
            problem = Optional.empty();
            return new Nothing();
        }
        if (key.code() == KeyCode.ENTER) {
            String url = input.text().strip();
            problem = Mirrors.check(url);
            if (problem.isPresent()) {
                return new Nothing();
            }
            input = null;
            return new Chosen(Mirror.of(URI.create(url), Mirror.Origin.TYPED));
        }
        if (input.handleKey(key, LineInput::wordStart) == LineInput.Edit.CHANGED) {
            problem = Optional.empty();
        }
        return new Nothing();
    }

    /** Draws the list, and below it the URL being typed and why it was refused. */
    void render(Frame frame, Rect area, String title) {
        int inputHeight = input == null ? 0 : 3 + (problem.isPresent() ? 1 : 0);
        List<Rect> parts = Layout.vertical()
                .constraints(Constraint.fill(), Constraint.length(inputHeight))
                .split(area);
        Block block = Panels.panel(title, input == null);
        Rect inner = block.inner(parts.get(0));
        List<Line> rows = new ArrayList<>();
        for (int i = 0; i <= mirrors.size(); i++) {
            rows.add(row(i));
        }
        int top = Math.max(cursor - Math.max(inner.height() - 1, 0), 0);
        frame.renderWidget(
                Paragraph.builder()
                        .text(Text.from(rows.subList(Math.min(top, rows.size()), rows.size())))
                        .block(block)
                        .build(),
                parts.get(0));
        if (input == null) {
            return;
        }
        Block inputBlock = Panels.panel(" Mirror URL ", true);
        Rect inputArea = parts.get(1);
        Rect field = new Rect(inputArea.x(), inputArea.y(), inputArea.width(), Math.min(3, inputArea.height()));
        Rect fieldInner = inputBlock.inner(field);
        frame.renderWidget(inputBlock, field);
        LineInput.Visible visible = input.visible(fieldInner.width());
        frame.renderWidget(Paragraph.from(Line.from(visible.text())), fieldInner);
        frame.setCursorPosition(fieldInner.x() + visible.cursorColumn(), fieldInner.y());
        problem.ifPresent(text -> frame.renderWidget(
                Paragraph.from(Line.styled(text, Palette.current().error())),
                new Rect(inputArea.x(), inputArea.y() + 3, inputArea.width(), 1)));
    }

    private Line row(int i) {
        boolean selected = i == cursor && input == null;
        Style highlight = selected ? Panels.highlight(true) : Style.EMPTY;
        if (i == mirrors.size()) {
            return Line.from(
                    Span.styled(OWN_ROW, highlight.patch(Palette.current().muted())));
        }
        Mirror mirror = mirrors.get(i);
        List<Span> spans = new ArrayList<>();
        String reason = failed.get(mirror.uri());
        spans.add(Span.styled((reason != null ? "✗ " : "  ") + mirror.name(), highlight));
        spans.add(Span.styled(
                " · " + mirror.uri() + RuntimeInstall.origin(mirror),
                Palette.current().muted()));
        if (reason != null) {
            spans.add(Span.styled(" · " + reason, Palette.current().error()));
        }
        return Line.from(spans);
    }

    /** The text of each row, for the tests. */
    List<String> rowTexts() {
        List<String> texts = new ArrayList<>();
        for (int i = 0; i <= mirrors.size(); i++) {
            StringBuilder text = new StringBuilder();
            row(i).spans().forEach(span -> text.append(span.content()));
            texts.add(text.toString());
        }
        return texts;
    }

    /** Why the typed URL was refused. */
    Optional<String> problem() {
        return problem;
    }
}
