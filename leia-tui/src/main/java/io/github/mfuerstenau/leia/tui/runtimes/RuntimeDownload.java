package io.github.mfuerstenau.leia.tui.runtimes;

import io.github.mfuerstenau.leia.server.runtime.RuntimeFolders;
import java.io.PrintStream;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What {@code leia --download-runtimes --runtime …} does, for an unattended install (ADR 0077, ADR
 * 0080): with nothing to download, it refreshes leia's own jars; otherwise it lists the downloads and
 * installs them without asking, since naming the runtimes is the consent. The {@link RuntimeWindow}
 * inside leia is where a user chooses.
 */
public final class RuntimeDownload {

    private RuntimeDownload() {}

    /**
     * @param folders the folders of {@code --runtime}, whose runtimes are installed
     * @return the exit status: 0, or 1 if a runtime failed
     */
    public static int run(RuntimeInstall install, List<String> folders, PrintStream out, PrintStream err) {
        List<RuntimeFolders.Known> runtimes = RuntimeFolders.KNOWN.stream()
                .filter(known -> folders.contains(known.folder()))
                .toList();
        RuntimeInstall.Plan plan;
        try {
            plan = install.plan(runtimes);
        } catch (RuntimeInstall.InstallException e) {
            err.println("leia: " + e.getMessage());
            return 1;
        }
        Set<RuntimeFolders.Known> needing = new LinkedHashSet<>();
        plan.needingDownload().forEach(runtime -> needing.add(runtime.known()));
        if (needing.isEmpty()) {
            out.println("Every Lucene jar is there and matches; nothing to download.");
        } else {
            install.printListing(plan, needing, out);
        }
        RuntimeInstall.Outcome outcome =
                install.install(plan, needing, RuntimeInstall.Listener.printing(out, err), () -> false);
        if (outcome.status() == 0) {
            out.println("Installed the runtimes into " + install.runtimesDir());
        }
        return outcome.status();
    }
}
