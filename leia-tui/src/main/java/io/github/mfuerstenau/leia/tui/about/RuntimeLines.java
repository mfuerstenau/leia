package io.github.mfuerstenau.leia.tui.about;

import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import io.github.mfuerstenau.leia.client.platform.RuntimesDirs;
import io.github.mfuerstenau.leia.protocol.jars.GetRuntimesResult;
import io.github.mfuerstenau.leia.protocol.jars.RuntimeFolder;
import io.github.mfuerstenau.leia.protocol.jars.RuntimeState;
import io.github.mfuerstenau.leia.protocol.jars.ServerRuntimeInfo;
import io.github.mfuerstenau.leia.protocol.version.VersionResult;
import io.github.mfuerstenau.leia.tui.theme.Palette;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** The lines about the Java and the runtimes in use, which the start screen and the about window share. */
public final class RuntimeLines {

    private RuntimeLines() {}

    /** The Java leia runs on. */
    public static Line java(VersionResult version) {
        return Line.from("Java " + version.java().version() + " ("
                + version.java().vendor() + ") in " + version.java().home());
    }

    /**
     * The loaded runtimes, the missing ones with where to put them, the incomplete and refused ones
     * with why (ADR 0072, ADR 0073), and an empty line after them; none until they are known.
     */
    public static List<Line> runtimes(@Nullable GetRuntimesResult runtimes, RuntimesDirs runtimeFolders) {
        if (runtimes == null) {
            return List.of();
        }
        List<RuntimeFolder> loaded = runtimes.runtimes().stream()
                .filter(runtime -> runtime.state() == RuntimeState.LOADED)
                .toList();
        List<RuntimeFolder> missing = runtimes.runtimes().stream()
                .filter(runtime -> runtime.state() == RuntimeState.MISSING)
                .toList();
        List<Line> lines = new ArrayList<>();
        lines.add(Line.from("Runtimes: "
                + (loaded.isEmpty()
                        ? "none"
                        : "Lucene "
                                + String.join(
                                        ", ",
                                        loaded.stream()
                                                .map(runtime -> runtime.luceneVersion()
                                                                .orElseThrow() + " (" + majors(runtime) + ")")
                                                .toList()))));
        // Where each was found, when it was in more than one place it could have been (ADR 0122).
        for (RuntimeFolder runtime : loaded) {
            runtime.path().ifPresent(path -> lines.add(Line.from("  " + runtime.folder() + " from " + path)));
        }
        for (ServerRuntimeInfo server : runtimes.servers()) {
            lines.add(Line.from(
                    "Server: " + server.label().map(label -> label + ", ").orElse("") + "Lucene "
                            + server.luceneVersion() + " (" + majors(server.indexMajors()) + "), in " + server.home()));
        }
        Style warning = Palette.current().warning();
        if (!missing.isEmpty()) {
            String where = runtimeFolders
                    .dir()
                    .map(dir -> "press D to download them into " + dir)
                    .orElse("(not run from leia.jar)");
            lines.add(Line.styled(
                    "Missing "
                            + String.join(
                                    ", ",
                                    missing.stream()
                                            .map(runtime -> runtime.folder() + " (Lucene " + majors(runtime) + ")")
                                            .toList())
                            + ": " + where,
                    warning));
        }
        for (RuntimeFolder runtime : runtimes.runtimes()) {
            String problem = runtime.problem().orElse("");
            switch (runtime.state()) {
                case INCOMPLETE ->
                    lines.add(Line.styled(
                            "Incomplete runtimes/" + runtime.folder() + ": " + problem
                                    + " is missing; press D to download it",
                            warning));
                case REFUSED ->
                    lines.add(Line.styled("Refused runtimes/" + runtime.folder() + ": " + problem, warning));
                case RESTART ->
                    lines.add(Line.styled(
                            "runtimes/" + runtime.folder() + " is installed; restart leia to load it", warning));
                case LOADED, MISSING -> {}
            }
        }
        if (loaded.isEmpty()) {
            lines.add(Line.styled(
                    "Without runtimes, indexes open only without a reader, and the analysis tab is off.", warning));
        }
        lines.add(Line.empty());
        return lines;
    }

    /** The majors of the indexes a runtime reads, such as {@code 9–10} or {@code 8}. */
    private static String majors(RuntimeFolder runtime) {
        return majors(runtime.indexMajors());
    }

    /** {@code indexMajors}, oldest first, such as {@code 9–10} or {@code 8}. */
    private static String majors(List<Integer> indexMajors) {
        int first = indexMajors.getFirst();
        int last = indexMajors.getLast();
        return first == last ? Integer.toString(first) : first + "–" + last;
    }
}
