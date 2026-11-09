package io.github.mfuerstenau.leia.client.runtimes;

import io.github.mfuerstenau.leia.server.runtime.RuntimeFolders;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;

/**
 * An install of runtimes on a thread of its own, as a handle that a UI polls (ADR 0126): {@link
 * #snapshot} says how far it is, {@link #choose} answers the question after a failed download (ADR
 * 0124), and {@link #cancel} stops it cooperatively, leaving no unfinished file behind (ADR 0080). It
 * is not a backend job: no index is involved, and the backend thread stays free (ADR 0080 §4).
 *
 * <p>Every method may be called from any thread.
 */
public final class InstallJob {

    private static final Logger LOG = Logger.getLogger(InstallJob.class.getName());

    /** Where the install is. */
    public enum State {
        /** Not started yet. */
        READY,
        RUNNING,
        /** A jar's download failed, and the install waits for {@link #choose}. */
        WAITING_FOR_MIRROR,
        /** Ended: {@link Snapshot#outcome} says how. */
        DONE
    }

    /** Where a Lucene jar is. */
    public enum JarState {
        WAITING,
        ACTIVE,
        DOWNLOADED,
        /** Copied from a local cache (ADR 0125). */
        COPIED,
        /** There already, and it matches. */
        KEPT,
        FAILED,
        CANCELLED
    }

    /**
     * A Lucene jar of a runtime the install takes on.
     *
     * @param size its size, as the list has it
     * @param bytes the bytes of its download so far; a retry starts at 0
     * @param source the mirror it was downloaded from, or the cache it was copied from; null before
     * @param failures a line per failed download, such as {@code Google Cloud, EU: HTTP 404}
     */
    public record JarProgress(
            String folder,
            String name,
            long size,
            long bytes,
            JarState state,
            @Nullable String source,
            List<String> failures) {

        public JarProgress {
            failures = List.copyOf(failures);
        }
    }

    /**
     * A runtime the install takes on.
     *
     * @param failure why it failed, once it did; else null
     * @param jars its Lucene jars, in the list's order
     */
    public record RuntimeProgress(
            RuntimeInstall.RuntimePlan plan, @Nullable String failure, List<JarProgress> jars) {

        public RuntimeProgress {
            jars = List.copyOf(jars);
        }

        public RuntimeFolders.Known known() {
            return plan.known();
        }
    }

    /**
     * The question after a failed download: which mirror to retry it from, or to skip its runtime
     * or stop the install.
     *
     * @param mirrors the install's mirrors, in order
     * @param tried the mirrors the jar failed from, the one of {@code failure} included
     */
    public record Question(MirrorChooser.Failure failure, List<Mirror> mirrors, Set<URI> tried) {

        public Question {
            mirrors = List.copyOf(mirrors);
            tried = Set.copyOf(tried);
        }
    }

    /**
     * How far the install is, copied, so that it doesn't change.
     *
     * @param cancelled whether {@link #cancel} was called
     * @param runtimes the runtimes it takes on, in the plan's order
     * @param jarCount the jars to download
     * @param total the bytes to download
     * @param done the bytes downloaded so far
     * @param question what the install waits for, in {@link State#WAITING_FOR_MIRROR}; else null
     * @param outcome how it ended, once {@link State#DONE}; else null
     */
    public record Snapshot(
            State state,
            boolean cancelled,
            List<RuntimeProgress> runtimes,
            int jarCount,
            long total,
            long done,
            @Nullable Question question,
            RuntimeInstall.@Nullable Outcome outcome) {

        public Snapshot {
            runtimes = List.copyOf(runtimes);
        }

        /** The jar that downloads now, if one does. */
        public Optional<JarProgress> active() {
            return runtimes.stream()
                    .flatMap(runtime -> runtime.jars().stream())
                    .filter(jar -> jar.state() == JarState.ACTIVE)
                    .findFirst();
        }
    }

    private final RuntimeInstall install;
    private final RuntimeInstall.Plan plan;
    private final Set<RuntimeFolders.Known> chosen;
    private final RuntimeInstall.Listener also;
    private final @Nullable MirrorChooser chooser;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final CompletableFuture<RuntimeInstall.Outcome> done = new CompletableFuture<>();
    private final Progress progress;

    // Guarded by this.
    private State state = State.READY;
    private @Nullable Question question;
    private @Nullable CompletableFuture<MirrorChooser.Choice> answer;

    private InstallJob(
            RuntimeInstall install,
            RuntimeInstall.Plan plan,
            Set<RuntimeFolders.Known> chosen,
            RuntimeInstall.Listener also,
            @Nullable MirrorChooser chooser) {
        this.install = install;
        this.plan = plan;
        this.chosen = Set.copyOf(chosen);
        this.also = also;
        this.chooser = chooser;
        // As RuntimeInstall#install takes them on: those without downloads, and the chosen ones.
        this.progress = new Progress(plan.runtimes().stream()
                .filter(runtime -> !runtime.needsDownload() || this.chosen.contains(runtime.known()))
                .toList());
    }

    /**
     * An install of {@code chosen} of {@code plan}, and of the runtimes of it that need no download,
     * which only refreshes leia's own jars. After a failed download it asks through {@link
     * Snapshot#question}, and waits for {@link #choose}.
     */
    public static InstallJob of(RuntimeInstall install, RuntimeInstall.Plan plan, Set<RuntimeFolders.Known> chosen) {
        return new InstallJob(install, plan, chosen, new RuntimeInstall.Listener() {}, null);
    }

    /**
     * {@link #of(RuntimeInstall, RuntimeInstall.Plan, Set)}, telling {@code also} how it goes, and
     * asking {@code chooser} on the install's thread after a failed download, as the command line does.
     */
    public static InstallJob of(
            RuntimeInstall install,
            RuntimeInstall.Plan plan,
            Set<RuntimeFolders.Known> chosen,
            RuntimeInstall.Listener also,
            MirrorChooser chooser) {
        return new InstallJob(install, plan, chosen, also, chooser);
    }

    /** The install this job runs. */
    public RuntimeInstall install() {
        return install;
    }

    /**
     * Starts the install on the daemon thread {@code leia-runtime-install}.
     *
     * @return this job
     * @throws IllegalStateException if it was started before
     */
    public InstallJob start() {
        synchronized (this) {
            if (state != State.READY) {
                throw new IllegalStateException("The install was started before");
            }
            state = State.RUNNING;
        }
        Thread.ofPlatform().daemon().name("leia-runtime-install").start(this::run);
        return this;
    }

    /** How far the install is. */
    public Snapshot snapshot() {
        synchronized (this) {
            return progress.snapshot(state, cancelled.get(), question, done.getNow(null));
        }
    }

    /**
     * Answers the question of {@link Snapshot#question}: the install goes on as {@code choice} says.
     *
     * @throws IllegalStateException if the install doesn't wait for an answer
     */
    public void choose(MirrorChooser.Choice choice) {
        CompletableFuture<MirrorChooser.Choice> waiting;
        synchronized (this) {
            if (answer == null) {
                throw new IllegalStateException("The install doesn't wait for a mirror");
            }
            waiting = answer;
            answer = null;
            question = null;
            state = State.RUNNING;
        }
        waiting.complete(choice);
    }

    /**
     * Stops the install: the current jar at its next buffer, the runtimes not begun yet aren't begun,
     * and a question that waits is answered with {@link MirrorChooser.Choice.Abort}. Before {@link
     * #start}, every runtime is cancelled once it starts.
     */
    public void cancel() {
        cancelled.set(true);
        synchronized (this) {
            if (answer != null) {
                answer.complete(new MirrorChooser.Choice.Abort());
                answer = null;
                question = null;
                state = State.RUNNING;
            }
        }
    }

    /** Completes with the outcome once the install has ended; never exceptionally. */
    public CompletableFuture<RuntimeInstall.Outcome> done() {
        return done;
    }

    /**
     * Waits for the install to end.
     *
     * @throws IllegalStateException if it wasn't started
     */
    public RuntimeInstall.Outcome await() {
        synchronized (this) {
            if (state == State.READY) {
                throw new IllegalStateException("The install wasn't started");
            }
        }
        return done.join();
    }

    private void run() {
        RuntimeInstall.Outcome outcome;
        try {
            outcome = install.install(
                    plan, chosen, new Both(progress, also), cancelled::get, chooser != null ? chooser : this::ask);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "The runtimes' install failed", e);
            outcome = failedOutcome(chosen, e);
        }
        synchronized (this) {
            progress.finish();
            state = State.DONE;
            question = null;
            answer = null;
        }
        done.complete(outcome);
    }

    /** On the install's thread: asks through the snapshot, and waits for the answer. */
    private MirrorChooser.Choice ask(MirrorChooser.Failure failure, List<Mirror> mirrors, Set<URI> tried) {
        CompletableFuture<MirrorChooser.Choice> waiting = new CompletableFuture<>();
        synchronized (this) {
            if (cancelled.get()) {
                return new MirrorChooser.Choice.Abort();
            }
            question = new Question(failure, mirrors, tried);
            answer = waiting;
            state = State.WAITING_FOR_MIRROR;
        }
        try {
            return waiting.get();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return new MirrorChooser.Choice.Abort();
        } catch (ExecutionException e) {
            LOG.log(Level.WARNING, "No mirror was chosen", e);
            return new MirrorChooser.Choice.Abort();
        }
    }

    /** How an install that threw ends: every chosen runtime failed, for {@code error}. */
    static RuntimeInstall.Outcome failedOutcome(Set<RuntimeFolders.Known> chosen, RuntimeException error) {
        Map<RuntimeFolders.Known, RuntimeInstall.Result> failed = new LinkedHashMap<>();
        chosen.forEach(known -> failed.put(known, new RuntimeInstall.Result.Failed(String.valueOf(error))));
        return new RuntimeInstall.Outcome(failed);
    }

    /** Tells two listeners. */
    private record Both(RuntimeInstall.Listener first, RuntimeInstall.Listener second)
            implements RuntimeInstall.Listener {

        @Override
        public void jarStarted(String folder, String jar, long size) {
            first.jarStarted(folder, jar, size);
            second.jarStarted(folder, jar, size);
        }

        @Override
        public void bytes(long n) {
            first.bytes(n);
            second.bytes(n);
        }

        @Override
        public void jarDone(String folder, String jar, RuntimeInstall.JarDone how, long size) {
            first.jarDone(folder, jar, how, size);
            second.jarDone(folder, jar, how, size);
        }

        @Override
        public void jarDownloaded(String folder, String jar, long size, Mirror mirror) {
            first.jarDownloaded(folder, jar, size, mirror);
            second.jarDownloaded(folder, jar, size, mirror);
        }

        @Override
        public void jarCopied(String folder, String jar, long size, LocalCaches.Local local) {
            first.jarCopied(folder, jar, size, local);
            second.jarCopied(folder, jar, size, local);
        }

        @Override
        public void mirrorFailed(MirrorChooser.Failure failure) {
            first.mirrorFailed(failure);
            second.mirrorFailed(failure);
        }

        @Override
        public void runtimeFailed(String folder, String message) {
            first.runtimeFailed(folder, message);
            second.runtimeFailed(folder, message);
        }
    }

    /** The state of each jar, written by the install's thread and copied into snapshots. */
    private static final class Progress implements RuntimeInstall.Listener {

        private static final class Jar {
            final String folder;
            final String name;
            final long size;
            JarState state = JarState.WAITING;
            long bytes;

            @Nullable
            String source;

            final List<String> failures = new ArrayList<>();

            Jar(String folder, String name, long size) {
                this.folder = folder;
                this.name = name;
                this.size = size;
            }

            JarProgress copy() {
                return new JarProgress(folder, name, size, bytes, state, source, failures);
            }
        }

        private final List<RuntimeInstall.RuntimePlan> runtimes;
        private final Map<String, Jar> jars = new LinkedHashMap<>();
        private final Map<String, String> failures = new LinkedHashMap<>();
        private final int jarCount;
        private final long total;
        private long done;
        private @Nullable Jar active;

        Progress(List<RuntimeInstall.RuntimePlan> runtimes) {
            this.runtimes = List.copyOf(runtimes);
            long sum = 0;
            int count = 0;
            for (RuntimeInstall.RuntimePlan runtime : runtimes) {
                for (RuntimeFolders.ListedJar jar : runtime.listed()) {
                    if (jar.mavenPath() != null) {
                        jars.put(
                                key(runtime.known().folder(), jar.name()),
                                new Jar(runtime.known().folder(), jar.name(), jar.size()));
                    }
                }
                sum += runtime.downloadSize();
                count += runtime.downloads().size();
            }
            total = sum;
            jarCount = count;
        }

        private static String key(String folder, String jar) {
            return folder + "/" + jar;
        }

        synchronized Snapshot snapshot(
                State state, boolean cancelled, @Nullable Question question, RuntimeInstall.@Nullable Outcome outcome) {
            List<RuntimeProgress> copies = new ArrayList<>();
            for (RuntimeInstall.RuntimePlan runtime : runtimes) {
                String folder = runtime.known().folder();
                copies.add(new RuntimeProgress(
                        runtime,
                        failures.get(folder),
                        jars.values().stream()
                                .filter(jar -> jar.folder.equals(folder))
                                .map(Jar::copy)
                                .toList()));
            }
            return new Snapshot(state, cancelled, copies, jarCount, total, done, question, outcome);
        }

        @Override
        public synchronized void jarStarted(String folder, String jar, long size) {
            active = jars.get(key(folder, jar));
            if (active != null) {
                // A retry starts over: the bytes of the failed try don't count.
                done -= active.bytes;
                active.state = JarState.ACTIVE;
                active.bytes = 0;
            }
        }

        @Override
        public synchronized void jarDownloaded(String folder, String jar, long size, Mirror mirror) {
            Jar found = jars.get(key(folder, jar));
            if (found != null) {
                found.source = mirror.name();
            }
            jarDone(folder, jar, RuntimeInstall.JarDone.DOWNLOADED, size);
        }

        @Override
        public synchronized void jarCopied(String folder, String jar, long size, LocalCaches.Local local) {
            Jar found = jars.get(key(folder, jar));
            if (found != null) {
                found.source = local.cache().name();
            }
            jarDone(folder, jar, RuntimeInstall.JarDone.COPIED, size);
        }

        @Override
        public synchronized void mirrorFailed(MirrorChooser.Failure failure) {
            Jar found = jars.get(key(failure.folder(), failure.jar()));
            if (found == null) {
                return;
            }
            found.state = JarState.FAILED;
            found.failures.add(failure.mirror().name() + ": " + failure.reason());
            if (found == active) {
                active = null;
            }
        }

        @Override
        public synchronized void bytes(long n) {
            done += n;
            if (active != null) {
                active.bytes += n;
            }
        }

        @Override
        public synchronized void jarDone(String folder, String jar, RuntimeInstall.JarDone how, long size) {
            Jar found = jars.get(key(folder, jar));
            if (found == null) {
                return;
            }
            switch (how) {
                case DOWNLOADED -> found.state = JarState.DOWNLOADED;
                case COPIED -> found.state = JarState.COPIED;
                case KEPT -> found.state = JarState.KEPT;
                default -> {
                    // Leia's own jars and removed ones aren't Lucene jars.
                }
            }
            if (found == active) {
                active = null;
            }
        }

        @Override
        public synchronized void runtimeFailed(String folder, String message) {
            failures.put(folder, message);
            if (active != null && active.folder.equals(folder)) {
                active.state = JarState.FAILED;
                active = null;
            }
        }

        /** The install has ended: a jar still active was cancelled. */
        synchronized void finish() {
            for (Jar jar : jars.values()) {
                if (jar.state == JarState.ACTIVE) {
                    jar.state = JarState.CANCELLED;
                }
            }
            active = null;
        }
    }
}
