package io.github.mfuerstenau.leia.server.runtime;

import io.github.mfuerstenau.leia.runtime.Analysis;
import io.github.mfuerstenau.leia.runtime.LuceneRuntime;
import io.github.mfuerstenau.leia.server.jars.JarChain;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;

/**
 * The runtimes that read the indexes (ADR 0044, ADR 0071): those of the {@link ServerRuntimes}
 * first, which read the indexes of their majors with their servers' Lucene (ADR 0090), then those of
 * the {@link RuntimeFolders}, each in a class loader of its own (ADR 0073), and those found with
 * {@link ServiceLoader} through the loader of the jars, {@link JarChain#classLoader()}, such as Lucene
 * 10's. A runtime built for another version of the interface, or one that can't be loaded, is refused
 * and never used.
 *
 * <p>It is used on the backend thread only, so it needs no locking.
 */
@Singleton
public final class Runtimes {

    private static final Logger LOG = Logger.getLogger(Runtimes.class.getName());

    /** Errors in a row after which the services files are given up on. */
    private static final int MAX_ERRORS = 100;

    private ClassLoader loader;
    private List<LuceneRuntime> folders;
    private List<ServerRuntime> servers;
    private Loaded loaded;
    private List<LuceneRuntime> leias;
    private List<LuceneRuntime> all;
    private final Set<String> reported;

    @Inject
    public Runtimes() {
        loader = JarChain.classLoader();
        folders = RuntimeFolders.runtimes();
        servers = ServerRuntimes.runtimes();
        loaded = load(loader);
        leias = leias(folders, loaded);
        all = all(servers, leias);
        // LeiaBackend.start refused these already, or RuntimeFolders reports them.
        reported = new HashSet<>(loaded.refused());
    }

    private static List<LuceneRuntime> leias(List<LuceneRuntime> folders, Loaded chain) {
        List<LuceneRuntime> leias = new ArrayList<>(folders);
        leias.addAll(chain.runtimes());
        return List.copyOf(leias);
    }

    private static List<LuceneRuntime> all(List<ServerRuntime> servers, List<LuceneRuntime> leias) {
        List<LuceneRuntime> all = new ArrayList<>(servers);
        all.addAll(leias);
        return List.copyOf(all);
    }

    /**
     * The runtimes that {@code loader} finds.
     *
     * @param runtimes those that leia can use
     * @param refused why each other one is refused, such as "the runtime X in Y is built for runtime
     *     interface 2, leia has 1"
     */
    public record Loaded(List<LuceneRuntime> runtimes, List<String> refused) {}

    /** Finds the runtimes in the services files that {@code loader} sees. */
    // Clearer than a loop with one exit (ADR 0088)
    @SuppressWarnings("java:S135")
    public static Loaded load(ClassLoader loader) {
        List<LuceneRuntime> runtimes = new ArrayList<>();
        List<String> refused = new ArrayList<>();
        Iterator<ServiceLoader.Provider<LuceneRuntime>> providers =
                ServiceLoader.load(LuceneRuntime.class, loader).stream().iterator();
        int errors = 0;
        while (errors < MAX_ERRORS) {
            ServiceLoader.Provider<LuceneRuntime> provider;
            try {
                if (!providers.hasNext()) {
                    break;
                }
                provider = providers.next();
            } catch (ServiceConfigurationError | LinkageError e) {
                // Such as a class named in a services file that isn't there; the next one may load.
                refused.add("cannot load a runtime: " + e.getMessage());
                errors++;
                continue;
            }
            errors = 0;
            String name = provider.type().getName() + " in " + location(provider.type());
            try {
                LuceneRuntime runtime = provider.get();
                int version = runtime.apiVersion();
                if (version != LuceneRuntime.API_VERSION) {
                    refused.add("the runtime " + name + " is built for runtime interface " + version + ", leia has "
                            + LuceneRuntime.API_VERSION);
                } else {
                    runtimes.add(runtime);
                }
            } catch (ServiceConfigurationError | LinkageError | RuntimeException e) {
                refused.add("cannot load the runtime " + name + ": " + e);
            }
        }
        LOG.fine(() -> "Runtimes "
                + runtimes.stream().map(LuceneRuntime::luceneVersion).toList() + ", refused " + refused);
        return new Loaded(List.copyOf(runtimes), List.copyOf(refused));
    }

    /** The jar or directory a class comes from. */
    private static String location(Class<?> type) {
        CodeSource source = type.getProtectionDomain().getCodeSource();
        return source == null || source.getLocation() == null
                ? "an unknown place"
                : source.getLocation().toString();
    }

    /**
     * The runtimes of the jar chain now, loaded again if jars were added since; {@link #all} follows
     * the folders' runtimes too, which {@code loadRuntimes} adds to (ADR 0080).
     */
    private Loaded chain() {
        ClassLoader now = JarChain.classLoader();
        List<LuceneRuntime> nowFolders = RuntimeFolders.runtimes();
        List<ServerRuntime> nowServers = ServerRuntimes.runtimes();
        boolean chainChanged = now != loader;
        if (chainChanged) {
            loader = now;
            loaded = load(now);
        }
        if (chainChanged || nowFolders != folders || nowServers != servers) {
            folders = nowFolders;
            servers = nowServers;
            leias = leias(folders, loaded);
            all = all(servers, leias);
        }
        return loaded;
    }

    /** Every runtime: those of the servers, then those of the folders, then those of the jar chain. */
    private List<LuceneRuntime> current() {
        chain();
        return all;
    }

    /** Leia's own runtimes: those of the folders, then those of the jar chain; no server's. */
    private List<LuceneRuntime> leias() {
        chain();
        return leias;
    }

    /** A runtime that reads indexes of {@code major}, the first one found: a server's, if one reads them. */
    public Optional<LuceneRuntime> forMajor(int major) {
        return forMajor(current(), major);
    }

    /**
     * One of leia's own runtimes that reads indexes of {@code major}, for an index that a server's
     * runtime fails to read, such as one written by a newer Lucene than the server's (ADR 0090).
     */
    public Optional<LuceneRuntime> leiasForMajor(int major) {
        return forMajor(leias(), major);
    }

    private static Optional<LuceneRuntime> forMajor(List<LuceneRuntime> runtimes, int major) {
        return runtimes.stream()
                .filter(runtime -> runtime.indexMajors().contains(major))
                .findFirst();
    }

    /**
     * The runtime that reads the newest indexes: the one with the highest major in {@link
     * LuceneRuntime#indexMajors()}, the first one found of those. It gets the indexes whose version
     * can't be detected or is newer than any runtime reads, and reports the format error itself (ADR
     * 0071).
     */
    public Optional<LuceneRuntime> latest() {
        // On a tie, max keeps the first.
        return current().stream().max(Comparator.comparingInt(Runtimes::newestMajor));
    }

    private static int newestMajor(LuceneRuntime runtime) {
        return runtime.indexMajors().stream().mapToInt(Integer::intValue).max().orElse(Integer.MIN_VALUE);
    }

    /** Whether {@code major} is newer than any runtime reads. */
    public boolean newerThanAll(int major) {
        return current().stream().allMatch(runtime -> newestMajor(runtime) < major);
    }

    /**
     * The analysis tab of the first runtime that has one (ADR 0067), with the runtime it belongs to;
     * never a server's, which has none.
     */
    public Optional<RuntimeAnalysis> analysis() {
        for (LuceneRuntime runtime : current()) {
            Optional<Analysis> analysis = runtime.analysis();
            if (analysis.isPresent()) {
                return Optional.of(new RuntimeAnalysis(runtime, analysis.get()));
            }
        }
        return Optional.empty();
    }

    /**
     * Every runtime leia knows, newest first, with the Lucene version of the loaded runtime that reads
     * its indexes, or empty if none is loaded, and how its folder was found (ADR 0072, ADR 0073); a
     * server's runtime doesn't count.
     */
    public List<KnownState> known() {
        return RuntimeFolders.KNOWN.stream()
                .map(known -> new KnownState(
                        known,
                        leiasForMajor(known.indexMajors().getFirst())
                                .map(LuceneRuntime::luceneVersion)
                                .orElse(null),
                        RuntimeFolders.state(known)))
                .toList();
    }

    /**
     * A runtime that leia knows, and whether it is loaded.
     *
     * @param luceneVersion the Lucene version of the loaded runtime that reads its indexes, or
     *     {@code null}
     * @param folder how its folder was found; a runtime can be loaded without one, through the jars
     */
    public record KnownState(
            RuntimeFolders.Known known, @Nullable String luceneVersion, RuntimeFolders.State folder) {}

    /** The servers' runtimes, in the order they were given (ADR 0090). */
    public List<ServerRuntime> servers() {
        chain();
        return servers;
    }

    /** The refusals not reported before, for {@code addJars}. */
    public List<String> newlyRefused() {
        List<String> fresh = chain().refused().stream()
                .filter(refusal -> !reported.contains(refusal))
                .toList();
        reported.addAll(fresh);
        return fresh;
    }
}
