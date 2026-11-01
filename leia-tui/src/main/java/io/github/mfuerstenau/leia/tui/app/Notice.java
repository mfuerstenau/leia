package io.github.mfuerstenau.leia.tui.app;

import dev.tamboui.text.Line;
import io.github.mfuerstenau.leia.tui.theme.Palette;
import java.util.List;

/**
 * What the tab line says about the last reopen, or the footer about the last export or theme, until
 * the next key.
 */
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

    /** {@code T} on the start screen switched to the theme {@code id} (ADR 0096). */
    record ThemeChanged(String id) implements Notice {}

    /** Theme files of the user's that leia refused, such as ones from before a slot was added (ADR 0121). */
    record ThemesLeftOut(List<String> reasons) implements Notice {}

    /** The scan of the stored fields failed (ADR 0113). */
    record StoredFieldsFailed(String error) implements Notice {}

    /** {@code b} on the overview, on a field the stored fields scan found no value of (ADR 0114). */
    record NotStored(String field) implements Notice {}

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
            case ThemeChanged changed -> Line.from("Theme: " + changed.id());
            case ThemesLeftOut left ->
                Line.styled(
                        "Theme left out: " + left.reasons().getFirst()
                                + (left.reasons().size() > 1
                                        ? " (+" + (left.reasons().size() - 1) + " more)"
                                        : ""),
                        Palette.current().warning());
            case StoredFieldsFailed failed ->
                Line.styled(
                        "Stored fields failed: " + failed.error(),
                        Palette.current().error());
            case NotStored notStored ->
                Line.styled(
                        "No document stores " + notStored.field(),
                        Palette.current().warning());
        };
    }

    /**
     * Whether the notice takes the footer, which is wider than the tab line: the outcome of an export
     * names its file, and the start screen has no tab line.
     */
    default boolean inFooter() {
        return this instanceof Exported
                || this instanceof ExportFailed
                || this instanceof CannotDownload
                || this instanceof ThemeChanged
                || this instanceof ThemesLeftOut
                || this instanceof StoredFieldsFailed;
    }
}
