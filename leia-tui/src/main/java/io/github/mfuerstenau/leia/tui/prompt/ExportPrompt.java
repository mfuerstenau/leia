package io.github.mfuerstenau.leia.tui.prompt;

import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Layout;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.block.Title;
import dev.tamboui.widgets.paragraph.Paragraph;
import io.github.mfuerstenau.leia.protocol.terms.StartExportTermsParams;
import io.github.mfuerstenau.leia.protocol.terms.TermDelimiter;
import io.github.mfuerstenau.leia.protocol.terms.TermExportFormat;
import io.github.mfuerstenau.leia.tui.documents.DocumentExport;
import io.github.mfuerstenau.leia.tui.theme.Palette;
import io.github.mfuerstenau.leia.tui.widgets.Panels;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The export prompts: of the terms screen (ADR 0022), for the file that the terms of a field are
 * written to and the delimiter, and of the documents screen (ADR 0030), for the file that a
 * document is written to as JSON and whether its indexed terms and points are rebuilt. The backend
 * writes the terms; the prompt only resolves the path and asks before overwriting a file that
 * exists. The UI writes the document, so its prompt checks the path first (rule 1).
 */
public final class ExportPrompt {

    /** The popup is at most this wide, borders included. */
    private static final int MAX_WIDTH = 100;

    private static final DateTimeFormatter FILE_TIME =
            DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC);

    /** What a key did to the prompt. */
    public sealed interface Event {}

    /** Nothing for the caller to do. */
    public record Nothing() implements Event {}

    /** Start the export. */
    public record Submit(ExportRequest request) implements Event {}

    /** Esc: close the prompt. */
    public record Cancel() implements Event {}

    /** The export a prompt asks for. */
    public sealed interface ExportRequest {

        /** The terms of a field, written by the backend (ADR 0022). */
        record Terms(StartExportTermsParams params) implements ExportRequest {}

        /** A document as JSON, written by the UI (ADR 0030), with its indexed terms rebuilt if {@code indexedTerms}. */
        record Document(int docId, boolean indexedTerms, ExportTarget target) implements ExportRequest {}
    }

    /** Where a document export goes. */
    public sealed interface ExportTarget {

        /** This absolute file, which exists if {@code overwrite}. */
        record File(String path, boolean overwrite) implements ExportTarget {}

        /** The clipboard, through OSC 52. */
        record Clipboard() implements ExportTarget {}
    }

    /** What is exported, and the prompt's choices for it. */
    private sealed interface Kind {}

    /** The terms of a field. */
    private record TermsKind(String field, TermDelimiter delimiter, TermExportFormat format) implements Kind {}

    /**
     * A document.
     *
     * @param indexDirs the index directories of the open path, opened or skipped, which it never writes to
     * @param rebuilds whether the index can rebuild the indexed terms, so that Tab switches them
     */
    private record DocumentKind(int docId, boolean indexedTerms, List<String> indexDirs, boolean rebuilds)
            implements Kind {}

    private Kind kind;
    private final LineInput input;

    /** Why the last export couldn't start, or that the file exists; or null. */
    private String error;

    /** The file that the prompt said exists: Enter on it again overwrites it. Or null. */
    private String overwrite;

    /** Set while the export is being started. */
    private boolean busy;

    /** The directory that {@code ~} stands for. */
    private final Optional<Path> home;

    /** The directory a relative path is resolved against. */
    private final Optional<Path> cwd;

    private ExportPrompt(Kind kind, String file, Optional<Path> home, Optional<Path> cwd) {
        this.kind = kind;
        this.input = new LineInput(file);
        this.home = home;
        this.cwd = cwd;
    }

    /** The prompt for the terms of {@code field}, prefilled with {@code terms_<field>_<UTC time>.out}. */
    public static ExportPrompt terms(String field, Optional<Path> home, Optional<Path> cwd) {
        return new ExportPrompt(
                new TermsKind(field, TermDelimiter.TAB, TermExportFormat.LUKE),
                defaultFile(field, System.currentTimeMillis()),
                home,
                cwd);
    }

    /**
     * The prompt for document {@code docId}, prefilled with {@code doc-<id>.json}, with its indexed
     * terms rebuilt if the index {@code rebuilds} them; otherwise they stay off (ADR 0044). It never
     * writes into {@code indexDirs}.
     */
    public static ExportPrompt document(
            int docId, List<String> indexDirs, boolean rebuilds, Optional<Path> home, Optional<Path> cwd) {
        return new ExportPrompt(
                new DocumentKind(docId, rebuilds, List.copyOf(indexDirs), rebuilds),
                DocumentExport.defaultFile(docId),
                home,
                cwd);
    }

    public String input() {
        return input.text();
    }

    public Optional<String> error() {
        return Optional.ofNullable(error);
    }

    public boolean busy() {
        return busy;
    }

    public void setBusy() {
        busy = true;
    }

    /** Shows why the export couldn't start; the prompt can be edited again. */
    public void setError(String error) {
        busy = false;
        overwrite = null;
        this.error = error;
    }

    public Event handleKey(KeyEvent key) {
        if (busy) {
            return new Nothing();
        }
        switch (key.code()) {
            case ESCAPE -> {
                return new Cancel();
            }
            case ENTER -> {
                if (!input.text().isBlank()) {
                    return submit();
                }
            }
            case TAB -> {
                kind = switch (kind) {
                    case TermsKind terms ->
                        new TermsKind(
                                terms.field(),
                                switch (terms.delimiter()) {
                                    case TAB -> TermDelimiter.COMMA;
                                    case COMMA -> TermDelimiter.SPACE;
                                    case SPACE -> TermDelimiter.TAB;
                                },
                                terms.format());
                    case DocumentKind document ->
                        new DocumentKind(
                                document.docId(),
                                document.rebuilds() && !document.indexedTerms(),
                                document.indexDirs(),
                                document.rebuilds());
                };
                return new Nothing();
            }
            case CHAR -> {
                if (key.hasCtrl() && key.codePoint() == 'y') {
                    if (kind instanceof DocumentKind document) {
                        return new Submit(new ExportRequest.Document(
                                document.docId(), document.indexedTerms(), new ExportTarget.Clipboard()));
                    }
                    return new Nothing();
                }
                if (key.hasCtrl() && key.codePoint() == 'f') {
                    if (kind instanceof TermsKind terms) {
                        kind = new TermsKind(
                                terms.field(),
                                terms.delimiter(),
                                terms.format() == TermExportFormat.LUKE
                                        ? TermExportFormat.FULL
                                        : TermExportFormat.LUKE);
                    }
                    return new Nothing();
                }
            }
            default -> {}
        }
        if (input.handleKey(key, PathCompletion::componentStart) == LineInput.Edit.CHANGED) {
            error = null;
            overwrite = null;
        }
        return new Nothing();
    }

    /**
     * The export of the typed path, or first the question whether to overwrite it. A document isn't
     * written where it mustn't be.
     */
    private Event submit() {
        Path path;
        try {
            path = resolved();
        } catch (InvalidPathException e) {
            overwrite = null;
            error = "Not a path: " + e.getMessage();
            return new Nothing();
        }
        String shown = path.toString();
        if (kind instanceof DocumentKind document) {
            Optional<String> refused = DocumentExport.checkTarget(path, true, document.indexDirs());
            if (refused.isPresent()) {
                overwrite = null;
                error = refused.get();
                return new Nothing();
            }
        }
        boolean exists = Files.exists(path, LinkOption.NOFOLLOW_LINKS);
        if (exists && !shown.equals(overwrite)) {
            error = "File exists: " + shown + ". ⏎ again overwrites it.";
            overwrite = shown;
            return new Nothing();
        }
        return switch (kind) {
            case TermsKind terms ->
                new Submit(new ExportRequest.Terms(new StartExportTermsParams(
                        terms.field(),
                        shown,
                        Optional.of(terms.delimiter()),
                        Optional.of(exists),
                        Optional.of(terms.format()))));
            case DocumentKind document ->
                new Submit(new ExportRequest.Document(
                        document.docId(), document.indexedTerms(), new ExportTarget.File(shown, exists)));
        };
    }

    /** The typed path with {@code ~} expanded, against the working directory if it is relative. */
    private Path resolved() {
        Path typed = Path.of(PathCompletion.expandHome(input.text().strip(), home));
        return cwd.isPresent() && !typed.isAbsolute() ? cwd.get().resolve(typed) : typed;
    }

    /** Draws the popup, centered in {@code area}. */
    public void render(Frame frame, Rect area) {
        int width = Math.clamp((long) area.width() - 4, 0, MAX_WIDTH);
        int innerWidth = Math.max(width - 2, 1);
        List<Line> errorLines = error == null ? List.of() : errorLines(error, overwrite != null, innerWidth);
        // Input, error, hint, and the top and bottom border.
        int height = Math.min(1 + errorLines.size() + 1 + 2, area.height());
        Rect popup = Panels.centered(area, width, height);
        Panels.clear(frame, popup);
        String title;
        String choice;
        String keys;
        switch (kind) {
            case TermsKind terms -> {
                title = " Export the terms of " + terms.field() + " ";
                choice = " Tab delimiter: " + delimiterName(terms.delimiter()) + " · Ctrl+F format: "
                        + (terms.format() == TermExportFormat.FULL ? "full" : "Luke") + " ";
                keys = "Enter export   Tab delimiter   Ctrl+F format   Esc cancel";
            }
            case DocumentKind document -> {
                title = " Export document " + document.docId() + " as JSON ";
                String indexedTerms = document.indexedTerms() ? "on" : "off";
                choice = document.rebuilds()
                        ? " Tab terms and points: " + indexedTerms + " "
                        : " Terms and points: off ";
                keys = document.rebuilds()
                        ? "Enter export   Ctrl+Y copy   Tab terms and points   Esc cancel"
                        : "Enter export   Ctrl+Y copy   Esc cancel";
            }
        }
        Block block = Block.builder()
                .borders(Borders.ALL)
                .title(title)
                .titleBottom(Title.from(Line.from(choice)).right())
                .build();
        Rect inner = block.inner(popup);
        frame.renderWidget(block, popup);

        List<Rect> parts = Layout.vertical()
                .constraints(Constraint.length(1), Constraint.fill(), Constraint.length(1))
                .split(inner);
        Rect inputArea = parts.get(0);
        LineInput.Visible visible = input.visible(inputArea.width());
        frame.renderWidget(Paragraph.from(Line.from(visible.text())), inputArea);
        if (!busy) {
            frame.setCursorPosition(inputArea.x() + visible.cursorColumn(), inputArea.y());
        }
        if (!errorLines.isEmpty()) {
            frame.renderWidget(Paragraph.from(Text.from(errorLines)), parts.get(1));
        }
        String hint = busy ? "Exporting…" : keys;
        frame.renderWidget(Paragraph.from(Line.from(Span.raw(hint).dim())), parts.get(2));
    }

    public static String delimiterName(TermDelimiter delimiter) {
        return switch (delimiter) {
            case TAB -> "tab";
            case COMMA -> "comma";
            case SPACE -> "space";
        };
    }

    /**
     * {@code terms_<field>_<UTC time>.out}, as Luke names it, with every character of the field that
     * doesn't belong in a file name as {@code _}.
     */
    static String defaultFile(String field, long millis) {
        StringBuilder name = new StringBuilder();
        field.codePoints()
                .forEach(c -> name.appendCodePoint(Character.isLetterOrDigit(c) || c == '-' || c == '.' ? c : '_'));
        return "terms_" + name + "_" + FILE_TIME.format(Instant.ofEpochMilli(millis)) + ".out";
    }

    /** The error in red, or the question whether to overwrite in yellow, wrapped at {@code width}. */
    private static List<Line> errorLines(String error, boolean question, int width) {
        Style style = question ? Palette.current().warning() : Palette.current().error();
        return Panels.wrap(List.of(Line.styled(error, style)), width);
    }
}
