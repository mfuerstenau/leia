package io.github.mfuerstenau.leia.tui.app;

import dev.tamboui.style.Style;
import dev.tamboui.text.Line;

/** What the tab line says about the last reopen, or the footer about the last export, until the next key. */
sealed interface Notice {

    record Reopening() implements Notice {}

    record Unchanged(String segmentsFile) implements Notice {}

    record Reopened(String text) implements Notice {}

    record Failed(String error) implements Notice {}

    /** {@code R} without a reader. */
    record NoReader() implements Notice {}

    /** The document was written or copied: what, and where. */
    record Exported(String text) implements Notice {}

    record ExportFailed(String error) implements Notice {}

    /** {@code E} while an export runs. */
    record ExportRunning() implements Notice {}

    default Line line() {
        return switch (this) {
            case Reopening _ -> Line.from("Reopening…");
            case Unchanged unchanged -> Line.from("Unchanged: " + unchanged.segmentsFile());
            case Reopened reopened -> Line.from("Reopened: " + reopened.text());
            case Failed failed -> Line.styled("Reopen failed: " + failed.error(), Style.EMPTY.red());
            case NoReader _ -> Line.from("No reader to reopen");
            case Exported exported -> Line.styled(exported.text(), Style.EMPTY.green());
            case ExportFailed failed -> Line.styled("Export failed: " + failed.error(), Style.EMPTY.red());
            case ExportRunning _ -> Line.from("An export runs; Esc cancels it");
        };
    }

    /** Whether the notice takes the footer, which is wider than the tab line: the outcome of an export names its file. */
    default boolean inFooter() {
        return this instanceof Exported || this instanceof ExportFailed;
    }
}
