package io.github.mfuerstenau.leia.client.runtimes;

import java.net.URI;
import java.util.List;
import java.util.Set;

/**
 * Asked, on the thread that installs, what to do after a jar's download from a mirror failed (ADR
 * 0124): retry it from a mirror, which is then the one the install goes on with; give up on the jar's
 * runtime and go on with the others; or stop the install.
 */
@FunctionalInterface
public interface MirrorChooser {

    /**
     * A jar's download that failed.
     *
     * @param folder the runtime's folder, such as {@code lucene9}
     * @param jar the jar's name
     * @param mirror the mirror it was downloaded from
     * @param reason why it failed, short, such as {@code HTTP 404}
     * @param message why it failed, as a sentence that names the jar and its URL
     */
    record Failure(String folder, String jar, Mirror mirror, String reason, String message) {}

    /** What to do after a failure. */
    sealed interface Choice {

        /** Download the jar again, from {@code mirror}, and the jars after it too. */
        record Retry(Mirror mirror) implements Choice {}

        /** The jar's runtime fails; the install goes on with the next runtime. */
        record Skip() implements Choice {}

        /** The jar's runtime fails, and the runtimes not begun yet are cancelled. */
        record Abort() implements Choice {}
    }

    /**
     * @param failure the download that failed
     * @param mirrors the mirrors of the install, in order
     * @param tried the mirrors this jar failed from, the one of {@code failure} included
     */
    Choice choose(Failure failure, List<Mirror> mirrors, Set<URI> tried);

    /**
     * Without asking: the next mirror that the jar hasn't failed from, in order, and once it failed
     * from each, the runtime fails and the install goes on, as for a script.
     */
    static MirrorChooser fallback() {
        return (failure, mirrors, tried) -> mirrors.stream()
                .filter(mirror -> !tried.contains(mirror.uri()))
                .findFirst()
                .<Choice>map(Choice.Retry::new)
                .orElseGet(Choice.Skip::new);
    }
}
