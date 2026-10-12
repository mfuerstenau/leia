package io.github.mfuerstenau.leia.tui.analysis;

import static io.github.mfuerstenau.leia.tui.widgets.Panels.highlight;
import static io.github.mfuerstenau.leia.tui.widgets.Panels.panel;

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
import dev.tamboui.widgets.table.Cell;
import dev.tamboui.widgets.table.Row;
import dev.tamboui.widgets.table.Table;
import dev.tamboui.widgets.table.TableState;
import io.github.mfuerstenau.leia.protocol.analysis.AnalyzeParams;
import io.github.mfuerstenau.leia.protocol.analysis.AnalyzeResult;
import io.github.mfuerstenau.leia.protocol.analysis.CharFilterStep;
import io.github.mfuerstenau.leia.protocol.analysis.GetAnalysisComponentsResult;
import io.github.mfuerstenau.leia.protocol.analysis.Token;
import io.github.mfuerstenau.leia.protocol.analysis.TokenAttribute;
import io.github.mfuerstenau.leia.protocol.analysis.TokenStage;
import io.github.mfuerstenau.leia.tui.prompt.LineInput;
import io.github.mfuerstenau.leia.tui.text.TextFormat;
import io.github.mfuerstenau.leia.tui.widgets.Form;
import io.github.mfuerstenau.leia.tui.widgets.Panels;
import io.github.mfuerstenau.leia.tui.widgets.Selection;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.ToIntFunction;

/**
 * The analysis screen (ADR 0014): the current analyzer, a text line, and what the analyzer makes of
 * the text, step by step: the text after each char filter, and the tokens after the tokenizer and
 * each token filter, with every attribute of the selected token. It works without an open index.
 * {@code b} builds the analyzer, which the search screen then parses and likes with. {@code a} on the
 * documents screen brings a stored value here.
 *
 * <p>Its calls are not jobs, and the backend answers them in order, so the reply to the last
 * analysis asked for comes last and is the one shown.
 */
public final class Analysis {

    /** Rows that PageUp and PageDown scroll the details. */
    private static final int PAGE = 10;

    /** From this width on, the token details stand next to the tokens; below it, under them. */
    private static final int WIDE = 120;

    private static final int COLUMN_SPACING = 1;

    /** What the caller has to fetch for the analysis screen. */
    public sealed interface Request {

        /** What custom analyzers can be built from, reported back with {@link #componentsLoaded} or {@link #componentsFailed}. */
        record Components() implements Request {}

        /** An analysis, reported back with {@link #analyzed} or {@link #analyzeFailed}. */
        record Analyze(AnalyzeParams params) implements Request {}
    }

    private enum Focus {
        STEPS,
        TOKENS,
        DETAILS
    }

    /** What an analysis showed, and the text that was analyzed. */
    private record Shown(String text, AnalyzeResult result) {}

    /** Where the text came from, such as a stored value; shown while the text line holds it. */
    private record Origin(String text, String label) {}

    /** A step of the analysis: the text after a char filter, or the tokens after a stage. */
    sealed interface Step {
        record CharFilter(int index) implements Step {}

        record Stage(int index) implements Step {}
    }

    private AnalyzerSettings settings = new AnalyzerSettings();

    /** The components, once fetched; null before. */
    private GetAnalysisComponentsResult components;

    /** Why the components could not be fetched; null otherwise. */
    private String componentsError;

    private final LineInput input = new LineInput("");
    private Origin origin;
    private boolean editing;
    private AnalyzerForm form;

    /** The analyzer that the form applied, while the backend tries it; null otherwise. */
    private AnalyzerSettings trying;

    /** The last analysis; null before the first one or after it failed. */
    private Shown shown;

    /** Why the last analysis failed; null otherwise. */
    private String error;

    private Focus focus = Focus.TOKENS;
    private int step = -1;
    private final TableState stepTable = new TableState();
    private int token;
    private final TableState tokenTable = new TableState();

    /** The first line of the details panel that is shown. */
    private int detailsScroll;

    /** The current analyzer. */
    public AnalyzerSettings settings() {
        return settings;
    }

    /** Whether the text line or the popup takes the keys, Esc included. */
    public boolean prompting() {
        return editing || form != null;
    }

    /** The components, the first time the screen is shown. */
    public Optional<Request> initialRequest() {
        return hasComponents() ? Optional.empty() : Optional.of(new Request.Components());
    }

    /** Whether the components were fetched, or failed to be. */
    public boolean hasComponents() {
        return components != null || componentsError != null;
    }

    public void componentsLoaded(GetAnalysisComponentsResult result) {
        if (form != null) {
            form.setComponents(result);
        }
        components = result;
        componentsError = null;
    }

    public void componentsFailed(String error) {
        components = null;
        componentsError = error;
    }

    /**
     * Takes the analysis of {@code text}. An analyzer that the form applied becomes the current one,
     * and the form closes.
     */
    public void analyzed(String text, AnalyzeResult result) {
        if (trying != null) {
            settings = trying;
            trying = null;
            form = null;
        }
        int steps = result.charFilters().size() + result.stages().size();
        // The last stage is what the analyzer makes of the text; a new analysis starts there.
        step = steps - 1;
        token = 0;
        detailsScroll = 0;
        shown = new Shown(text, result);
        error = null;
    }

    /**
     * Shows why the analysis failed: in the form if it tried an analyzer, which stays open to fix it.
     */
    public void analyzeFailed(String error) {
        if (trying != null && form != null) {
            trying = null;
            form.setError(error);
            return;
        }
        trying = null;
        shown = null;
        this.error = error;
    }

    /**
     * Analyzes a value from elsewhere, such as a document's stored value, with the current analyzer;
     * {@code label} says where it is from. A value longer than the backend takes is cut to its start.
     */
    public Optional<Request> analyzeValue(String label, String value) {
        String text = withinLimit(value);
        boolean cut = text.length() < value.length();
        input.set(text);
        origin = new Origin(text, cut ? label + " · first " + AnalyzeParams.MAX_TEXT_LENGTH + " characters" : label);
        editing = false;
        return analyze(settings);
    }

    /** The analysis of the text line with {@code with}. */
    private Optional<Request> analyze(AnalyzerSettings with) {
        return Optional.of(new Request.Analyze(new AnalyzeParams(with.analyzer(), input.text())));
    }

    /**
     * Edits the text line while it is open (Enter analyzes, Esc closes it), or the analyzer popup
     * (Enter tries the analyzer, which becomes the current one once the backend took it). Otherwise
     * {@code /} opens the line, {@code b} the popup, {@code r} analyzes again, and Tab switches between
     * the steps, the tokens and the token's details.
     */
    public Optional<Request> handleKey(KeyEvent key) {
        if (form != null) {
            return switch (form.handleKey(key)) {
                case Form.Event.None<AnalyzerSettings> _ -> Optional.empty();
                case Form.Event.Cancel<AnalyzerSettings> _ -> {
                    form = null;
                    yield Optional.empty();
                }
                case Form.Event.Apply<AnalyzerSettings> apply -> {
                    trying = apply.draft();
                    yield analyze(apply.draft());
                }
            };
        }
        if (editing) {
            switch (key.code()) {
                case ENTER -> {
                    if (input.text().length() > AnalyzeParams.MAX_TEXT_LENGTH) {
                        shown = null;
                        error = "The text is longer than " + AnalyzeParams.MAX_TEXT_LENGTH + " characters.";
                        return Optional.empty();
                    }
                    editing = false;
                    return analyze(settings);
                }
                case ESCAPE -> editing = false;
                default -> input.handleKey(key, LineInput::wordStart);
            }
            return Optional.empty();
        }
        if (key.isChar('/')) {
            editing = true;
            return Optional.empty();
        }
        if (key.isChar('b')) {
            form = new AnalyzerForm(settings, Optional.ofNullable(components));
            return Optional.empty();
        }
        if (key.isChar('r')) {
            return shown != null || error != null ? analyze(settings) : Optional.empty();
        }
        if (key.code() == KeyCode.TAB) {
            boolean back = key.hasShift();
            focus = switch (focus) {
                case STEPS -> back ? Focus.DETAILS : Focus.TOKENS;
                case TOKENS -> back ? Focus.STEPS : Focus.DETAILS;
                case DETAILS -> back ? Focus.TOKENS : Focus.STEPS;
            };
            return Optional.empty();
        }
        switch (focus) {
            case STEPS -> {
                OptionalInt moved = Selection.moved(Math.max(step, 0), stepsLen(), key);
                if (moved.isPresent() && moved.getAsInt() != step && stepsLen() > 0) {
                    step = moved.getAsInt();
                    detailsScroll = 0;
                }
            }
            case TOKENS -> {
                Optional<List<Token>> tokens = stageTokens();
                if (tokens.isPresent()) {
                    OptionalInt moved = Selection.moved(token, tokens.get().size(), key);
                    if (moved.isPresent() && moved.getAsInt() != token) {
                        token = moved.getAsInt();
                        detailsScroll = 0;
                    }
                }
            }
            case DETAILS -> scrollDetails(key);
        }
        return Optional.empty();
    }

    private void scrollDetails(KeyEvent key) {
        switch (key.code()) {
            case DOWN -> detailsScroll = saturated(detailsScroll + 1L);
            case UP -> detailsScroll = Math.max(detailsScroll - 1, 0);
            case PAGE_DOWN -> detailsScroll = saturated(detailsScroll + (long) PAGE);
            case PAGE_UP -> detailsScroll = Math.max(detailsScroll - PAGE, 0);
            case HOME -> detailsScroll = 0;
            case END -> detailsScroll = Integer.MAX_VALUE;
            case CHAR -> {
                switch (key.codePoint()) {
                    case 'j' -> detailsScroll = saturated(detailsScroll + 1L);
                    case 'k' -> detailsScroll = Math.max(detailsScroll - 1, 0);
                    case 'g' -> detailsScroll = 0;
                    case 'G' -> detailsScroll = Integer.MAX_VALUE;
                    default -> {}
                }
            }
            default -> {}
        }
    }

    private static int saturated(long value) {
        return (int) Math.min(value, Integer.MAX_VALUE);
    }

    private int stepsLen() {
        return shown == null
                ? 0
                : shown.result.charFilters().size() + shown.result.stages().size();
    }

    /** The selected step; empty without an analysis. */
    Optional<Step> step() {
        if (shown == null || step < 0) {
            return Optional.empty();
        }
        int charFilters = shown.result.charFilters().size();
        if (step < charFilters) {
            return Optional.of(new Step.CharFilter(step));
        }
        return step - charFilters < shown.result.stages().size()
                ? Optional.of(new Step.Stage(step - charFilters))
                : Optional.empty();
    }

    /** The tokens of the selected stage; empty if a char filter is selected. */
    private Optional<List<Token>> stageTokens() {
        return step().flatMap(selected -> selected instanceof Step.Stage stage
                ? Optional.of(shown.result.stages().get(stage.index()).tokens())
                : Optional.empty());
    }

    /** The selected token; empty if a char filter or an empty stage is selected. */
    Optional<Token> selectedToken() {
        return stageTokens()
                .filter(tokens -> !tokens.isEmpty())
                .map(tokens -> tokens.get(Math.min(token, tokens.size() - 1)));
    }

    /** The keys for the footer. */
    public String keys() {
        if (form != null) {
            return form.keys();
        }
        return editing
                ? " ⏎ analyze   Esc done   ←→ Ctrl+A/E/U/K/W edit"
                : "/ text   b analyzer   r again   Tab panel   ↑↓";
    }

    public void render(Frame frame, Rect area) {
        List<Line> analyzerLines = analyzerLines();
        int innerWidth = Math.max(area.width() - 2, 1);
        int analyzerHeight = Panels.wrap(analyzerLines, innerWidth).size();
        List<Rect> rows = Layout.vertical()
                .constraints(
                        Constraint.length(Math.clamp(analyzerHeight, 1, 4) + 2),
                        Constraint.length(3),
                        Constraint.fill())
                .split(area);
        frame.renderWidget(
                Panels.wrapped(analyzerLines, panel(" Analyzer · b builds it ", false), rows.get(0)), rows.get(0));
        renderText(frame, rows.get(1));
        Rect body = rows.get(2);
        if (error != null) {
            frame.renderWidget(
                    Panels.wrapped(
                            List.of(Line.styled(error, Style.EMPTY.red())), panel(" Analysis failed ", false), body),
                    body);
        } else if (shown == null) {
            List<Line> hint = List.of(
                    Line.styled(
                            "Press / to type a text, then Enter to analyze it, or a on the documents "
                                    + "screen for a stored value.",
                            Form.dim()),
                    Line.styled(
                            "Each step shows what the analyzer has made of it so far, and each token "
                                    + "every attribute.",
                            Form.dim()));
            frame.renderWidget(Panels.wrapped(hint, panel(" Analysis ", false), body), body);
        } else {
            // The names, the counts, the borders and a column to spare.
            List<Rect> columns = Layout.horizontal()
                    .constraints(Constraint.length(stepNameWidth() + 10), Constraint.fill())
                    .split(body);
            renderSteps(frame, columns.get(0));
            List<Rect> parts = area.width() >= WIDE
                    ? Layout.horizontal()
                            .constraints(Constraint.fill(), Constraint.length(48))
                            .split(columns.get(1))
                    : Layout.vertical()
                            .constraints(Constraint.fill(3), Constraint.fill(2))
                            .split(columns.get(1));
            renderStep(frame, parts.get(0));
            renderDetails(frame, parts.get(1));
        }
        if (form != null) {
            form.render(frame, area);
        }
    }

    /** The current analyzer, and why the component names are missing. */
    private List<Line> analyzerLines() {
        List<Line> lines = new ArrayList<>();
        lines.add(Line.from(settings.text()));
        if (componentsError != null) {
            lines.add(Line.styled("Cannot list the components: " + componentsError, Style.EMPTY.red()));
        }
        return lines;
    }

    private void renderText(Frame frame, Rect area) {
        String title = origin != null && origin.text.equals(input.text())
                ? " Text · " + TextFormat.escapeControl(origin.label) + " "
                : " Text ";
        Block block = panel(title, editing);
        Rect inner = block.inner(area);
        frame.renderWidget(block, area);
        if (editing) {
            LineInput.Visible visible = input.visible(inner.width());
            frame.renderWidget(Paragraph.from(Line.from(visible.text())), inner);
            frame.setCursorPosition(inner.x() + visible.cursorColumn(), inner.y());
        } else if (input.text().isEmpty()) {
            frame.renderWidget(Paragraph.from(Line.styled("Press / to type a text.", Form.dim())), inner);
        } else {
            Line line = Line.from(Span.raw(TextFormat.escapeControl(input.text())));
            if (shown != null && !shown.text.equals(input.text())) {
                line = line.append(Span.styled("   (not analyzed yet: Enter)", Form.dim()));
            }
            frame.renderWidget(Paragraph.from(line), inner);
        }
    }

    /**
     * How wide the step names are: as wide as the longest, from 16 to 23 columns, so that Lucene's
     * wrappers, such as {@code LimitTokenCountAnalyzer}, fit (ADR 0023).
     */
    private int stepNameWidth() {
        if (shown == null) {
            return 16;
        }
        int widest = 0;
        for (CharFilterStep filter : shown.result.charFilters()) {
            widest = Math.max(widest, chars(TextFormat.escapeControl(filter.name())));
        }
        for (TokenStage stage : shown.result.stages()) {
            widest = Math.max(widest, chars(TextFormat.escapeControl(stage.name())));
        }
        return Math.clamp(widest, 16, 23);
    }

    private static int chars(String text) {
        return text.codePointCount(0, text.length());
    }

    private boolean focused(Focus panel) {
        return focus == panel && !prompting();
    }

    private void renderSteps(Frame frame, Rect area) {
        int width = stepNameWidth();
        boolean focused = focused(Focus.STEPS);
        List<Row> rows = new ArrayList<>();
        for (CharFilterStep filter : shown.result.charFilters()) {
            rows.add(Row.from(Cell.from(Line.from(
                    Span.raw(Form.padded(TextFormat.escapeControl(filter.name()), width) + " "),
                    Span.styled(String.format("%6s", "text"), Form.dim())))));
        }
        for (TokenStage stage : shown.result.stages()) {
            String count = stage.tokens().size() + (stage.truncated() ? "+" : "");
            rows.add(Row.from(Cell.from(
                    Form.padded(TextFormat.escapeControl(stage.name()), width) + " " + String.format("%6s", count))));
        }
        if (step >= 0) {
            stepTable.select(step);
        }
        Table table = Table.builder()
                .rows(rows)
                .widths(Constraint.fill())
                .highlightStyle(highlight(focused))
                .highlightSymbol("")
                .highlightSpacing(Table.HighlightSpacing.NEVER)
                .block(panel(" Steps ", focused))
                .build();
        frame.renderStatefulWidget(table, area, stepTable);
    }

    /** The text after the selected char filter, or the tokens of the selected stage. */
    private void renderStep(Frame frame, Rect area) {
        boolean focused = focused(Focus.TOKENS);
        Optional<Step> selected = step();
        if (selected.isEmpty()) {
            frame.renderWidget(panel(" Tokens ", focused), area);
            return;
        }
        if (selected.get() instanceof Step.CharFilter charFilter) {
            CharFilterStep filter = shown.result.charFilters().get(charFilter.index());
            frame.renderWidget(
                    Panels.wrapped(
                            TextFormat.escapeControl(filter.text()),
                            panel(" Text after " + TextFormat.escapeControl(filter.name()) + " ", focused),
                            area),
                    area);
            return;
        }
        TokenStage stage = shown.result.stages().get(((Step.Stage) selected.get()).index());
        String title = " Tokens after " + TextFormat.escapeControl(stage.name()) + " · "
                + stage.tokens().size() + " ";
        if (stage.truncated()) {
            title += "· first " + AnalyzeResult.MAX_TOKENS + " only ";
        }
        Block block = panel(title, focused);
        if (stage.tokens().isEmpty()) {
            frame.renderWidget(
                    Paragraph.builder()
                            .text(Text.from(Line.styled("No token.", Form.dim())))
                            .block(block)
                            .build(),
                    area);
            return;
        }
        List<Row> rows = new ArrayList<>();
        for (Token each : stage.tokens()) {
            Row row = Row.from(
                    Cell.from(positionText(each)),
                    Cell.from(TextFormat.escapeControl(each.term())),
                    Cell.from(offsets(each)),
                    Cell.from(TextFormat.escapeControl(each.type())));
            // Stacked on the token before, such as a synonym.
            rows.add(each.positionIncrement() == 0 ? row.style(Style.EMPTY.italic()) : row);
        }
        tokenTable.select(Math.min(token, stage.tokens().size() - 1));
        Table table = Table.builder()
                .rows(rows)
                .header(Row.from(Cell.from("Pos"), Cell.from("Term"), Cell.from("Offsets"), Cell.from("Type"))
                        .style(Style.EMPTY.bold()))
                .widths(
                        Constraint.length(width(stage, each -> chars(positionText(each)))),
                        Constraint.fill(),
                        Constraint.length(Math.max(width(stage, each -> chars(offsets(each))), 7)),
                        Constraint.length(Math.max(width(stage, each -> chars(each.type())), 4)))
                .columnSpacing(COLUMN_SPACING)
                .highlightStyle(highlight(focused))
                .highlightSymbol("")
                .highlightSpacing(Table.HighlightSpacing.NEVER)
                .block(block)
                .build();
        frame.renderStatefulWidget(table, area, tokenTable);
    }

    /** The widest value of a column, from 3 to 40. */
    private static int width(TokenStage stage, ToIntFunction<Token> width) {
        return Math.clamp(stage.tokens().stream().mapToInt(width).max().orElse(0), 3, 40);
    }

    private static String offsets(Token token) {
        return token.startOffset() + "–" + token.endOffset();
    }

    /** Every attribute of the selected token. */
    private void renderDetails(Frame frame, Rect area) {
        boolean focused = focused(Focus.DETAILS);
        Optional<Token> selected = selectedToken();
        Block block = panel(" Token ", focused);
        if (selected.isEmpty()) {
            String hint = step().orElse(null) instanceof Step.CharFilter
                    ? "A char filter changes the text, before any token."
                    : "";
            frame.renderWidget(Panels.wrapped(List.of(Line.styled(hint, Form.dim())), block, area), area);
            return;
        }
        Token shownToken = selected.get();
        List<Line> lines = new ArrayList<>(List.of(
                Line.from("term              " + TextFormat.escapeControl(shownToken.term())),
                Line.from("position          " + shownToken.position()),
                Line.from("positionIncrement " + shownToken.positionIncrement()),
                Line.from("positionLength    " + shownToken.positionLength()),
                Line.from("offsets           " + offsets(shownToken)),
                Line.from("type              " + TextFormat.escapeControl(shownToken.type()))));
        String attribute = null;
        for (TokenAttribute value : shownToken.attributes()) {
            if (!value.attribute().equals(attribute)) {
                attribute = value.attribute();
                lines.add(Line.empty());
                lines.add(Line.styled(TextFormat.escapeControl(value.attribute()), Style.EMPTY.bold()));
            }
            lines.add(Line.from("  " + Form.padded(TextFormat.escapeControl(value.key()), 15) + " "
                    + TextFormat.escapeControl(value.value())));
        }
        // TamboUI scrolls no further than to show the last line at the bottom, so the scroll stops
        // there too (ADR 0034 §10, the commits screen).
        Rect inner = block.inner(area);
        List<Line> wrapped = Panels.wrap(lines, inner.width());
        detailsScroll = Math.min(detailsScroll, Math.max(wrapped.size() - inner.height(), 0));
        frame.renderWidget(
                Paragraph.builder()
                        .text(Text.from(wrapped))
                        .scroll(detailsScroll)
                        .block(block)
                        .build(),
                area);
    }

    /**
     * The token's position, and the last one it spans if more than one, such as a multi-word synonym;
     * {@code ·} marks a gap before it, where a token was removed, such as a stop word.
     */
    static String positionText(Token token) {
        String text = Integer.toString(token.position());
        if (token.positionLength() > 1) {
            text += "–" + (token.position() + token.positionLength() - 1);
        }
        if (token.positionIncrement() > 1) {
            text += "·";
        }
        return text;
    }

    /**
     * The start of {@code text} that the backend takes, cut between two characters: it counts UTF-16
     * code units, as Java's {@code String.length} does.
     */
    static String withinLimit(String text) {
        if (text.length() <= AnalyzeParams.MAX_TEXT_LENGTH) {
            return text;
        }
        int end = AnalyzeParams.MAX_TEXT_LENGTH;
        if (Character.isLowSurrogate(text.charAt(end)) && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end);
    }

    /** The text in the text line, for the tests. */
    LineInput input() {
        return input;
    }

    /** Why the last analysis failed, for the tests. */
    Optional<String> error() {
        return Optional.ofNullable(error);
    }
}
