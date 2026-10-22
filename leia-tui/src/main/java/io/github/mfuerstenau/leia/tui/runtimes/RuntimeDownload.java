package io.github.mfuerstenau.leia.tui.runtimes;

import io.github.mfuerstenau.leia.server.runtime.RuntimeFolders;
import java.io.PrintStream;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * What {@code leia --download-runtimes} does (ADR 0077, ADR 0079): with nothing to download, it
 * refreshes leia's own jars without asking; with {@code --yes}, it lists the downloads and installs
 * them; on a terminal, the {@link RuntimeDialog} lets the user choose; without one, it downloads
 * nothing.
 */
public final class RuntimeDownload {

    private RuntimeDownload() {}

    /** Lets the user choose and install; empty if they downloaded nothing. */
    @FunctionalInterface
    public interface Dialog {

        Optional<RuntimeInstall.Outcome> run(
                RuntimeInstall install, RuntimeInstall.Plan plan, Set<RuntimeFolders.Known> initiallyChecked)
                throws Exception;
    }

    /**
     * @param folders the folders of {@code --runtime}, whose runtimes the dialogue checks at first;
     *     every runtime leia knows if empty
     * @param yes {@code --yes}: download without asking
     * @param terminal whether there is a terminal to ask on
     * @return the exit status: 0, or 1 if a runtime failed or nothing was downloaded that should be
     */
    public static int run(
            RuntimeInstall install,
            List<String> folders,
            boolean yes,
            boolean terminal,
            Dialog dialog,
            PrintStream out,
            PrintStream err)
            throws Exception {
        List<RuntimeFolders.Known> runtimes = RuntimeFolders.KNOWN.stream()
                .filter(known -> folders.isEmpty() || folders.contains(known.folder()))
                .toList();
        boolean named = !folders.isEmpty();
        RuntimeInstall.Plan plan;
        try {
            plan = install.plan(runtimes);
        } catch (RuntimeInstall.InstallException e) {
            err.println("leia: " + e.getMessage());
            return 1;
        }
        Set<RuntimeFolders.Known> needing = new LinkedHashSet<>();
        plan.needingDownload().forEach(runtime -> needing.add(runtime.known()));
        RuntimeInstall.Outcome outcome;
        if (needing.isEmpty()) {
            out.println("Every Lucene jar is there and matches; nothing to download.");
            outcome = install.install(plan, Set.of(), RuntimeInstall.Listener.printing(out, err), () -> false);
        } else if (yes) {
            install.printListing(plan, needing, out);
            out.println("--yes given; downloading.");
            outcome = install.install(plan, needing, RuntimeInstall.Listener.printing(out, err), () -> false);
        } else if (!terminal) {
            err.println("leia: nothing downloaded: --download-runtimes asks before it downloads, and there is no"
                    + " terminal to ask on; --yes downloads without asking");
            return 1;
        } else {
            Optional<RuntimeInstall.Outcome> chosen = dialog.run(install, plan, named ? needing : Set.of());
            if (chosen.isEmpty()) {
                err.println("leia: nothing downloaded");
                return 1;
            }
            outcome = chosen.get();
            outcome.summary().forEach(out::println);
        }
        if (outcome.status() == 0) {
            out.println("Installed the runtimes into " + install.runtimesDir());
        }
        return outcome.status();
    }
}
