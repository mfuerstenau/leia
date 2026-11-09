package io.github.mfuerstenau.leia.server.runtime;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.runtime.LuceneRuntime;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.jars.JarChain;
import java.io.IOException;
import java.io.Reader;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.ServiceConfigurationError;
import java.util.Set;
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
 * OpenSearch's k-NN plugin, whose codecs its {@code knn_vector} indexes name, is loaded from {@code
 * plugins/} with what it extends (ADR 0092).
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

    /**
     * The plugins whose jars are loaded besides {@code lib/} (ADR 0092), each a folder of {@code
     * plugins/}. Only those that hold the codec of a mapping type: each plugin has a class loader of
     * its own in the server and a setup of its own, which leia would have to do for every one (ADR
     * 0090). OpenSearch's k-NN plugin has the codecs of the {@code knn_vector} fields.
     */
    private static final List<String> CODEC_PLUGINS = List.of("opensearch-knn");

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
     * adapters of {@code runtimesDirs}: for each, the adapter of the first folder that has one that is
     * current (ADR 0122). A server that is loaded already isn't loaded again.
     *
     * @throws ServerException if one can't be loaded: no Lucene in it, a Lucene whose major leia has
     *     no adapter for, an adapter that isn't installed, a second server for one major; none of
     *     them is loaded then
     */
    public static synchronized void load(List<Path> paths, List<RuntimesDir> runtimesDirs) throws ServerException {
        List<ServerRuntime> loaded = new ArrayList<>(runtimes);
        List<URLClassLoader> fresh = new ArrayList<>();
        try {
            for (Path path : paths.stream()
                    .map(path -> path.toAbsolutePath().normalize())
                    .distinct()
                    .toList()) {
                if (loaded.stream().noneMatch(runtime -> runtime.home().equals(path))) {
                    loaded.add(load(path, runtimesDirs, loaded, fresh));
                }
            }
        } catch (ServerException | RuntimeException e) {
            fresh.forEach(ServerRuntimes::close);
            throw e;
        }
        loaded.subList(runtimes.size(), loaded.size())
                .forEach(runtime -> LOG.info(() -> "Loaded " + runtime + " from " + runtime.home()));
        runtimes = List.copyOf(loaded);
    }

    private static ServerRuntime load(
            Path home, List<RuntimesDir> runtimesDirs, List<ServerRuntime> loaded, List<URLClassLoader> fresh)
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
        if (runtimesDirs.isEmpty()) {
            throw new ServerException(
                    home,
                    "it needs leia's adapter in runtimes/" + known.folder()
                            + ", and leia has no runtimes folder, since it doesn't run from leia.jar");
        }
        List<Path> adapterJars = adapterJars(home, runtimesDirs, known);
        String builtFor = builtFor(adapterJars.getFirst());
        List<Path> jars = new ArrayList<>(adapterJars);
        // Sorted by name; the plugins' jars only as pluginJars says.
        jars.addAll(jarsIn(home, lib));
        List<Path> plugins = pluginJars(home, lib);
        jars.addAll(plugins);
        String loaderName = "leia-server-" + known.folder();
        URLClassLoader loader = new URLClassLoader(
                loaderName, urls(home, jars), new RuntimeFolders.ApiOnlyLoader(ServerRuntimes.class.getClassLoader()));
        fresh.add(loader);
        ElasticsearchSetup.setUp(loader, lib.toAbsolutePath());
        if (!plugins.isEmpty()) {
            OpenSearchKnnSetup.setUp(loader, home);
        }
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
        return new ServerRuntime(reading.getFirst(), home, label(lib).orElse(null), lucene, builtFor, loader);
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
     * leia-core for Lucene 10, from the first of {@code dirs} that has them. The folder's Lucene isn't
     * needed, so the folder needn't be complete. If no folder does, the first problem found.
     */
    private static List<Path> adapterJars(Path home, List<RuntimesDir> dirs, RuntimeFolders.Known known)
            throws ServerException {
        ServerException missing = null;
        ServerException refused = null;
        for (RuntimesDir dir : dirs) {
            Path folder = dir.path().resolve(known.folder());
            try {
                return adapterJars(home, dir, folder, known);
            } catch (ServerException e) {
                // A folder without the adapter says least.
                if (Files.isRegularFile(folder.resolve(known.adapterJar()))) {
                    refused = refused == null ? e : refused;
                } else {
                    missing = missing == null ? e : missing;
                }
            }
        }
        throw refused != null ? refused : missing;
    }

    private static List<Path> adapterJars(Path home, RuntimesDir dir, Path folder, RuntimeFolders.Known known)
            throws ServerException {
        Path adapter = folder.resolve(known.adapterJar());
        String install = "install the runtime " + known.folder() + " with D on leia's start screen, or with leia"
                + " --download-runtimes --runtime " + known.folder();
        if (!Files.isRegularFile(adapter)) {
            throw new ServerException(home, "it needs leia's adapter " + adapter + ", which isn't there; " + install);
        }
        Optional<String> outdated = RuntimeFolders.outdated(adapter, known, ServerRuntimes.class.getClassLoader());
        if (outdated.isPresent()) {
            throw new ServerException(home, "it needs leia's adapter, which is " + outdated.get());
        }
        List<Path> jars = new ArrayList<>();
        jars.add(adapter);
        try {
            List<RuntimeFolders.ListedJar> own = RuntimeFolders.listed(adapter).stream()
                    .filter(listed -> listed.mavenPath() == null)
                    .toList();
            for (RuntimeFolders.ListedJar listed : own) {
                Path jar = folder.resolve(listed.name());
                if (!Files.isRegularFile(jar)) {
                    throw new ServerException(home, "it needs " + jar + ", which isn't there; " + install);
                }
                jars.add(jar);
            }
            if (dir.system()) {
                Optional<String> tampered = RuntimeFolders.mismatch(folder, own);
                if (tampered.isPresent()) {
                    throw new ServerException(home, "it needs leia's jars, but " + tampered.get());
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

    /** The jars directly in {@code folder}, sorted by name. */
    private static List<Path> jarsIn(Path home, Path folder) throws ServerException {
        try (Stream<Path> files = Files.list(folder)) {
            return files.filter(file -> file.getFileName().toString().endsWith(".jar"))
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(file -> file.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            throw new ServerException(home, "cannot list " + folder + ": " + e.getMessage(), e);
        }
    }

    /**
     * The jars of the {@link #CODEC_PLUGINS} that the installation has, sorted by name, each folder
     * followed by those it extends ({@code extended.plugins} of its {@code plugin-descriptor.properties}),
     * and those they extend in turn, each once, found in {@code modules/} or {@code plugins/}:
     * OpenSearch 3's k-NN plugin gets Guava from the {@code transport-grpc} module. They go after
     * {@code lib/}, whose classes win. None if the installation has no such plugin; also for a folder
     * of jars that isn't named {@code lib}, which has no installation around it.
     */
    static List<Path> pluginJars(Path home, Path lib) throws ServerException {
        Path absolute = lib.toAbsolutePath();
        Path installation = absolute.getParent();
        if (installation == null || !absolute.getFileName().toString().equals("lib")) {
            return List.of();
        }
        List<Path> jars = new ArrayList<>();
        for (String plugin : CODEC_PLUGINS) {
            Path folder = installation.resolve("plugins").resolve(plugin);
            if (!Files.isDirectory(folder)) {
                continue;
            }
            jars.addAll(jarsIn(home, folder));
            addExtended(home, installation, plugin, folder, new HashSet<>(Set.of(plugin)), jars);
        }
        return jars;
    }

    /**
     * Adds the jars of the plugins that {@code folder} extends, depth first as OpenSearch follows them;
     * {@code seen} holds the names taken, so that a plugin extended twice, or in a cycle, is taken once.
     */
    private static void addExtended(
            Path home, Path installation, String plugin, Path folder, Set<String> seen, List<Path> jars)
            throws ServerException {
        for (String extended : extendedPlugins(folder)) {
            if (!seen.add(extended)) {
                continue;
            }
            Path extendedFolder = Stream.of("modules", "plugins")
                    .map(parent -> installation.resolve(parent).resolve(extended))
                    .filter(Files::isDirectory)
                    .findFirst()
                    .orElse(null);
            if (extendedFolder == null) {
                LOG.fine(() -> "The " + plugin + " plugin of " + home + " extends " + extended + ", which isn't there");
            } else {
                jars.addAll(jarsIn(home, extendedFolder));
                addExtended(home, installation, extended, extendedFolder, seen, jars);
            }
        }
    }

    /** The names in {@code extended.plugins} of the plugin in {@code folder}; none if it can't be read. */
    private static List<String> extendedPlugins(Path folder) {
        Properties descriptor = new Properties();
        try (Reader reader = Files.newBufferedReader(folder.resolve("plugin-descriptor.properties"))) {
            descriptor.load(reader);
        } catch (IOException e) {
            LOG.log(Level.FINE, e, () -> "Cannot read the descriptor of the plugin in " + folder);
            return List.of();
        }
        return Stream.of(descriptor.getProperty("extended.plugins", "").split(","))
                .map(String::strip)
                .filter(name -> !name.isEmpty())
                .toList();
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
    // A class loader is one by identity
    @SuppressWarnings("ReferenceEquality")
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
