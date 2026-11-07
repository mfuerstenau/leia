package io.github.mfuerstenau.leia.tui.runtimes;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * What the window of {@code D} needs to know of the mirrors beyond the install's list (ADR 0124).
 *
 * @param problems the mirrors that were left out, and why, such as a bad line of the {@code mirrors}
 *     file; the window names them
 * @param saveFile where the mirror the user chooses is saved, to be tried first next time; empty
 *     keeps it nowhere
 */
public record MirrorSettings(List<String> problems, Optional<Path> saveFile) {

    public MirrorSettings {
        problems = List.copyOf(problems);
    }

    /** Nothing left out, and nowhere to save. */
    public static MirrorSettings none() {
        return new MirrorSettings(List.of(), Optional.empty());
    }
}
