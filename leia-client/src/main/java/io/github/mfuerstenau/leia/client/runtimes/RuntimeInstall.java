package io.github.mfuerstenau.leia.client.runtimes;

import io.github.mfuerstenau.leia.client.values.Units;
import io.github.mfuerstenau.leia.server.runtime.RuntimeFolders;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.Serial;
import java.net.ConnectException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;

/**
 * {@code leia --download-runtimes} (ADR 0073): installs runtimes' folders into the runtimes folder,
 * beside {@code leia.jar} or in the user's data folder (ADR 0098). leia's own jars, the adapter and
 * for Lucene 10 leia-core's, come from {@code leia.jar}, which embeds them under {@value
 * RuntimeFolders#EMBEDDED}; Lucene's come from Maven Central, as the adapter's list names them, each
 * checked against the list's SHA-256 and size.
 *
 * <p>It works in two steps (ADR 0079). {@link #plan} reads what {@code leia.jar} has and what is
 * there, and writes and downloads nothing; {@link #install} then installs the runtimes that need
 * no download and those the user chose, and leaves the others alone. A runtime that fails doesn't
 * stop the others, and a cancel stops at the next buffer or jar.
 *
 * <p>A jar that is there and matches is kept; one that doesn't is replaced. A download goes to a
 * temporary file in the folder first, and moves into place only once it matches. Jars in a folder
 * that its list doesn't name, such as those of an older Lucene, are removed.
 */
public final class RuntimeInstall {

    private static final Logger LOG = Logger.getLogger(RuntimeInstall.class.getName());

    /** The suffix of a download that isn't done yet. */
    private static final String PART = ".part";

    private static final int BUFFER = 64 * 1024;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);

    /** How long a mirror may take to answer, and to send the next bytes of a jar (ADR 0124). */
    static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final Path runtimesDir;
    private final List<Mirror> mirrors;
    private final ClassLoader resourceLoader;
    private final Duration timeout;
    private final LocalCaches caches;

    /**
     * @param runtimesDir the runtimes folder (ADR 0098)
     * @param mirrors the mirrors the Lucene jars are downloaded from, in order (ADR 0124); not empty
     * @param resources the class loader that sees {@value RuntimeFolders#EMBEDDED}
     */
    public RuntimeInstall(Path runtimesDir, List<Mirror> mirrors, ClassLoader resources) {
        this(runtimesDir, mirrors, resources, LocalCaches.none());
    }

    /**
     * @param caches the local caches a Lucene jar is copied from before it is downloaded (ADR 0125)
     */
    public RuntimeInstall(Path runtimesDir, List<Mirror> mirrors, ClassLoader resources, LocalCaches caches) {
        this(runtimesDir, mirrors, resources, TIMEOUT, caches);
    }

    /** With one mirror, {@code base}, as the tests have it. */
    public RuntimeInstall(Path runtimesDir, URI base, ClassLoader resources) {
        this(runtimesDir, List.of(Mirror.of(base, Mirror.Origin.OPTION)), resources);
    }

    /**
     * @param timeout how long a mirror may take to answer, and to send the next bytes of a jar
     */
    RuntimeInstall(Path runtimesDir, List<Mirror> mirrors, ClassLoader resources, Duration timeout) {
        this(runtimesDir, mirrors, resources, timeout, LocalCaches.none());
    }

    private RuntimeInstall(
            Path runtimesDir, List<Mirror> mirrors, ClassLoader resources, Duration timeout, LocalCaches caches) {
        if (mirrors.isEmpty()) {
            throw new IllegalArgumentException("no mirror");
        }
        this.runtimesDir = runtimesDir;
        this.mirrors = List.copyOf(mirrors);
        this.resourceLoader = resources;
        this.timeout = timeout;
        this.caches = caches;
    }

    /** The folder {@code runtimes}. */
    public Path runtimesDir() {
        return runtimesDir;
    }

    /** The mirrors the Lucene jars are downloaded from, in order: the first is tried first. */
    public List<Mirror> mirrors() {
        return mirrors;
    }

    /** The first mirror's base. */
    public URI repository() {
        return mirrors.getFirst().uri();
    }

    /** This install with {@code first} as its first mirror, and its other mirrors after it. */
    public RuntimeInstall withFirst(Mirror first) {
        return new RuntimeInstall(runtimesDir, Mirrors.withFirst(mirrors, first), resourceLoader, timeout, caches);
    }

    /** A failure that ends a plan, or a runtime's install, with what to say about it. */
    public static final class InstallException extends Exception {

        @Serial
        private static final long serialVersionUID = 1L;

        InstallException(String message) {
            super(message);
        }
    }

    /** The install was cancelled. */
    private static final class CancelledException extends Exception {

        @Serial
        private static final long serialVersionUID = 1L;
    }

    /** The user stopped the install after a failed download; {@code message} says why it failed. */
    private static final class AbortedException extends Exception {

        @Serial
        private static final long serialVersionUID = 1L;

        AbortedException(String message) {
            super(message);
        }
    }

    /** One download from one mirror failed, for {@code reason}; another mirror may do. */
    private static final class DownloadFailed extends Exception {

        @Serial
        private static final long serialVersionUID = 1L;

        private final String reason;

        DownloadFailed(String message, String reason) {
            super(message);
            this.reason = reason;
        }
    }

    /**
     * A Lucene jar to download.
     *
     * @param folder the runtime's folder, such as {@code lucene9}
     * @param jar the jar as the adapter's list names it
     * @param uri where it is downloaded from
     * @param local a copy in a local cache that matches, which is copied instead (ADR 0125); null if
     *     no cache has one
     */
    public record Download(String folder, RuntimeFolders.ListedJar jar, URI uri, LocalCaches.@Nullable Local local) {

        /** A jar that no local cache has. */
        public Download(String folder, RuntimeFolders.ListedJar jar, URI uri) {
            this(folder, jar, uri, null);
        }
    }

    /**
     * A runtime's folder as {@code leia.jar} has it, and what of it needs downloading.
     *
     * @param known the runtime
     * @param adapter its adapter jar, from {@code leia.jar}
     * @param listed the jars its adapter lists, in order
     * @param downloads the Lucene jars that aren't there or don't match
     * @param kept the Lucene jars that are there and match
     */
    // Never compared, hashed or used as a key (ADR 0088)
    @SuppressWarnings("java:S6218")
    public record RuntimePlan(
            RuntimeFolders.Known known,
            byte[] adapter,
            List<RuntimeFolders.ListedJar> listed,
            List<Download> downloads,
            List<RuntimeFolders.ListedJar> kept) {

        public RuntimePlan {
            listed = List.copyOf(listed);
            downloads = List.copyOf(downloads);
            kept = List.copyOf(kept);
        }

        /** Whether a Lucene jar of it needs downloading. */
        public boolean needsDownload() {
            return !downloads.isEmpty();
        }

        /** The bytes to download: the jars that no local cache has. */
        public long downloadSize() {
            return downloads.stream()
                    .filter(download -> download.local() == null)
                    .mapToLong(download -> download.jar().size())
                    .sum();
        }

        /** The jars to download that a local cache has, which are copied instead (ADR 0125). */
        public List<Download> localCopies() {
            return downloads.stream()
                    .filter(download -> download.local() != null)
                    .toList();
        }

        /** leia's own jars, which come from {@code leia.jar}: the adapter first. */
        public List<String> ownJars() {
            List<String> own = new ArrayList<>();
            own.add(known.adapterJar());
            listed.stream().filter(jar -> jar.mavenPath().isEmpty()).forEach(jar -> own.add(jar.name()));
            return own;
        }
    }

    /** What {@link #plan} found, one entry per runtime in the order asked for. */
    public record Plan(List<RuntimePlan> runtimes) {

        public Plan {
            runtimes = List.copyOf(runtimes);
        }

        /** The runtimes that need downloads, in order. */
        public List<RuntimePlan> needingDownload() {
            return runtimes.stream().filter(RuntimePlan::needsDownload).toList();
        }
    }

    /** How a runtime's install ended. */
    public sealed interface Result {

        /** Its folder is complete. */
        record Installed() implements Result {}

        /** It failed, for {@code message}. */
        record Failed(String message) implements Result {}

        /** It was cancelled before it was done, or before it began. */
        record Cancelled() implements Result {}
    }

    /** How each runtime that {@link #install} took on ended, in the plan's order. */
    public record Outcome(Map<RuntimeFolders.Known, Result> results) {

        public Outcome {
            results = Collections.unmodifiableMap(new LinkedHashMap<>(results));
        }

        /** The exit status: 0 if every runtime is installed, else 1. */
        public int status() {
            return results.values().stream().allMatch(Result.Installed.class::isInstance) ? 0 : 1;
        }

        /** A line per runtime, such as {@code lucene9: installed}. */
        public List<String> summary() {
            List<String> lines = new ArrayList<>();
            results.forEach((known, result) -> lines.add(known.folder() + ": "
                    + switch (result) {
                        case Result.Installed _ -> "installed";
                        case Result.Failed failed -> "failed: " + failed.message();
                        case Result.Cancelled _ -> "cancelled";
                    }));
            return lines;
        }
    }

    /** What happened to a jar. */
    public enum JarDone {
        /** One of leia's, written from {@code leia.jar}. */
        WRITTEN,
        /** Downloaded, and it matches. */
        DOWNLOADED,
        /** Copied from a local cache, and it matches (ADR 0125). */
        COPIED,
        /** There already, and it matches. */
        KEPT,
        /** Not listed, or an unfinished download, and removed. */
        REMOVED
    }

    /** Hears how the install goes, on the thread that installs. */
    public interface Listener {

        /** A download of {@code size} bytes begins. */
        default void jarStarted(String folder, String jar, long size) {}

        /** {@code n} more bytes of the current download arrived. */
        default void bytes(long n) {}

        /** A jar is done; {@code size} is its size, or 0 for a removed one. */
        default void jarDone(String folder, String jar, JarDone how, long size) {}

        /** A jar was downloaded from {@code mirror}, and it matches; tells {@link #jarDone} too. */
        default void jarDownloaded(String folder, String jar, long size, Mirror mirror) {
            jarDone(folder, jar, JarDone.DOWNLOADED, size);
        }

        /** A jar was copied from a local cache, and it matches; tells {@link #jarDone} too. */
        default void jarCopied(String folder, String jar, long size, LocalCaches.Local local) {
            jarDone(folder, jar, JarDone.COPIED, size);
        }

        /** A jar's download from a mirror failed; the {@link MirrorChooser} is asked next. */
        default void mirrorFailed(MirrorChooser.Failure failure) {}

        /** A runtime failed, for {@code message}; the others go on. */
        default void runtimeFailed(String folder, String message) {}

        /**
         * A line per jar to {@code out}, with the mirror of each download, and a line per failure to
         * {@code err}, also to {@code log}.
         */
        static Listener printing(PrintStream out, PrintStream err) {
            return new Listener() {
                private String lastFailure = "";

                @Override
                public void jarDone(String folder, String jar, JarDone how, long size) {
                    String label = folder + "/" + jar + ": ";
                    out.println(
                            switch (how) {
                                case WRITTEN -> label + "written, " + Units.size(size);
                                case DOWNLOADED -> label + "downloaded, " + Units.size(size);
                                case COPIED -> label + "copied, " + Units.size(size);
                                case KEPT -> label + "kept";
                                case REMOVED -> label + "removed";
                            });
                }

                @Override
                public void jarDownloaded(String folder, String jar, long size, Mirror mirror) {
                    out.println(folder + "/" + jar + ": downloaded, " + Units.size(size) + ", from " + mirror.name());
                }

                @Override
                public void jarCopied(String folder, String jar, long size, LocalCaches.Local local) {
                    out.println(folder + "/" + jar + ": copied, " + Units.size(size) + ", from "
                            + local.cache().name());
                }

                @Override
                public void mirrorFailed(MirrorChooser.Failure failure) {
                    lastFailure = failure.message();
                    err.println("leia: " + failure.message() + " ("
                            + failure.mirror().name() + ")");
                }

                @Override
                public void runtimeFailed(String folder, String message) {
                    // A runtime that failed on one mirror's download was named just now.
                    if (!message.equals(lastFailure)) {
                        err.println("leia: " + message);
                    }
                }
            };
        }

        /** A line per jar written to {@code log}, and a warning per failure. */
        static Listener logging(Logger log) {
            return new Listener() {
                @Override
                public void jarDone(String folder, String jar, JarDone how, long size) {
                    if (how == JarDone.WRITTEN) {
                        log.info(() -> "Refreshed the runtime's jar " + folder + "/" + jar + " from leia.jar");
                    }
                }

                @Override
                public void runtimeFailed(String folder, String message) {
                    log.warning(() -> "Cannot refresh the runtime " + folder + ": " + message);
                }
            };
        }
    }

    /**
     * Reads the folders of {@code runtimes} from {@code leia.jar}, and finds the Lucene jars that
     * aren't there or don't match. Writes and downloads nothing.
     *
     * @throws InstallException if {@code leia.jar} lacks a runtime's jars, or a jar can't be read
     */
    public Plan plan(List<RuntimeFolders.Known> runtimes) throws InstallException {
        List<RuntimePlan> plans = new ArrayList<>();
        for (RuntimeFolders.Known known : runtimes) {
            plans.add(plan(known));
        }
        return new Plan(plans);
    }

    private RuntimePlan plan(RuntimeFolders.Known known) throws InstallException {
        String folderName = known.folder();
        byte[] adapter = resource(folderName, known.adapterJar());
        List<RuntimeFolders.ListedJar> listed = list(folderName, known.adapterJar(), adapter);
        Path folder = runtimesDir.resolve(folderName);
        List<Download> downloads = new ArrayList<>();
        List<RuntimeFolders.ListedJar> kept = new ArrayList<>();
        for (RuntimeFolders.ListedJar jar : listed) {
            Optional<String> mavenPath = jar.mavenPath();
            if (mavenPath.isEmpty()) {
                continue;
            }
            Path target = folder.resolve(jar.name());
            if (Files.isRegularFile(target) && sha256(target).equals(jar.sha256())) {
                kept.add(jar);
            } else {
                downloads.add(new Download(
                        folderName,
                        jar,
                        repository().resolve(mavenPath.get()),
                        caches.find(mavenPath.get(), jar).orElse(null)));
            }
        }
        return new RuntimePlan(known, adapter, listed, downloads, kept);
    }

    /** What will be downloaded for {@code chosen}, with where from and what each jar is checked against. */
    public void printListing(Plan plan, Set<RuntimeFolders.Known> chosen, PrintStream out) {
        List<RuntimePlan> runtimes = plan.runtimes().stream()
                .filter(runtime -> chosen.contains(runtime.known()))
                .toList();
        out.println(source() + ",");
        out.println("and checks each against the SHA-256 and size that leia's build recorded:");
        out.println();
        for (RuntimePlan runtime : runtimes) {
            for (Download download : runtime.downloads()) {
                out.println("  " + download.folder() + "/" + download.jar().name() + ", "
                        + Units.size(download.jar().size()));
                LocalCaches.Local local = download.local();
                out.println(
                        local != null
                                ? "    copied from " + local.file() + " ("
                                        + local.cache().name() + ")"
                                : "    " + download.uri());
                out.println("    SHA-256 " + download.jar().sha256());
            }
        }
        out.println();
        out.println("leia's own jars, the adapters and leia-core, come from leia.jar and aren't downloaded.");
        List<Download> copies = runtimes.stream()
                .flatMap(runtime -> runtime.localCopies().stream())
                .toList();
        if (!copies.isEmpty()) {
            long copied =
                    copies.stream().mapToLong(download -> download.jar().size()).sum();
            long downloaded =
                    runtimes.stream().mapToLong(RuntimePlan::downloadSize).sum();
            long all = runtimes.stream()
                    .mapToLong(runtime -> runtime.downloads().size())
                    .sum();
            out.println(Units.count(copies.size(), "jar") + ", " + Units.size(copied)
                    + ", come from local caches and aren't downloaded; "
                    + Units.count(all - copies.size(), "jar") + ", " + Units.size(downloaded)
                    + ", are.");
        }
        int kept = runtimes.stream().mapToInt(runtime -> runtime.kept().size()).sum();
        if (kept > 0) {
            out.println(
                    Units.count(kept, "jar") + " already there and matching " + (kept == 1 ? "is" : "are") + " kept.");
        }
        if (mirrors.size() > 1) {
            out.println();
            out.println("The mirrors, in the order they are tried when a download fails:");
            for (int i = 0; i < mirrors.size(); i++) {
                out.println("  " + (i + 1) + ". " + mirrors.get(i).name() + "  "
                        + mirrors.get(i).uri() + origin(mirrors.get(i)));
            }
        }
    }

    /** Where a mirror comes from, if not from leia's own list, such as {@code " (--download-url)"}. */
    public static String origin(Mirror mirror) {
        return switch (mirror.origin()) {
            case PROPERTY -> " (-Dleia.download.url)";
            case OPTION -> " (--download-url)";
            case SAVED -> " (chosen last)";
            case FILE -> " (mirrors file)";
            case TYPED -> " (typed)";
            case BUILT_IN -> "";
        };
    }

    /** Where the Lucene jars come from, as a sentence without its end. */
    public String source() {
        Mirror first = mirrors.getFirst();
        String others = mirrors.size() > 1
                ? ", or, when a download fails, from " + Units.count(mirrors.size() - 1L, "other mirror")
                : "";
        return first.uri().equals(Mirrors.CENTRAL)
                ? "leia downloads these official Lucene jars from Maven Central (" + Mirrors.CENTRAL + "), unchanged"
                        + others
                : "leia downloads these official Lucene jars of Maven Central, unchanged, from " + first.label()
                        + origin(first) + others;
    }

    /**
     * Installs the runtimes of {@code plan} that need no download, which only refreshes leia's own
     * jars and removes stale ones, and then those of {@code chosen}. Leaves every other runtime's
     * folder alone. A runtime that fails doesn't stop the others; once {@code cancelled}, the current
     * one stops at its next buffer, and the rest aren't begun.
     *
     * @return how each runtime it took on ended, in the plan's order
     */
    public Outcome install(Plan plan, Set<RuntimeFolders.Known> chosen, Listener listener, BooleanSupplier cancelled) {
        return install(plan, chosen, listener, cancelled, MirrorChooser.fallback());
    }

    /**
     * {@link #install(Plan, Set, Listener, BooleanSupplier)}, asking {@code chooser} what to do when
     * a jar's download from a mirror fails (ADR 0124). The mirror it retries from is the one the
     * install goes on with. Once the user aborts, the runtime fails and the rest are cancelled.
     */
    public Outcome install(
            Plan plan,
            Set<RuntimeFolders.Known> chosen,
            Listener listener,
            BooleanSupplier cancelled,
            MirrorChooser chooser) {
        // Those without downloads first: they are quick, and a cancel then only stops chosen ones.
        List<RuntimePlan> taken = new ArrayList<>();
        plan.runtimes().stream().filter(runtime -> !runtime.needsDownload()).forEach(taken::add);
        plan.runtimes().stream()
                .filter(runtime -> runtime.needsDownload() && chosen.contains(runtime.known()))
                .forEach(taken::add);
        Map<RuntimeFolders.Known, Result> results = new LinkedHashMap<>();
        HttpClient.Builder builder =
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).followRedirects(HttpClient.Redirect.NORMAL);
        // -Djava.net.useSystemProxies=true makes it the system's.
        ProxySelector proxies = ProxySelector.getDefault();
        if (proxies != null) {
            builder.proxy(proxies);
        }
        ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("leia-download-watchdog").factory());
        try (HttpClient client = builder.build()) {
            Run run = new Run(client, listener, cancelled, chooser, watchdog);
            for (RuntimePlan runtime : taken) {
                if (cancelled.getAsBoolean() || run.aborted) {
                    results.put(runtime.known(), new Result.Cancelled());
                    continue;
                }
                try {
                    install(run, runtime);
                    results.put(runtime.known(), new Result.Installed());
                } catch (InstallException e) {
                    results.put(runtime.known(), new Result.Failed(e.getMessage()));
                    listener.runtimeFailed(runtime.known().folder(), e.getMessage());
                } catch (AbortedException e) {
                    run.aborted = true;
                    String message = e.getMessage() + "; the download was stopped";
                    results.put(runtime.known(), new Result.Failed(message));
                    listener.runtimeFailed(runtime.known().folder(), message);
                } catch (CancelledException _) {
                    results.put(runtime.known(), new Result.Cancelled());
                }
            }
        } finally {
            watchdog.shutdownNow();
        }
        Map<RuntimeFolders.Known, Result> ordered = new LinkedHashMap<>();
        plan.runtimes().stream()
                .map(RuntimePlan::known)
                .filter(results::containsKey)
                .forEach(known -> ordered.put(known, results.get(known)));
        return new Outcome(ordered);
    }

    /**
     * What an install goes on with from one jar to the next: the mirror that worked last, and
     * whether the user stopped it.
     */
    private final class Run {
        final HttpClient client;
        final Listener listener;
        final BooleanSupplier cancelled;
        final MirrorChooser chooser;
        final ScheduledExecutorService watchdog;
        final List<Mirror> runMirrors = new ArrayList<>(mirrors);
        Mirror current = mirrors.getFirst();
        boolean aborted;

        Run(
                HttpClient client,
                Listener listener,
                BooleanSupplier cancelled,
                MirrorChooser chooser,
                ScheduledExecutorService watchdog) {
            this.client = client;
            this.listener = listener;
            this.cancelled = cancelled;
            this.chooser = chooser;
            this.watchdog = watchdog;
        }
    }

    private void install(Run run, RuntimePlan runtime) throws InstallException, CancelledException, AbortedException {
        Listener listener = run.listener;
        BooleanSupplier cancelled = run.cancelled;
        RuntimeFolders.Known known = runtime.known();
        String folderName = known.folder();
        Path folder = runtimesDir.resolve(folderName);
        try {
            Files.createDirectories(folder);
        } catch (IOException e) {
            throw new InstallException("cannot create " + folder + ", so runtimes/ isn't writable: " + e);
        }
        Set<String> names = new HashSet<>();
        names.add(known.adapterJar());
        writeOwn(folder, known.adapterJar(), runtime.adapter(), listener);
        Map<String, Download> downloads = new LinkedHashMap<>();
        runtime.downloads().forEach(download -> downloads.put(download.jar().name(), download));
        for (RuntimeFolders.ListedJar jar : runtime.listed()) {
            if (cancelled.getAsBoolean()) {
                throw new CancelledException();
            }
            names.add(jar.name());
            Optional<String> mavenPath = jar.mavenPath();
            if (mavenPath.isEmpty()) {
                writeOwn(folder, jar.name(), resource(folderName, jar.name()), listener);
            } else if (downloads.containsKey(jar.name())) {
                LocalCaches.Local local = downloads.get(jar.name()).local();
                if (local == null || !copyLocal(run, folder, jar, local)) {
                    fetch(run, folder, jar, mavenPath.get());
                }
            } else {
                listener.jarDone(folderName, jar.name(), JarDone.KEPT, jar.size());
            }
        }
        removeStale(folder, names, listener);
    }

    /**
     * Rewrites leia's own jars in each runtime's folder that has an adapter, where they differ from
     * those of {@code leia.jar}, as after an upgrade of leia (ADR 0098): the jars that the list names
     * without a Maven path, then the adapter, so that a failure leaves the old adapter, which the
     * backend refuses as outdated. Creates no folder, downloads nothing and removes nothing; a Lucene
     * jar that the new list names and that isn't there leaves the runtime incomplete, for {@code D}.
     *
     * @return the runtimes that failed, with why, in the order of {@link RuntimeFolders#KNOWN}
     */
    public Map<RuntimeFolders.Known, String> refreshOwnJars(Listener listener) {
        Map<RuntimeFolders.Known, String> failed = new LinkedHashMap<>();
        for (RuntimeFolders.Known known : RuntimeFolders.KNOWN) {
            Path folder = runtimesDir.resolve(known.folder());
            if (!Files.isRegularFile(folder.resolve(known.adapterJar()))) {
                continue;
            }
            try {
                byte[] adapter = resource(known.folder(), known.adapterJar());
                for (RuntimeFolders.ListedJar jar : list(known.folder(), known.adapterJar(), adapter)) {
                    if (jar.mavenPath().isEmpty()) {
                        writeOwn(folder, jar.name(), resource(known.folder(), jar.name()), listener);
                    }
                }
                writeOwn(folder, known.adapterJar(), adapter, listener);
            } catch (InstallException e) {
                failed.put(known, e.getMessage());
                listener.runtimeFailed(known.folder(), e.getMessage());
            }
        }
        return failed;
    }

    /** One of leia's jars, embedded in {@code leia.jar}. */
    private byte[] resource(String folder, String jar) throws InstallException {
        String name = RuntimeFolders.EMBEDDED + folder + "/" + jar;
        try (InputStream in = resourceLoader.getResourceAsStream(name)) {
            if (in == null) {
                throw new InstallException(
                        "leia.jar lacks " + name + "; --download-runtimes works only from a complete leia.jar");
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new InstallException("cannot read " + name + " from leia.jar: " + e);
        }
    }

    /** The list in an adapter jar. */
    private static List<RuntimeFolders.ListedJar> list(String folder, String name, byte[] adapter)
            throws InstallException {
        try (JarInputStream jar = new JarInputStream(new ByteArrayInputStream(adapter))) {
            for (JarEntry entry = jar.getNextJarEntry(); entry != null; entry = jar.getNextJarEntry()) {
                if (entry.getName().equals(RuntimeFolders.LIST)) {
                    return RuntimeFolders.listedJars(jar);
                }
            }
        } catch (IOException e) {
            throw new InstallException("cannot read the list of " + folder + "/" + name + ": " + e.getMessage());
        }
        throw new InstallException(folder + "/" + name + " in leia.jar has no " + RuntimeFolders.LIST);
    }

    /** Writes one of leia's jars, unless it is there already. */
    private void writeOwn(Path folder, String name, byte[] content, Listener listener) throws InstallException {
        Path target = folder.resolve(name);
        String folderName = folder.getFileName().toString();
        if (Files.isRegularFile(target) && sha256(target).equals(sha256(content))) {
            listener.jarDone(folderName, name, JarDone.KEPT, content.length);
            return;
        }
        Path part = null;
        try {
            part = Files.createTempFile(folder, name, PART);
            Files.write(part, content);
            move(part, target);
        } catch (IOException e) {
            delete(part);
            throw new InstallException("cannot write " + target + ": " + e);
        }
        listener.jarDone(folderName, name, JarDone.WRITTEN, content.length);
    }

    /**
     * Copies a Lucene jar from a local cache (ADR 0125), checking it again, since the cache may have
     * changed since the plan, and moves it into place once it matches.
     *
     * @return whether it was copied; if not, the jar is downloaded instead
     */
    private boolean copyLocal(Run run, Path folder, RuntimeFolders.ListedJar jar, LocalCaches.Local local)
            throws InstallException, CancelledException {
        String folderName = folder.getFileName().toString();
        Path part = null;
        try {
            part = Files.createTempFile(folder, jar.name(), PART);
            MessageDigest digest = newDigest();
            long size;
            try (InputStream in = new DigestInputStream(Files.newInputStream(local.file()), digest)) {
                size = Files.copy(in, part, StandardCopyOption.REPLACE_EXISTING);
            }
            String sha256 = HexFormat.of().formatHex(digest.digest());
            if (run.cancelled.getAsBoolean()) {
                throw new CancelledException();
            }
            if (size != jar.size() || !sha256.equals(jar.sha256())) {
                delete(part);
                LOG.info(() -> local.file() + " no longer matches " + jar.name() + "; downloading it instead");
                return false;
            }
            move(part, folder.resolve(jar.name()));
            run.listener.jarCopied(folderName, jar.name(), size, local);
            return true;
        } catch (CancelledException e) {
            delete(part);
            throw e;
        } catch (IOException e) {
            delete(part);
            if (part == null) {
                throw new InstallException("cannot write " + folder.resolve(jar.name()) + ": " + e);
            }
            LOG.info(() -> "Cannot copy " + local.file() + ": " + e + "; downloading it instead");
            return false;
        }
    }

    /**
     * Downloads a Lucene jar from the mirror the install goes on with; when that fails, asks the
     * chooser, and retries from the mirror it names until one works, or it gives up (ADR 0124).
     */
    private void fetch(Run run, Path folder, RuntimeFolders.ListedJar jar, String mavenPath)
            throws InstallException, CancelledException, AbortedException {
        String folderName = folder.getFileName().toString();
        Set<URI> tried = new LinkedHashSet<>();
        List<String> failures = new ArrayList<>();
        while (true) {
            Mirror mirror = run.current;
            try {
                download(run, folder, jar, mirror, mirror.resolve(mavenPath));
                return;
            } catch (DownloadFailed e) {
                MirrorChooser.Failure failure =
                        new MirrorChooser.Failure(folderName, jar.name(), mirror, e.reason, e.getMessage());
                tried.add(mirror.uri());
                failures.add(mirror.name() + ": " + e.reason);
                // INFO: --download-runtimes shows its warnings on stderr, where the listener named it.
                LOG.info(() -> failure.message() + " (" + mirror.label() + ")");
                run.listener.mirrorFailed(failure);
                if (run.cancelled.getAsBoolean()) {
                    throw new CancelledException();
                }
                MirrorChooser.Choice choice =
                        run.chooser.choose(failure, List.copyOf(run.runMirrors), Set.copyOf(tried));
                if (run.cancelled.getAsBoolean()) {
                    throw new CancelledException();
                }
                switch (choice) {
                    case MirrorChooser.Choice.Retry retry -> {
                        if (run.runMirrors.stream()
                                .noneMatch(known ->
                                        known.uri().equals(retry.mirror().uri()))) {
                            run.runMirrors.add(retry.mirror());
                        }
                        run.current = retry.mirror();
                    }
                    case MirrorChooser.Choice.Skip _ ->
                        throw new InstallException(
                                tried.size() == 1
                                        ? e.getMessage()
                                        : "cannot download " + jar.name() + " from any of "
                                                + Units.count(tried.size(), "mirror") + ": "
                                                + String.join("; ", failures));
                    case MirrorChooser.Choice.Abort _ -> throw new AbortedException(e.getMessage());
                }
            }
        }
    }

    /**
     * Downloads a Lucene jar from {@code uri}, and moves it into place once it matches. Checks for
     * a cancel after each buffer, and then leaves nothing behind. A mirror that sends no byte for
     * {@link #timeout} has timed out.
     */
    private void download(Run run, Path folder, RuntimeFolders.ListedJar jar, Mirror mirror, URI uri)
            throws InstallException, CancelledException, DownloadFailed {
        Path target = folder.resolve(jar.name());
        String folderName = folder.getFileName().toString();
        String from = "cannot download " + jar.name() + " from " + uri + ": ";
        run.listener.jarStarted(folderName, jar.name(), jar.size());
        Path part = null;
        AtomicBoolean idle = new AtomicBoolean();
        ScheduledFuture<?> watch = null;
        try {
            part = Files.createTempFile(folder, jar.name(), PART);
            HttpResponse<InputStream> response = run.client.send(
                    HttpRequest.newBuilder(uri).timeout(timeout).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            MessageDigest digest = newDigest();
            long size = 0;
            AtomicLong lastBytes = new AtomicLong(System.nanoTime());
            InputStream raw = response.body();
            watch = watch(run.watchdog, raw, lastBytes, idle);
            try (InputStream body = new DigestInputStream(raw, digest);
                    OutputStream file = Files.newOutputStream(part)) {
                if (response.statusCode() != 200) {
                    throw new DownloadFailed(from + "HTTP " + response.statusCode(), "HTTP " + response.statusCode());
                }
                byte[] buffer = new byte[BUFFER];
                for (int n = body.read(buffer); n >= 0; n = body.read(buffer)) {
                    lastBytes.set(System.nanoTime());
                    file.write(buffer, 0, n);
                    size += n;
                    run.listener.bytes(n);
                    if (run.cancelled.getAsBoolean()) {
                        throw new CancelledException();
                    }
                }
            }
            String sha256 = HexFormat.of().formatHex(digest.digest());
            if (size != jar.size() || !sha256.equals(jar.sha256())) {
                String mismatch = size + " bytes with SHA-256 " + sha256 + ", expected " + jar.size()
                        + " bytes with SHA-256 " + jar.sha256();
                throw new DownloadFailed(
                        "the download of " + jar.name() + " from " + uri + " doesn't match: " + mismatch,
                        "doesn't match: " + mismatch);
            }
            move(part, target);
            run.listener.jarDownloaded(folderName, jar.name(), size, mirror);
        } catch (DownloadFailed | CancelledException e) {
            delete(part);
            throw e;
        } catch (HttpTimeoutException _) {
            delete(part);
            String reason = "no answer within " + timeout.toSeconds() + " s";
            throw new DownloadFailed(from + reason, reason);
        } catch (IOException e) {
            delete(part);
            if (idle.get()) {
                String reason = "no bytes for " + timeout.toSeconds() + " s";
                throw new DownloadFailed(from + reason, reason);
            }
            if (part == null) {
                // The folder can't be written: no mirror helps.
                throw new InstallException(
                        "cannot download " + jar.name() + " from " + uri + " to " + target + ": " + e);
            }
            String reason = e instanceof ConnectException
                    ? "cannot connect" + (e.getMessage() == null ? "" : ": " + e.getMessage())
                    : String.valueOf(e);
            throw new DownloadFailed(from + reason, reason);
        } catch (InterruptedException _) {
            delete(part);
            Thread.currentThread().interrupt();
            throw new InstallException("interrupted while downloading " + jar.name() + " from " + uri);
        } finally {
            if (watch != null) {
                watch.cancel(false);
            }
        }
    }

    /** Closes {@code body} once no bytes arrived for {@link #timeout}, which ends a read that waits. */
    private ScheduledFuture<?> watch(
            ScheduledExecutorService watchdog, InputStream body, AtomicLong lastBytes, AtomicBoolean idle) {
        long limit = timeout.toNanos();
        long period = Math.max(timeout.toMillis() / 4, 10);
        return watchdog.scheduleAtFixedRate(
                () -> {
                    if (!idle.get() && System.nanoTime() - lastBytes.get() > limit) {
                        idle.set(true);
                        try {
                            body.close();
                        } catch (IOException _) {
                            // The read that waits fails either way.
                        }
                    }
                },
                period,
                period,
                TimeUnit.MILLISECONDS);
    }

    /** Removes the jars and unfinished downloads in {@code folder} that aren't {@code names}. */
    private void removeStale(Path folder, Set<String> names, Listener listener) throws InstallException {
        List<Path> stale = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder)) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                String lower = name.toLowerCase(Locale.ROOT);
                if (Files.isRegularFile(file)
                        && (lower.endsWith(PART) || (lower.endsWith(".jar") && !names.contains(name)))) {
                    stale.add(file);
                }
            }
            for (Path file : stale) {
                Files.delete(file);
                listener.jarDone(
                        folder.getFileName().toString(), file.getFileName().toString(), JarDone.REMOVED, 0);
            }
        } catch (IOException e) {
            throw new InstallException("cannot remove the old jars of " + folder + ": " + e);
        }
    }

    private static void move(Path part, Path target) throws IOException {
        try {
            Files.move(part, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException _) {
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void delete(Path part) {
        if (part == null) {
            return;
        }
        try {
            Files.deleteIfExists(part);
        } catch (IOException _) {
            // Removed by the next install.
        }
    }

    static String sha256(Path file) throws InstallException {
        MessageDigest digest = newDigest();
        try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
            in.transferTo(OutputStream.nullOutputStream());
        } catch (IOException e) {
            throw new InstallException("cannot read " + file + ": " + e);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sha256(byte[] content) {
        return HexFormat.of().formatHex(newDigest().digest(content));
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java has SHA-256", e);
        }
    }
}
