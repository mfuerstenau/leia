package io.github.mfuerstenau.leia.mcp;

import io.github.mfuerstenau.leia.protocol.index.DirectoryImpl;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexResult;
import java.io.File;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * What MCP mode keeps between requests (ADR 0100 §7): the {@code Directory} of {@code --directory},
 * the user's home for a leading {@code ~}, the roots of {@code --mcp-root} that {@code open_index}
 * is held to (ADR 0102), and what {@code openIndex} said of the open index, which {@code
 * getIndexStats} doesn't say.
 */
public final class Session {

    private static final Logger LOG = Logger.getLogger(Session.class.getName());

    private final DirectoryImpl defaultDirectory;
    private final Optional<Path> home;
    private final List<Path> roots;

    /** Set by {@code open_index}, which runs alone (ADR 0101), or before the loop starts. */
    // An immutable value, published through volatile (ADR 0088)
    @SuppressWarnings("java:S3077")
    private volatile Optional<OpenIndexResult> opened = Optional.empty();

    /**
     * @param roots real paths, below which alone {@code open_index} opens; none for any path
     */
    public Session(DirectoryImpl defaultDirectory, Optional<Path> home, List<Path> roots) {
        this.defaultDirectory = defaultDirectory;
        this.home = home;
        this.roots = List.copyOf(roots);
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

    /**
     * The path that {@code open_index} opens for {@code path} (ADR 0102): with a leading {@code ~}
     * expanded, and with roots, its real path, which must be one of them or below one. The backend
     * opens the real path, so a symbolic link changed after the check doesn't lead elsewhere.
     *
     * @throws ToolException if there are roots and {@code path} isn't below one: the same message
     *     whether it exists or not, so that the refusal says nothing of the paths outside them
     */
    public String allowed(String path) throws ToolException {
        String expanded = expandHome(path);
        if (roots.isEmpty()) {
            return expanded;
        }
        try {
            Path real = Path.of(expanded).toRealPath();
            if (roots.stream().anyMatch(real::startsWith)) {
                return real.toString();
            }
            LOG.info(() -> "open_index refused " + real + ": not below a root");
        } catch (IOException | InvalidPathException e) {
            LOG.log(Level.INFO, e, () -> "open_index refused " + expanded);
        }
        throw ToolError.invalid("open_index opens only paths below "
                + roots.stream().map(Path::toString).collect(Collectors.joining(", ")));
    }
}
