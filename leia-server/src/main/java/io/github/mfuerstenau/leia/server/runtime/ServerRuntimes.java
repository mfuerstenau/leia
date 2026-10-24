package io.github.mfuerstenau.leia.server.runtime;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.runtime.LuceneRuntime;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.jars.JarChain;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The runtimes of the Elasticsearch and OpenSearch installations that {@code --server} names (ADR
 * 0090): for each, leia's adapter for the major of its Lucene, from {@code runtimes/}, in a class
 * loader over the adapter and the top-level jars of the installation's {@code lib/}, whose Lucene,
 * codecs and formats it reads with. Elasticsearch 8 and 9 and OpenSearch 2 and 3 ship Lucene 9 or 10.
 *
 * <p>They are loaded at startup, after the {@link RuntimeFolders} and before the jars of {@code
 * --jar}, once per JVM, like the jar chain; a server that is loaded stays loaded. A server that can't
 * be loaded stops the startup, since it was asked for, as a jar of {@code --jar} does (ADR 0018).
 */
public final class ServerRuntimes {

    private static final Logger LOG = Logger.getLogger(ServerRuntimes.class.getName());

    /** The product's own jar in {@code lib/}, such as {@code elasticsearch-8.15.3.jar}. */
    private static final Pattern PRODUCT_JAR =
            Pattern.compile("(elasticsearch|opensearch)-(\\d+\\.\\d+\\.\\d+(?:-[A-Za-z0-9.]+)?)\\.jar");

    /** What the message of an unknown Lucene names. */
    private static final String SERVERS_LEIA_READS =
            "leia reads the indexes of Elasticsearch 8 and 9 and OpenSearch 2 and 3, whose Lucene is 9 or 10";

    // Written while holding the class's lock, read without it.
    // An immutable value, published through volatile (ADR 0088)
    @SuppressWarnings("java:S3077")
    private static volatile List<ServerRuntime> runtimes = List.of();

    private ServerRuntimes() {}

    /**
     * The {@code lib/} of the installation at {@code path}: {@code path/lib}, or {@code path} itself,
     * whichever has a {@code lucene-core} jar; empty if neither does.
     */
    public static Optional<Path> lib(Path path) {
        return Stream.of(path.resolve("lib"), path)
                .filter(ServerRuntimes::hasLuceneCore)
                .findFirst();
    }

    private static boolean hasLuceneCore(Path dir) {
        return luceneCore(dir).isPresent();
    }

    private static Optional<Path> luceneCore(Path dir) {
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(file ->
                            LuceneJarVersion.isLuceneCore(file.getFileName().toString()))
                    .filter(Files::isRegularFile)
                    .sorted()
                    .findFirst();
        } catch (IOException e) {
            LOG.log(Level.FINE, e, () -> "Cannot list " + dir);
            return Optional.empty();
        }
    }

    /**
     * Loads the servers at {@code paths}, each an installation's home or its {@code lib/}, with the
     * adapters of {@code runtimesDir}. A server that is loaded already isn't loaded again.
     *
     * @throws ServerException if one can't be loaded: no Lucene in it, a Lucene whose major leia has
     *     no adapter for, an adapter that isn't installed, a second server for one major; none of
     *     them is loaded then
     */
    public static synchronized void load(List<Path> paths, Optional<Path> runtimesDir) throws ServerException {
        List<ServerRuntime> loaded = new ArrayList<>(runtimes);
        List<URLClassLoader> fresh = new ArrayList<>();
        try {
            for (Path path : paths.stream()
                    .map(path -> path.toAbsolutePath().normalize())
                    .distinct()
                    .toList()) {
                if (loaded.stream().noneMatch(runtime -> runtime.home().equals(path))) {
                    loaded.add(load(path, runtimesDir, loaded, fresh));
                }
            }
        } catch (ServerException e) {
            fresh.forEach(ServerRuntimes::close);
            throw e;
        }
        loaded.subList(runtimes.size(), loaded.size())
                .forEach(runtime -> LOG.info(() -> "Loaded " + runtime + " from " + runtime.home()));
        runtimes = List.copyOf(loaded);
    }

    private static ServerRuntime load(
            Path home, Optional<Path> runtimesDir, List<ServerRuntime> loaded, List<URLClassLoader> fresh)
            throws ServerException {
        Path lib = lib(home)
                .orElseThrow(() -> new ServerException(
                        home, "it has no lucene-core jar, neither in " + home.resolve("lib") + " nor in itself"));
        LuceneJarVersion lucene = luceneVersion(home, lib);
        RuntimeFolders.Known known = RuntimeFolders.KNOWN.stream()
                .filter(candidate -> candidate.folder().equals("lucene" + lucene.major()))
                .findFirst()
                .orElseThrow(() ->
                        new ServerException(home, "its Lucene is " + lucene.version() + "; " + SERVERS_LEIA_READS));
        for (ServerRuntime other : loaded) {
            if (other.lucene().major() == lucene.major()) {
                throw new ServerException(
                        home,
                        "its Lucene " + lucene.version() + " is of the same major as that of " + other.name() + " in "
                                + other.home() + "; one server per major can be read");
            }
        }
        Path folder = runtimesDir
                .map(dir -> dir.resolve(known.folder()))
                .orElseThrow(() -> new ServerException(
                        home, "it needs leia's adapter in runtimes/" + known.folder() + ", and there is no runtimes/"));
        List<Path> adapterJars = adapterJars(home, folder, known);
        String builtFor = builtFor(adapterJars.getFirst());
        List<Path> jars = new ArrayList<>(adapterJars);
        jars.addAll(libJars(home, lib));
        String loaderName = "leia-server-" + known.folder();
        URLClassLoader loader = new URLClassLoader(
                loaderName, urls(home, jars), new RuntimeFolders.ApiOnlyLoader(ServerRuntimes.class.getClassLoader()));
        fresh.add(loader);
        ElasticsearchSetup.setUp(loader, lib.toAbsolutePath());
        Runtimes.Loaded found = Runtimes.load(loader);
        List<LuceneRuntime> reading = found.runtimes().stream()
                .filter(runtime -> runtime.luceneVersion().startsWith(lucene.major() + "."))
                .toList();
        if (reading.size() != 1) {
            throw new ServerException(
                    home,
                    found.refused().isEmpty()
                            ? "the adapter " + adapterJars.getFirst() + " finds "
                                    + found.runtimes().size() + " runtimes on its Lucene " + lucene.version()
                                    + ", not one"
                            : String.join("; ", found.refused()));
        }
        return new ServerRuntime(reading.getFirst(), home, label(lib), lucene, builtFor, loader);
    }

    private static LuceneJarVersion luceneVersion(Path home, Path lib) throws ServerException {
        Path core = luceneCore(lib).orElseThrow();
        try {
            return LuceneJarVersion.of(core)
                    .orElseThrow(() -> new ServerException(home, "the version of " + core + " can't be told"));
        } catch (IOException e) {
            throw new ServerException(home, "cannot read " + core + ": " + e.getMessage(), e);
        }
    }

    /**
     * The adapter and the jars its list names that {@code leia.jar} brings: leia's own, such as
     * leia-core for Lucene 10. The folder's Lucene isn't needed, so the folder needn't be complete.
     */
    private static List<Path> adapterJars(Path home, Path folder, RuntimeFolders.Known known) throws ServerException {
        Path adapter = folder.resolve(known.adapterJar());
        String install = "install the runtime " + known.folder() + " with D on leia's start screen, or with leia"
                + " --download-runtimes --runtime " + known.folder();
        if (!Files.isRegularFile(adapter)) {
            throw new ServerException(home, "it needs leia's adapter " + adapter + ", which isn't there; " + install);
        }
        List<Path> jars = new ArrayList<>();
        jars.add(adapter);
        try {
            for (RuntimeFolders.ListedJar listed : RuntimeFolders.listed(adapter)) {
                if (listed.mavenPath().isEmpty()) {
                    Path jar = folder.resolve(listed.name());
                    if (!Files.isRegularFile(jar)) {
                        throw new ServerException(home, "it needs " + jar + ", which isn't there; " + install);
                    }
                    jars.add(jar);
                }
            }
        } catch (IOException e) {
            throw new ServerException(
                    home, "cannot read " + RuntimeFolders.LIST + " of " + adapter + ": " + e.getMessage(), e);
        }
        return jars;
    }

    /** The Lucene version that {@code adapter} is built for, as the {@code lucene-core} of its list says. */
    private static String builtFor(Path adapter) {
        try {
            return RuntimeFolders.listed(adapter).stream()
                    .map(RuntimeFolders.ListedJar::name)
                    .flatMap(name -> LuceneJarVersion.fromFileName(name).stream())
                    .map(LuceneJarVersion::version)
                    .findFirst()
                    .orElse("unknown");
        } catch (IOException _) {
            // adapterJars read it already.
            return "unknown";
        }
    }

    /** The jars directly in {@code lib/}, sorted by name; not those of {@code modules/} or {@code plugins/}. */
    private static List<Path> libJars(Path home, Path lib) throws ServerException {
        try (Stream<Path> files = Files.list(lib)) {
            return files.filter(file -> file.getFileName().toString().endsWith(".jar"))
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(file -> file.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            throw new ServerException(home, "cannot list " + lib + ": " + e.getMessage(), e);
        }
    }

    private static URL[] urls(Path home, List<Path> jars) throws ServerException {
        URL[] urls = new URL[jars.size()];
        for (int i = 0; i < urls.length; i++) {
            try {
                urls[i] = jars.get(i).toUri().toURL();
            } catch (MalformedURLException e) {
                throw new ServerException(home, "not a jar: " + jars.get(i), e);
            }
        }
        return urls;
    }

    /** The product and version, such as {@code Elasticsearch 8.15.3}, from its jar in {@code lib/}. */
    static Optional<String> label(Path lib) {
        try (Stream<Path> files = Files.list(lib)) {
            return files.map(file -> PRODUCT_JAR.matcher(file.getFileName().toString()))
                    .filter(Matcher::matches)
                    .map(matcher -> (matcher.group(1).equals("elasticsearch") ? "Elasticsearch " : "OpenSearch ")
                            + matcher.group(2))
                    .sorted()
                    .findFirst();
        } catch (IOException e) {
            LOG.log(Level.FINE, e, () -> "Cannot list " + lib);
            return Optional.empty();
        }
    }

    private static void close(URLClassLoader loader) {
        try {
            loader.close();
        } catch (IOException e) {
            LOG.log(Level.FINE, "Cannot close the class loader of a refused server", e);
        }
    }

    /**
     * Makes the jar chain's loader the thread's context class loader, as after jars were added, unless
     * it is a server's, while one of its indexes is open (ADR 0090): that one stays.
     */
    public static void chainAsContextLoader() {
        Thread thread = Thread.currentThread();
        ClassLoader context = thread.getContextClassLoader();
        if (runtimes.stream().noneMatch(runtime -> runtime.loader() == context)) {
            thread.setContextClassLoader(JarChain.classLoader());
        }
    }

    /** The servers that are loaded, in the order they were given. */
    public static List<ServerRuntime> runtimes() {
        return runtimes;
    }

    /**
     * What a method that hit {@code error} in a server's classes fails with: {@code NOT_SUPPORTED}.
     * Mostly a server's Lucene is older than the one leia's adapter is built for and lacks a class or
     * method it calls, which the JVM finds out only when it is called; or a class of the server fails
     * to initialize, or a format of it, which breaks Lucene's list of them. Empty for any other error,
     * and one that didn't come from a server's classes.
     */
    public static Optional<RpcException> notSupported(Error error) {
        return notSupported(error, runtimes);
    }

    /** As {@link #notSupported(Error)}, with the servers {@code runtimes}. */
    static Optional<RpcException> notSupported(Error error, List<ServerRuntime> runtimes) {
        if (!(error instanceof LinkageError || error instanceof ServiceConfigurationError)) {
            return Optional.empty();
        }
        for (StackTraceElement frame : error.getStackTrace()) {
            String loader = frame.getClassLoaderName();
            if (loader == null) {
                continue;
            }
            for (ServerRuntime runtime : runtimes) {
                if (loader.equals(runtime.loaderName())) {
                    return Optional.of(new RpcException(ErrorCode.NOT_SUPPORTED, message(error, runtime), error));
                }
            }
        }
        return Optional.empty();
    }

    private static String message(Error error, ServerRuntime runtime) {
        boolean missing = error instanceof IncompatibleClassChangeError
                || (error instanceof NoClassDefFoundError
                        && !String.valueOf(error.getMessage()).startsWith("Could not initialize"));
        if (missing) {
            return "The Lucene " + runtime.luceneVersion() + " of " + runtime.name()
                    + " lacks what leia needs for this, as leia's runtime is built for Lucene " + runtime.builtFor()
                    + ": " + error;
        }
        return "The classes of " + runtime.name() + " fail at this, with its Lucene " + runtime.luceneVersion()
                + " (more in leia.log): " + error;
    }
}
