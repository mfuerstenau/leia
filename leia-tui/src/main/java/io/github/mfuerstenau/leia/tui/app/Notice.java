package io.github.mfuerstenau.leia.tui.app;

import dev.tamboui.text.Line;
import io.github.mfuerstenau.leia.tui.theme.Palette;

/** What the tab line says about the last reopen, or the footer about the last export, until the next key. */
sealed interface Notice {

    record Reopening() implements Notice {}

    record Unchanged(String segmentsFile) implements Notice {}

    record Reopened(String text) implements Notice {}

    record Failed(String error) implements Notice {}

    /**
     * A screen or key that the open index lacks (ADR 0044): what it is, and why, such as {@code
     * needs a reader}.
     */
    record NotSupported(String what, String reason) implements Notice {}

    /** The document was written or copied: what, and where. */
    record Exported(String text) implements Notice {}

    record ExportFailed(String error) implements Notice {}

    /** {@code E} while an export runs. */
    record ExportRunning() implements Notice {}

    /** A segment of an older commit that the open reader doesn't have (ADR 0051). */
    record NotOpened(String segment) implements Notice {}

    /** {@code D} on the start screen, when there is no {@code runtimes/} to download into (ADR 0080). */
    record CannotDownload(String reason) implements Notice {}

    default Line line() {
        return switch (this) {
            case Reopening _ -> Line.from("Reopening…");
            case Unchanged unchanged -> Line.from("Unchanged: " + unchanged.segmentsFile());
            case Reopened reopened -> Line.from("Reopened: " + reopened.text());
            case Failed failed ->
                Line.styled(
                        "Reopen failed: " + failed.error(), Palette.current().error());
            case NotSupported notSupported ->
                Line.styled(
                        notSupported.what() + ": " + notSupported.reason(),
                        Palette.current().warning());
            case Exported exported ->
                Line.styled(exported.text(), Palette.current().success());
            case ExportFailed failed ->
                Line.styled(
                        "Export failed: " + failed.error(), Palette.current().error());
            case ExportRunning _ -> Line.from("An export runs; Esc cancels it");
            case NotOpened notOpened ->
                Line.styled(
                        notOpened.segment() + " isn't in the opened commit",
                        Palette.current().warning());
            case CannotDownload cannot ->
                Line.styled(
                        "Cannot download runtimes: " + cannot.reason(),
                        Palette.current().warning());
        };
    }

    /**
     * Whether the notice takes the footer, which is wider than the tab line: the outcome of an export
     * names its file, and the start screen has no tab line.
     */
    default boolean inFooter() {
        return this instanceof Exported || this instanceof ExportFailed || this instanceof CannotDownload;
    }
}
