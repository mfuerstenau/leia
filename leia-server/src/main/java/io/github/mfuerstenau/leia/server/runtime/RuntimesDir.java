package io.github.mfuerstenau.leia.server.runtime;

import java.nio.file.Path;

/**
 * One folder that runtimes are looked up in (ADR 0122), such as {@code runtimes/} beside {@code
 * leia.jar} or in a data folder.
 *
 * @param path the folder, which holds a folder for each runtime
 * @param system whether it is a system-wide one, such as {@code /usr/share/leia/runtimes}, which
 *     other users may be able to write to: every jar of it is checked against the SHA-256 and size
 *     that the adapter lists, and leia never rewrites it
 */
public record RuntimesDir(Path path, boolean system) {

    /** A folder of the user's own. */
    public static RuntimesDir user(Path path) {
        return new RuntimesDir(path, false);
    }
}
