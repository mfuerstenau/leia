package io.github.mfuerstenau.leia.server.runtime;

import io.github.mfuerstenau.leia.runtime.LuceneRuntime;
import io.github.mfuerstenau.leia.server.jars.JarChain;
import io.github.mfuerstenau.leia.server.jars.JarException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarFile;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;

/**
 * The folders of {@code runtimes/} beside {@code leia.jar}, one per runtime, each with the runtime's
 * adapter jar and the jars it lists in {@value #LIST} (ADR 0073): the official Lucene jars and, for
 * Lucene 10, leia-core's.
 *
 * <p>An old runtime gets a class loader of its own, whose parent sees only the JDK and the runtime
 * interface, so that its Lucene meets no other. Lucene 10's folder becomes the first link of the
 * {@link JarChain} instead, since the jars of {@code --jar} link against its Lucene (ADR 0065).
 *
 * <p>A folder that is missing, incomplete or refused is not fatal: leia starts, and {@code
 * getRuntimes} says what is wrong. The folders are loaded once per JVM, like the jar chain; a folder
 * that is loaded stays loaded.
 */
public final class RuntimeFolders {

    private static final Logger LOG = Logger.getLogger(RuntimeFolders.class.getName());

    /** The list of the jars beside the adapter, in the adapter jar. */
    public static final String LIST = "META-INF/leia/runtime.txt";

    private static final String API_PACKAGE = LuceneRuntime.class.getPackageName() + ".";

    /**
     * A runtime that leia knows.
     *
     * @param folder its folder in {@code runtimes/}, such as {@code lucene9}
     * @param indexMajors the majors of the indexes it reads, oldest first
     */
    public record Known(String folder, List<Integer> indexMajors) {

        /** The adapter jar in the folder, such as {@code leia-lucene9.jar}. */
        public String adapterJar() {
            return "leia-" + folder + ".jar";
        }
    }

    /** Every runtime leia knows, newest first. A new runtime is one more entry here. */
    public static final List<Known> KNOWN = List.of(
            new Known("lucene10", List.of(9, 10)),
            new Known("lucene9", List.of(8)),
            new Known("lucene7", List.of(6, 7)),
            new Known("lucene5", List.of(4, 5)),
            new Known("lucene3", List.of(2, 3)));

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
    }

    // Written while holding the class's lock, read without it.
    private static volatile Map<Known, State> states = initialStates();
    private static volatile List<LuceneRuntime> runtimes = List.of();
    private static volatile List<String> chainRefusals = List.of();

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
     * Loads the folders of {@code dir} whose runtime isn't loaded yet. Lucene 10's goes into the jar
     * chain, so this comes before the jars of {@code --jar}.
     */
    public static synchronized void load(Path dir) {
        Map<Known, State> now = new LinkedHashMap<>(states);
        List<LuceneRuntime> loaded = new ArrayList<>(runtimes);
        for (Known known : KNOWN) {
            if (now.get(known) instanceof State.Loaded) {
                continue;
            }
            State state = loadFolder(dir.resolve(known.folder()), known, loaded);
            now.put(known, state);
            switch (state) {
                case State.Loaded _ -> LOG.info(() -> "Loaded the runtime " + dir.resolve(known.folder()));
                case State.Missing _ -> LOG.info(() -> "No runtime " + dir.resolve(known.folder()));
                case State.Incomplete incomplete ->
                    LOG.warning(() -> "The runtime " + dir.resolve(known.folder()) + " lacks " + incomplete.jar());
                case State.Refused refused ->
                    LOG.warning(() -> "Refused the runtime " + dir.resolve(known.folder()) + ": " + refused.reason());
            }
        }
        runtimes = List.copyOf(loaded);
        states = Map.copyOf(now);
    }

    /**
     * Loads {@code folder}, the folder of {@code known}, and adds its runtime to {@code loaded}: an
     * old runtime's through a class loader of its own, Lucene 10's into the jar chain.
     */
    static State loadFolder(Path folder, Known known, List<LuceneRuntime> loaded) {
        Path adapter = folder.resolve(known.adapterJar());
        if (!Files.isRegularFile(adapter)) {
            return new State.Missing();
        }
        List<String> listed;
        try {
            listed = list(adapter);
        } catch (IOException e) {
            return new State.Refused("cannot read " + LIST + " of " + adapter + ": " + e.getMessage());
        }
        List<Path> jars = new ArrayList<>();
        jars.add(adapter);
        for (String name : listed) {
            Path jar = folder.resolve(name);
            if (!Files.isRegularFile(jar)) {
                return new State.Incomplete(name);
            }
            jars.add(jar);
        }
        return known.equals(LATEST) ? loadIntoChain(known, jars) : loadAlone(known, jars, loaded);
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
            } catch (MalformedURLException e) {
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
     *     org/apache/lucene/lucene-core/9.12.3/lucene-core-9.12.3.jar}; empty for leia's own jars,
     *     which {@code leia.jar} brings
     */
    public record ListedJar(String name, String sha256, long size, Optional<String> mavenPath) {}

    /** The file names that {@code adapter}'s list names, in order. */
    static List<String> list(Path adapter) throws IOException {
        try (JarFile jar = new JarFile(adapter.toFile())) {
            ZipEntry entry = jar.getEntry(LIST);
            if (entry == null) {
                throw new IOException("there is none");
            }
            try (InputStream in = jar.getInputStream(entry)) {
                return list(in).stream().map(ListedJar::name).toList();
            }
        }
    }

    /**
     * The jars of a list, in order.
     *
     * @throws IOException if it can't be read, or a line is malformed
     */
    public static List<ListedJar> list(InputStream list) throws IOException {
        List<ListedJar> jars = new ArrayList<>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(list, StandardCharsets.UTF_8));
        for (String line = reader.readLine(); line != null; line = reader.readLine()) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String[] fields = trimmed.split("\\s+");
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
            jars.add(new ListedJar(
                    name, fields[1], size, fields.length == 4 ? Optional.of(fields[3]) : Optional.empty()));
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

    /** What the jar chain refused when Lucene 10's folder was loaded into it; not fatal. */
    public static List<String> chainRefusals() {
        return chainRefusals;
    }

    /**
     * The parent of an old runtime's class loader: the runtime interface from leia's own loader, and
     * the JDK from the platform loader. Neither leia's classes nor the jar chain's Lucene are seen,
     * nor any resource but the JDK's.
     */
    private static final class ApiOnlyLoader extends ClassLoader {

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
