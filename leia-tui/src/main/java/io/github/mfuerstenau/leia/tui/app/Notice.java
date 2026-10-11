package io.github.mfuerstenau.leia.tui.app;

import dev.tamboui.style.Style;
import dev.tamboui.text.Line;

/** What the tab line says about the last reopen, until the next key. */
sealed interface Notice {

    record Reopening() implements Notice {}

    record Unchanged(String segmentsFile) implements Notice {}

    record Reopened(String text) implements Notice {}

    record Failed(String error) implements Notice {}

    /** {@code R} without a reader. */
    record NoReader() implements Notice {}

    default Line line() {
        return switch (this) {
            case Reopening _ -> Line.from("Reopening…");
            case Unchanged unchanged -> Line.from("Unchanged: " + unchanged.segmentsFile());
            case Reopened reopened -> Line.from("Reopened: " + reopened.text());
            case Failed failed -> Line.styled("Reopen failed: " + failed.error(), Style.EMPTY.red());
            case NoReader _ -> Line.from("No reader to reopen");
        };
    }
}
