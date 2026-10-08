package io.github.mfuerstenau.leia.protocol.index;

import java.util.Optional;

/**
 * Params of {@code openIndex}.
 *
 * @param path the index directory, or a directory with indexes below it (ADR 0021); a relative path
 *     is resolved against the server's working directory, which is the TUI's
 * @param directoryImpl the {@code Directory} to read it through (ADR 0020); absent means {@link
 *     DirectoryImpl#FS}
 * @param mode how to open it (ADR 0021); absent means {@link OpenMode#READER}
 */
public record OpenIndexParams(String path, Optional<DirectoryImpl> directoryImpl, Optional<OpenMode> mode) {

    /** Opens {@code path} with a reader through {@link DirectoryImpl#FS}. */
    public OpenIndexParams(String path) {
        this(path, Optional.empty(), Optional.empty());
    }
}
