package io.github.mfuerstenau.leia.client.runtimes;

import io.github.mfuerstenau.leia.server.runtime.RuntimeFolders;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * What a UI needs of the runtimes' install (ADR 0080, ADR 0124, ADR 0126): plans, on a thread of
 * their own, since they hash the jars that are there; install jobs; and the mirror the user chose,
 * which is the first from then on and saved for next time.
 */
public final class RuntimeService {

    /**
     * What {@link #plan} found.
     *
     * @param plan the plan of the runtimes to offer
     * @param present the plans of the runtimes that are there already, which are only shown
     */
    public record Plans(RuntimeInstall.Plan plan, List<RuntimeInstall.RuntimePlan> present) {

        public Plans {
            present = List.copyOf(present);
        }
    }

    private final MirrorSettings settings;
    private volatile RuntimeInstall install;

    public RuntimeService(RuntimeInstall install, MirrorSettings settings) {
        this.install = install;
        this.settings = settings;
    }

    /** The install, with the mirror chosen last first. */
    public RuntimeInstall install() {
        return install;
    }

    /** The mirrors that were left out, and why, such as a bad line of the {@code mirrors} file. */
    public List<String> mirrorProblems() {
        return settings.problems();
    }

    /**
     * Plans {@code runtimes} and {@code present} on the daemon thread {@code leia-runtime-plan}.
     * Writes and downloads nothing. Completes exceptionally with the {@link
     * RuntimeInstall.InstallException} that says why it can't.
     */
    public CompletableFuture<Plans> plan(List<RuntimeFolders.Known> runtimes, List<RuntimeFolders.Known> present) {
        RuntimeInstall current = install;
        CompletableFuture<Plans> plans = new CompletableFuture<>();
        Thread.ofPlatform().daemon().name("leia-runtime-plan").start(() -> {
            try {
                plans.complete(
                        new Plans(current.plan(runtimes), current.plan(present).runtimes()));
            } catch (RuntimeInstall.InstallException | RuntimeException e) {
                plans.completeExceptionally(e);
            }
        });
        return plans;
    }

    /** An install of {@code chosen} of {@code plan}, not started yet, that asks after a failed download. */
    public InstallJob job(RuntimeInstall.Plan plan, Set<RuntimeFolders.Known> chosen) {
        return InstallJob.of(install, plan, chosen);
    }

    /** {@code mirror} is the first from now on, and saved for next time (ADR 0124). */
    public void useMirror(Mirror mirror) {
        install = install.withFirst(mirror);
        settings.saveFile().ifPresent(file -> Mirrors.save(file, mirror));
    }
}
