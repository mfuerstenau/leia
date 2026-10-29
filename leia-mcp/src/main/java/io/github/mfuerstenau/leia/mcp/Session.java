package io.github.mfuerstenau.leia.mcp;

import io.github.mfuerstenau.leia.protocol.index.DirectoryImpl;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexResult;
import java.io.File;
import java.nio.file.Path;
import java.util.Optional;

/**
 * What MCP mode keeps between requests (ADR 0100 §7): the {@code Directory} of {@code --directory},
 * the user's home for a leading {@code ~}, and what {@code openIndex} said of the open index, which
 * {@code getIndexStats} doesn't say.
 */
public final class Session {

    private final DirectoryImpl defaultDirectory;
    private final Optional<Path> home;

    /** Set by {@code open_index}, which runs alone (ADR 0101), or before the loop starts. */
    // An immutable value, published through volatile (ADR 0088)
    @SuppressWarnings("java:S3077")
    private volatile Optional<OpenIndexResult> opened = Optional.empty();

    public Session(DirectoryImpl defaultDirectory, Optional<Path> home) {
        this.defaultDirectory = defaultDirectory;
        this.home = home;
    }

    public DirectoryImpl defaultDirectory() {
        return defaultDirectory;
    }

    /** The last index that was opened, while it is open: a failed open leaves it open. */
    public Optional<OpenIndexResult> opened() {
        return opened;
    }

    public void opened(OpenIndexResult result) {
        opened = Optional.of(result);
    }

    /** {@code path} with a leading {@code ~} replaced by the user's home, as the open prompt does. */
    public String expandHome(String path) {
        if (!path.startsWith("~") || home.isEmpty()) {
            return path;
        }
        String rest = path.substring(1);
        if (rest.isEmpty() || rest.charAt(0) == '/' || rest.charAt(0) == File.separatorChar) {
            return home.get() + rest;
        }
        return path;
    }
}
