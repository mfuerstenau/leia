package io.github.mfuerstenau.leia.protocol.index;

import java.util.Optional;

/**
 * Params of {@code openIndex}.
 *
 * @param path the index directory; a relative path is resolved against the server's working
 *     directory, which is the TUI's
 * @param directoryImpl the {@code Directory} to read it through (ADR 0020); absent means {@link
 *     DirectoryImpl#FS}
 */
public record OpenIndexParams(String path, Optional<DirectoryImpl> directoryImpl) {

    /** Opens {@code path} through {@link DirectoryImpl#FS}. */
    public OpenIndexParams(String path) {
        this(path, Optional.empty());
    }
}
