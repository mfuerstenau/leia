package io.github.mfuerstenau.leia.server.runtime;

import io.github.mfuerstenau.leia.runtime.LuceneRuntime;
import io.github.mfuerstenau.leia.server.jars.JarChain;
import io.github.mfuerstenau.leia.server.jars.JarException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarFile;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import org.jspecify.annotations.Nullable;

/**
 * The folders of the runtimes folder, one per runtime, each with the runtime's adapter jar and the
 * jars it lists in {@value #LIST} (ADR 0073): the official Lucene jars and, for Lucene 10,
 * leia-core's. The runtimes folders are {@code runtimes/} beside {@code leia.jar}, the data folders'
 * (ADR 0098, ADR 0122).
 *
 * <p>An old runtime gets a class loader of its own, whose parent sees only the JDK and the runtime
 * interface, so that its Lucene meets no other. Lucene 10's folder becomes the first link of the
 * {@link JarChain} instead, since the jars of {@code --jar} link against its Lucene (ADR 0065).
 *
 * <p>Each runtime is looked up in the runtimes folders one at a time, user folders first, then system ones (ADR 0122).
 * A folder that is missing, incomplete or refused is not fatal: leia starts, and {@code
 * getRuntimes} says what is wrong. A folder whose adapter isn't the one {@code leia.jar} embeds is
 * another leia's, and is refused as outdated (ADR 0098). The folders are loaded per JVM, like the jar chain: at startup, and
 * again by {@code loadRuntimes} after a download (ADR 0080); a folder that is loaded stays loaded.
 */
public final class RuntimeFolders {

    private static final Logger LOG = Logger.getLogger(RuntimeFolders.class.getName());

    /** The list of the jars beside the adapter, in the adapter jar. */
    public static final String LIST = "META-INF/leia/runtime.txt";

    /** Where {@code leia.jar} holds each runtime's own jars: {@code leia-runtimes/<folder>/<jar>}. */
    public static final String EMBEDDED = "leia-runtimes/";

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private static final String API_PACKAGE = LuceneRuntime.class.getPackageName() + ".";

    /**
     * A runtime that leia knows.
     *
     * @param folder its folder in {@code runtimes/}, such as {@code lucene9}
     * @param name its name for people, such as {@code Lucene 9}
     * @param indexMajors the majors of the indexes it reads, oldest first
     */
    public record Known(String folder, String name, List<Integer> indexMajors) {

        /** The adapter jar in the folder, such as {@code leia-lucene9.jar}. */
        public String adapterJar() {
            return "leia-" + folder + ".jar";
        }
    }

    /**
     * Every runtime leia knows, newest first, from the catalog {@value RuntimeCatalog#RESOURCE} (ADR
     * 0078). A new runtime is one more entry there.
     */
    // A List.copyOf, which is immutable (ADR 0088)
    @SuppressWarnings("java:S2386")
    public static final List<Known> KNOWN = RuntimeCatalog.load(RuntimeFolders.class.getClassLoader());

    /** The runtime that reads the newest indexes, and those whose version can't be detected (ADR 0071). */
    public static final Known LATEST = KNOWN.getFirst();

    /** How a folder was found. */
    public sealed interface State {

        /** Its runtime is loaded. */
        record Loaded() implements State {}

        /** No folder, or no adapter jar in it. */
        record Missing() implements State {}

        /** The adapter lists {@code jar}, which isn't in the folder. Nothing of it is loaded. */
        record Incomplete(String jar) implements State {}

        /** The folder is complete, but its runtime can't be used, for {@code reason}. */
        record Refused(String reason) implements State {}

        /**
         * Lucene 10's folder is complete, but the jar chain has jars already, which link against no
         * Lucene of it: leia has to restart to load it (ADR 0080).
         */
        record Restart() implements State {}
    }

    // Written while holding the class's lock, read without it.
    // An immutable value, published through volatile (ADR 0088)
    @SuppressWarnings("java:S3077")
    private static volatile Map<Known, State> states = initialStates();

    @SuppressWarnings("java:S3077")
    private static volatile List<LuceneRuntime> runtimes = List.of();

    @SuppressWarnings("java:S3077")
    private static volatile List<String> chainRefusals = List.of();

    @SuppressWarnings("java:S3077")
    private static volatile List<RuntimesDir> dirs = List.of();

    @SuppressWarnings("java:S3077")
    private static volatile Map<Known, Path> loadedFrom = Map.of();

    private RuntimeFolders() {}

    private static Map<Known, State> initialStates() {
        Map<Known, State> states = new LinkedHashMap<>();
        for (Known known : KNOWN) {
            states.put(known, new State.Missing());
        }
        return Map.copyOf(states);
    }

    /** The runtime that reads indexes of {@code major}, such as {@code lucene3} for 2 and 3. */
    public static Optional<Known> forMajor(int major) {
        return KNOWN.stream()
                .filter(known -> known.indexMajors().contains(major))
                .findFirst();
    }

    /**
     * Loads the runtimes from the folder {@code dir}, as one user folder.
     *
     * @see #load(List)
     */
    public static synchronized void load(Path dir) {
        load(List.of(RuntimesDir.user(dir)));
    }

    /**
     * Loads the runtimes that aren't loaded yet, each from the first of {@code dirs} whose folder for
     * it is complete and usable (ADR 0122): a folder that is missing, incomplete or refused is passed
     * over for the next, and if none loads, the first state that isn't {@code Missing} is the
     * runtime's, since it says the most. A system folder is checked jar by jar against the list of its
     * adapter first. Lucene 10's goes into the jar chain, so this comes before the jars of {@code
     * --jar}.
     */
    public static synchronized void load(List<RuntimesDir> dirs) {
        RuntimeFolders.dirs = List.copyOf(dirs);
        ClassLoader embedded = RuntimeFolders.class.getClassLoader();
        Map<Known, State> now = new LinkedHashMap<>(states);
        Map<Known, Path> from = new LinkedHashMap<>(loadedFrom);
        List<LuceneRuntime> loaded = new ArrayList<>(runtimes);
        for (Known known : KNOWN) {
            if (now.get(known) instanceof State.Loaded) {
                continue;
            }
            Found lookedUp = lookUp(known, dirs, loaded, embedded);
            State state = lookedUp.state();
            Optional<Path> found = Optional.ofNullable(lookedUp.folder());
            now.put(known, state);
            if (found.isPresent() && state instanceof State.Loaded) {
                from.put(known, found.get());
            }
            Path where = found.orElse(Path.of(known.folder()));
            switch (state) {
                case State.Loaded _ -> LOG.info(() -> "Loaded the runtime " + where);
                case State.Missing _ -> LOG.info(() -> "No runtime " + known.folder() + " in " + dirs);
                case State.Incomplete incomplete ->
                    LOG.warning(() -> "The runtime " + where + " lacks " + incomplete.jar());
                case State.Refused refused ->
                    LOG.warning(() -> "Refused the runtime " + where + ": " + refused.reason());
                case State.Restart _ ->
                    LOG.info(() -> "The runtime " + where
                            + " is complete, but the jar chain has jars; restart leia to load it");
            }
        }
        runtimes = List.copyOf(loaded);
        states = Map.copyOf(now);
        loadedFrom = Map.copyOf(from);
    }

    /**
     * How a runtime was found.
     *
     * @param state the state
     * @param folder the folder the state is of; {@code null} if no folder had the runtime
     */
    record Found(State state, @Nullable Path folder) {}

    /**
     * Looks for the runtime {@code known} in {@code dirs}, in order, and loads it from the first folder
     * that is usable, adding it to {@code loaded}. If none is, the first state that isn't {@code
     * Missing}, with its folder, or {@code Missing}.
     */
    static Found lookUp(Known known, List<RuntimesDir> dirs, List<LuceneRuntime> loaded, ClassLoader embedded) {
        Found best = new Found(new State.Missing(), null);
        for (RuntimesDir dir : dirs) {
            Path folder = dir.path().resolve(known.folder());
            Inspection inspection = inspect(folder, known, dir.system(), embedded);
            if (inspection instanceof Inspection.Ready ready) {
                // The load itself happens once: Lucene 10's can't be undone.
                return new Found(loadJars(known, ready.jars(), loaded), folder);
            }
            State unusable = ((Inspection.Unusable) inspection).state();
            if (best.state() instanceof State.Missing && !(unusable instanceof State.Missing)) {
                best = new Found(unusable, folder);
            }
        }
        return best;
    }

    /**
     * Loads the folders of the {@code dirs} that {@link #load} was given whose runtime isn't loaded
     * yet, such as those just downloaded (ADR 0080); does nothing if {@link #load} never ran.
     */
    public static synchronized void reload() {
        if (!dirs.isEmpty()) {
            load(dirs);
        }
    }

    /** What looking into a runtime's folder found: jars to load, or why not. */
    private sealed interface Inspection {

        record Ready(List<Path> jars) implements Inspection {}

        record Unusable(State state) implements Inspection {}
    }

    /**
     * Loads {@code folder}, the folder of {@code known}, and adds its runtime to {@code loaded}: an
     * old runtime's through a class loader of its own, Lucene 10's into the jar chain.
     */
    static State loadFolder(Path folder, Known known, List<LuceneRuntime> loaded) {
        return loadFolder(folder, known, loaded, RuntimeFolders.class.getClassLoader());
    }

    /**
     * {@link #loadFolder(Path, Known, List)}, with {@code embedded} the class loader that sees the
     * adapters that {@code leia.jar} embeds under {@value #EMBEDDED}.
     */
    static State loadFolder(Path folder, Known known, List<LuceneRuntime> loaded, ClassLoader embedded) {
        return switch (inspect(folder, known, false, embedded)) {
            case Inspection.Ready ready -> loadJars(known, ready.jars(), loaded);
            case Inspection.Unusable unusable -> unusable.state();
        };
    }

    /**
     * Looks into {@code folder}: is the adapter there, is it leia's own, are the jars its list names
     * there; and, for a {@code system} folder, do they match the SHA-256 and size of the list. Loads
     * nothing.
     */
    private static Inspection inspect(Path folder, Known known, boolean system, ClassLoader embedded) {
        Path adapter = folder.resolve(known.adapterJar());
        if (!Files.isRegularFile(adapter)) {
            return new Inspection.Unusable(new State.Missing());
        }
        Optional<String> outdated = outdated(adapter, known, embedded);
        if (outdated.isPresent()) {
            return new Inspection.Unusable(new State.Refused(outdated.get()));
        }
        if (system && embedded.getResource(EMBEDDED + known.folder() + "/" + known.adapterJar()) == null) {
            return new Inspection.Unusable(new State.Refused(
                    "cannot trust " + adapter + " in a system folder: leia.jar embeds no adapter to compare it with"));
        }
        List<ListedJar> listed;
        try {
            listed = listed(adapter);
        } catch (IOException e) {
            return new Inspection.Unusable(
                    new State.Refused("cannot read " + LIST + " of " + adapter + ": " + e.getMessage()));
        }
        List<Path> jars = new ArrayList<>();
        jars.add(adapter);
        for (ListedJar each : listed) {
            Path jar = folder.resolve(each.name());
            if (!Files.isRegularFile(jar)) {
                return new Inspection.Unusable(new State.Incomplete(each.name()));
            }
            jars.add(jar);
        }
        if (system) {
            Optional<String> tampered = mismatch(folder, listed);
            if (tampered.isPresent()) {
                return new Inspection.Unusable(new State.Refused(tampered.get()));
            }
        }
        return new Inspection.Ready(jars);
    }

    /**
     * Why a jar of {@code listed} in {@code folder} isn't the one the list describes: its size or
     * SHA-256 differs. Empty if each jar that is there matches. A system folder may be writable by
     * others (ADR 0122), so its jars are trusted only by their hash.
     */
    static Optional<String> mismatch(Path folder, List<ListedJar> listed) {
        for (ListedJar each : listed) {
            Path jar = folder.resolve(each.name());
            try {
                if (Files.size(jar) != each.size()) {
                    return Optional.of(jar + " is not the jar leia lists: its size differs");
                }
                try (InputStream in = Files.newInputStream(jar)) {
                    if (!HexFormat.of().formatHex(sha256(in)).equalsIgnoreCase(each.sha256())) {
                        return Optional.of(jar + " is not the jar leia lists: its SHA-256 differs");
                    }
                }
            } catch (IOException e) {
                return Optional.of("cannot check " + jar + ": " + e.getMessage());
            }
        }
        return Optional.empty();
    }

    private static State loadJars(Known known, List<Path> jars, List<LuceneRuntime> loaded) {
        if (!known.equals(LATEST)) {
            return loadAlone(known, jars, loaded);
        }
        // At startup the chain is empty here; later, the jars in it link against no Lucene of this
        // folder, and a chain can't be rebuilt under them (ADR 0080).
        return JarChain.jars().isEmpty() ? loadIntoChain(known, jars) : new State.Restart();
    }

    /** Lucene 10's folder, as the first link of the jar chain (ADR 0065). */
    private static State loadIntoChain(Known known, List<Path> jars) {
        try {
            JarChain.load(jars);
        } catch (JarException e) {
            return new State.Refused(e.getMessage());
        }
        Runtimes.Loaded found = Runtimes.load(JarChain.classLoader());
        // A loader can't be unloaded: a runtime refused here stays in the chain, unused.
        chainRefusals = found.refused();
        if (found.runtimes().stream().anyMatch(runtime -> reads(runtime, known))) {
            return new State.Loaded();
        }
        return new State.Refused(
                found.refused().isEmpty() ? "no runtime in " + jars.getFirst() : String.join("; ", found.refused()));
    }

    /** An old runtime, through a class loader of its own (ADR 0073). */
    private static State loadAlone(Known known, List<Path> jars, List<LuceneRuntime> loaded) {
        URL[] urls = new URL[jars.size()];
        for (int i = 0; i < urls.length; i++) {
            try {
                urls[i] = jars.get(i).toUri().toURL();
            } catch (MalformedURLException _) {
                return new State.Refused("not a jar: " + jars.get(i));
            }
        }
        URLClassLoader loader = new URLClassLoader(
                "leia-" + known.folder(), urls, new ApiOnlyLoader(RuntimeFolders.class.getClassLoader()));
        Runtimes.Loaded found = Runtimes.load(loader);
        List<LuceneRuntime> reading = found.runtimes().stream()
                .filter(runtime -> reads(runtime, known))
                .toList();
        if (reading.isEmpty()) {
            close(loader);
            return new State.Refused(
                    found.refused().isEmpty()
                            ? "no runtime for " + known.folder() + " in " + jars.getFirst()
                            : String.join("; ", found.refused()));
        }
        loaded.addAll(reading);
        return new State.Loaded();
    }

    /**
     * Why {@code adapter}, the adapter jar of {@code known}, isn't leia's own: it differs from the one
     * that {@code embedded} sees under {@value #EMBEDDED}, so it is another leia's (ADR 0098). The
     * adapter alone is enough, since its list carries the SHA-256 of leia's other own jars. Empty if it
     * matches, or if there is no embedded adapter to compare, as under Gradle.
     */
    static Optional<String> outdated(Path adapter, Known known, ClassLoader embedded) {
        String name = EMBEDDED + known.folder() + "/" + known.adapterJar();
        try (InputStream own = embedded.getResourceAsStream(name)) {
            if (own == null) {
                return Optional.empty();
            }
            byte[] ownHash = sha256(own);
            byte[] hash;
            try (InputStream in = Files.newInputStream(adapter)) {
                hash = sha256(in);
            }
            return MessageDigest.isEqual(ownHash, hash)
                    ? Optional.empty()
                    : Optional.of("outdated: " + adapter + " is another leia's, and couldn't be refreshed;"
                            + " close any other leia that uses it, make its folder writable, and restart leia");
        } catch (IOException e) {
            return Optional.of("cannot compare " + adapter + " with leia's own: " + e.getMessage());
        }
    }

    private static byte[] sha256(InputStream in) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java has SHA-256", e);
        }
        try (DigestInputStream digesting = new DigestInputStream(in, digest)) {
            digesting.transferTo(OutputStream.nullOutputStream());
        }
        return digest.digest();
    }

    private static boolean reads(LuceneRuntime runtime, Known known) {
        return runtime.indexMajors().contains(known.indexMajors().getFirst());
    }

    /**
     * A jar that an adapter's list names, one line of it: {@code <name> <SHA-256> <size>
     * [<Maven path>]}.
     *
     * @param name its file name in the runtime's folder
     * @param sha256 its SHA-256, in lower-case hex
     * @param size its size in bytes
     * @param mavenPath its path on Maven Central, such as {@code
     *     org/apache/lucene/lucene-core/9.12.3/lucene-core-9.12.3.jar}; {@code null} for leia's own jars,
     *     which {@code leia.jar} brings
     */
    public record ListedJar(
            String name, String sha256, long size, @Nullable String mavenPath) {}

    /** The file names that {@code adapter}'s list names, in order. */
    static List<String> listedJars(Path adapter) throws IOException {
        return listed(adapter).stream().map(ListedJar::name).toList();
    }

    /** The jars that {@code adapter}'s list names, in order. */
    static List<ListedJar> listed(Path adapter) throws IOException {
        try (JarFile jar = new JarFile(adapter.toFile())) {
            ZipEntry entry = jar.getEntry(LIST);
            if (entry == null) {
                throw new IOException("there is none");
            }
            try (InputStream in = jar.getInputStream(entry)) {
                return listedJars(in);
            }
        }
    }

    /**
     * The jars of a list, in order.
     *
     * @throws IOException if it can't be read, or a line is malformed
     */
    public static List<ListedJar> listedJars(InputStream list) throws IOException {
        List<ListedJar> jars = new ArrayList<>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(list, StandardCharsets.UTF_8));
        for (String line = reader.readLine(); line != null; line = reader.readLine()) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String[] fields = WHITESPACE.split(trimmed);
            if (fields.length < 3 || fields.length > 4) {
                throw new IOException("not a line of a runtime's list: " + trimmed);
            }
            String name = fields[0];
            if (name.contains("/") || name.contains("\\") || name.startsWith(".") || !name.endsWith(".jar")) {
                throw new IOException("not a jar's file name: " + name);
            }
            long size;
            try {
                size = Long.parseLong(fields[2]);
            } catch (NumberFormatException e) {
                throw new IOException("not a size: " + fields[2], e);
            }
            jars.add(new ListedJar(name, fields[1], size, fields.length == 4 ? fields[3] : null));
        }
        return jars;
    }

    private static void close(URLClassLoader loader) {
        try {
            loader.close();
        } catch (IOException e) {
            LOG.log(Level.FINE, "Cannot close the class loader of a refused runtime", e);
        }
    }

    /** The old runtimes that are loaded, each from its own folder. */
    public static List<LuceneRuntime> runtimes() {
        return runtimes;
    }

    /** How the folder of {@code known} was found. */
    public static State state(Known known) {
        return states.get(known);
    }

    /** The folder that the runtime of {@code known} was loaded from, if it was loaded from one (ADR 0122). */
    public static Optional<Path> loadedFrom(Known known) {
        return Optional.ofNullable(loadedFrom.get(known));
    }

    /** What the jar chain refused when Lucene 10's folder was loaded into it; not fatal. */
    public static List<String> chainRefusals() {
        return chainRefusals;
    }

    /**
     * The parent of an old runtime's class loader, and of a server's (ADR 0090): the runtime interface
     * from leia's own loader, and the JDK from the platform loader. Neither leia's classes nor the jar
     * chain's Lucene are seen, nor any resource but the JDK's.
     */
    static final class ApiOnlyLoader extends ClassLoader {

        private final ClassLoader leia;

        ApiOnlyLoader(ClassLoader leia) {
            super("leia-runtime-api", ClassLoader.getPlatformClassLoader());
            this.leia = leia;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith(API_PACKAGE)) {
                return leia.loadClass(name);
            }
            return super.loadClass(name, resolve);
        }
    }
}
