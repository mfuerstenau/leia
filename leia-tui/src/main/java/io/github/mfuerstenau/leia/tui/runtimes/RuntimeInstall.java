package io.github.mfuerstenau.leia.tui.runtimes;

import io.github.mfuerstenau.leia.server.runtime.RuntimeFolders;
import io.github.mfuerstenau.leia.tui.text.TextFormat;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.Serial;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

/**
 * {@code leia --download-runtimes} (ADR 0073): installs runtimes' folders into {@code runtimes/}
 * beside {@code leia.jar}. leia's own jars, the adapter and for Lucene 10 leia-core's, come from
 * {@code leia.jar}, which embeds them under {@value #RESOURCES}; Lucene's come from Maven Central,
 * as the adapter's list names them, each checked against the list's SHA-256 and size.
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

    /** Where {@code leia.jar} holds each runtime's own jars: {@code leia-runtimes/<folder>/<jar>}. */
    public static final String RESOURCES = "leia-runtimes/";

    /** Maven Central. */
    public static final URI CENTRAL = URI.create("https://repo1.maven.org/maven2/");

    /** The suffix of a download that isn't done yet. */
    private static final String PART = ".part";

    private static final int BUFFER = 64 * 1024;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(10);

    private final Path runtimesDir;
    private final URI base;
    private final ClassLoader resourceLoader;

    /**
     * @param runtimesDir the folder {@code runtimes} beside {@code leia.jar}
     * @param base the repository the Lucene jars are downloaded from, such as {@link #CENTRAL}
     * @param resources the class loader that sees {@value #RESOURCES}
     */
    public RuntimeInstall(Path runtimesDir, URI base, ClassLoader resources) {
        this.runtimesDir = runtimesDir;
        this.base = base.toString().endsWith("/") ? base : URI.create(base + "/");
        this.resourceLoader = resources;
    }

    /** The folder {@code runtimes}. */
    public Path runtimesDir() {
        return runtimesDir;
    }

    /** The repository the Lucene jars are downloaded from. */
    public URI repository() {
        return base;
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

    /**
     * A Lucene jar to download.
     *
     * @param folder the runtime's folder, such as {@code lucene9}
     * @param jar the jar as the adapter's list names it
     * @param uri where it is downloaded from
     */
    public record Download(String folder, RuntimeFolders.ListedJar jar, URI uri) {}

    /**
     * A runtime's folder as {@code leia.jar} has it, and what of it needs downloading.
     *
     * @param known the runtime
     * @param adapter its adapter jar, from {@code leia.jar}
     * @param listed the jars its adapter lists, in order
     * @param downloads the Lucene jars that aren't there or don't match
     * @param kept the Lucene jars that are there and match
     */
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

        /** The bytes to download. */
        public long downloadSize() {
            return downloads.stream()
                    .mapToLong(download -> download.jar().size())
                    .sum();
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

        /** A runtime failed, for {@code message}; the others go on. */
        default void runtimeFailed(String folder, String message) {}

        /** A line per jar to {@code out}, and a line per failure to {@code err}. */
        static Listener printing(PrintStream out, PrintStream err) {
            return new Listener() {
                @Override
                public void jarDone(String folder, String jar, JarDone how, long size) {
                    String label = folder + "/" + jar + ": ";
                    out.println(
                            switch (how) {
                                case WRITTEN -> label + "written, " + TextFormat.size(size);
                                case DOWNLOADED -> label + "downloaded, " + TextFormat.size(size);
                                case KEPT -> label + "kept";
                                case REMOVED -> label + "removed";
                            });
                }

                @Override
                public void runtimeFailed(String folder, String message) {
                    err.println("leia: " + message);
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
            if (jar.mavenPath().isEmpty()) {
                continue;
            }
            Path target = folder.resolve(jar.name());
            if (Files.isRegularFile(target) && sha256(target).equals(jar.sha256())) {
                kept.add(jar);
            } else {
                downloads.add(new Download(
                        folderName, jar, base.resolve(jar.mavenPath().get())));
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
                        + TextFormat.size(download.jar().size()));
                out.println("    " + download.uri());
                out.println("    SHA-256 " + download.jar().sha256());
            }
        }
        out.println();
        out.println("leia's own jars, the adapters and leia-core, come from leia.jar and aren't downloaded.");
        int kept = runtimes.stream().mapToInt(runtime -> runtime.kept().size()).sum();
        if (kept > 0) {
            out.println(TextFormat.count(kept, "jar") + " already there and matching " + (kept == 1 ? "is" : "are")
                    + " kept.");
        }
    }

    /** Where the Lucene jars come from, as a sentence without its end. */
    public String source() {
        return base.equals(CENTRAL)
                ? "leia downloads these official Lucene jars from Maven Central (" + CENTRAL + "), unchanged"
                : "leia downloads these official Lucene jars of Maven Central, unchanged, from " + base
                        + " (-Dleia.download.url)";
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
        try (HttpClient client = builder.build()) {
            for (RuntimePlan runtime : taken) {
                if (cancelled.getAsBoolean()) {
                    results.put(runtime.known(), new Result.Cancelled());
                    continue;
                }
                try {
                    install(client, runtime, listener, cancelled);
                    results.put(runtime.known(), new Result.Installed());
                } catch (InstallException e) {
                    results.put(runtime.known(), new Result.Failed(e.getMessage()));
                    listener.runtimeFailed(runtime.known().folder(), e.getMessage());
                } catch (CancelledException _) {
                    results.put(runtime.known(), new Result.Cancelled());
                }
            }
        }
        Map<RuntimeFolders.Known, Result> ordered = new LinkedHashMap<>();
        plan.runtimes().stream()
                .map(RuntimePlan::known)
                .filter(results::containsKey)
                .forEach(known -> ordered.put(known, results.get(known)));
        return new Outcome(ordered);
    }

    private void install(HttpClient client, RuntimePlan runtime, Listener listener, BooleanSupplier cancelled)
            throws InstallException, CancelledException {
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
        Set<String> downloads = new HashSet<>();
        runtime.downloads().forEach(download -> downloads.add(download.jar().name()));
        for (RuntimeFolders.ListedJar jar : runtime.listed()) {
            if (cancelled.getAsBoolean()) {
                throw new CancelledException();
            }
            names.add(jar.name());
            if (jar.mavenPath().isEmpty()) {
                writeOwn(folder, jar.name(), resource(folderName, jar.name()), listener);
            } else if (downloads.contains(jar.name())) {
                download(client, folder, jar, base.resolve(jar.mavenPath().get()), listener, cancelled);
            } else {
                listener.jarDone(folderName, jar.name(), JarDone.KEPT, jar.size());
            }
        }
        removeStale(folder, names, listener);
    }

    /** One of leia's jars, embedded in {@code leia.jar}. */
    private byte[] resource(String folder, String jar) throws InstallException {
        String name = RESOURCES + folder + "/" + jar;
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
     * Downloads a Lucene jar from {@code uri}, and moves it into place once it matches. Checks for
     * a cancel after each buffer, and then leaves nothing behind.
     */
    private void download(
            HttpClient client,
            Path folder,
            RuntimeFolders.ListedJar jar,
            URI uri,
            Listener listener,
            BooleanSupplier cancelled)
            throws InstallException, CancelledException {
        Path target = folder.resolve(jar.name());
        String folderName = folder.getFileName().toString();
        listener.jarStarted(folderName, jar.name(), jar.size());
        Path part = null;
        try {
            part = Files.createTempFile(folder, jar.name(), PART);
            HttpResponse<InputStream> response = client.send(
                    HttpRequest.newBuilder(uri).timeout(DOWNLOAD_TIMEOUT).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            MessageDigest digest = newDigest();
            long size = 0;
            try (InputStream body = new DigestInputStream(response.body(), digest);
                    OutputStream file = Files.newOutputStream(part)) {
                if (response.statusCode() != 200) {
                    throw new InstallException(
                            "cannot download " + jar.name() + " from " + uri + ": HTTP " + response.statusCode());
                }
                byte[] buffer = new byte[BUFFER];
                for (int n = body.read(buffer); n >= 0; n = body.read(buffer)) {
                    file.write(buffer, 0, n);
                    size += n;
                    listener.bytes(n);
                    if (cancelled.getAsBoolean()) {
                        throw new CancelledException();
                    }
                }
            }
            String sha256 = HexFormat.of().formatHex(digest.digest());
            if (size != jar.size() || !sha256.equals(jar.sha256())) {
                throw new InstallException("the download of " + jar.name() + " from " + uri
                        + " doesn't match: " + size + " bytes with SHA-256 " + sha256 + ", expected " + jar.size()
                        + " bytes with SHA-256 " + jar.sha256());
            }
            move(part, target);
            listener.jarDone(folderName, jar.name(), JarDone.DOWNLOADED, size);
        } catch (InstallException | CancelledException e) {
            delete(part);
            throw e;
        } catch (IOException e) {
            delete(part);
            throw new InstallException("cannot download " + jar.name() + " from " + uri + " to " + target + ": " + e);
        } catch (InterruptedException _) {
            delete(part);
            Thread.currentThread().interrupt();
            throw new InstallException("interrupted while downloading " + jar.name() + " from " + uri);
        }
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

    private static String sha256(Path file) throws InstallException {
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

    /** The repository to download from: {@code -Dleia.download.url}, or {@link #CENTRAL}. */
    public static URI base() {
        return Optional.ofNullable(System.getProperty("leia.download.url"))
                .filter(url -> !url.isEmpty())
                .map(URI::create)
                .orElse(CENTRAL);
    }
}
