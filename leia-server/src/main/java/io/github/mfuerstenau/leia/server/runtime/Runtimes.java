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

/**
 * The runtimes that read the indexes (ADR 0044, ADR 0071), found with {@link ServiceLoader}
 * through the loader of the jars, {@link JarChain#classLoader()}. A runtime built for another
 * version of the interface, or one that can't be loaded, is refused and never used.
 *
 * <p>It is used on the backend thread only, so it needs no locking.
 */
@Singleton
public final class Runtimes {

    private static final Logger LOG = Logger.getLogger(Runtimes.class.getName());

    /** Errors in a row after which the services files are given up on. */
    private static final int MAX_ERRORS = 100;

    private ClassLoader loader;
    private Loaded loaded;
    private final Set<String> reported;

    @Inject
    public Runtimes() {
        loader = JarChain.classLoader();
        loaded = load(loader);
        // LeiaBackend.start refused these already.
        reported = new HashSet<>(loaded.refused());
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

    /** The runtimes of the jars now, loaded again if jars were added since. */
    private Loaded current() {
        ClassLoader now = JarChain.classLoader();
        if (now != loader) {
            loader = now;
            loaded = load(now);
        }
        return loaded;
    }

    /** A runtime that reads indexes of {@code major}, the first one found. */
    public Optional<LuceneRuntime> forMajor(int major) {
        return current().runtimes().stream()
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
        return current().runtimes().stream().max(Comparator.comparingInt(Runtimes::newestMajor));
    }

    private static int newestMajor(LuceneRuntime runtime) {
        return runtime.indexMajors().stream().mapToInt(Integer::intValue).max().orElse(Integer.MIN_VALUE);
    }

    /** Whether {@code major} is newer than any runtime reads. */
    public boolean newerThanAll(int major) {
        return current().runtimes().stream().allMatch(runtime -> newestMajor(runtime) < major);
    }

    /**
     * The analysis tab of the first runtime that has one (ADR 0067), with the runtime it belongs to.
     */
    public Optional<RuntimeAnalysis> analysis() {
        for (LuceneRuntime runtime : current().runtimes()) {
            Optional<Analysis> analysis = runtime.analysis();
            if (analysis.isPresent()) {
                return Optional.of(new RuntimeAnalysis(runtime, analysis.get()));
            }
        }
        return Optional.empty();
    }

    /** The runtime jar that reads the newest indexes, and those whose version can't be detected (ADR 0071). */
    public static final String LATEST_JAR = "leia-lucene10.jar";

    /**
     * The runtime jar that reads indexes of {@code major} (ADR 0044, ADR 0071), such as {@code
     * leia-lucene3.jar} for 2 and 3, or empty for a major that none reads.
     */
    public static Optional<String> jarFor(int major) {
        return switch (major) {
            case 2, 3 -> Optional.of("leia-lucene3.jar");
            case 4, 5 -> Optional.of("leia-lucene5.jar");
            case 6, 7 -> Optional.of("leia-lucene7.jar");
            case 8 -> Optional.of("leia-lucene9.jar");
            case 9, 10 -> Optional.of(LATEST_JAR);
            default -> Optional.empty();
        };
    }

    /** The refusals not reported before, for {@code addJars}. */
    public List<String> newlyRefused() {
        List<String> fresh = current().refused().stream()
                .filter(refusal -> !reported.contains(refusal))
                .toList();
        reported.addAll(fresh);
        return fresh;
    }
}
