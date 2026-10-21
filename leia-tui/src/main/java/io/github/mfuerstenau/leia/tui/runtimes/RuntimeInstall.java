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
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

/**
 * {@code leia --download-runtimes} (ADR 0073): installs each runtime's folder into {@code runtimes/}
 * beside {@code leia.jar}. leia's own jars, the adapter and for Lucene 10 leia-core's, come from
 * {@code leia.jar}, which embeds them under {@value #RESOURCES}; Lucene's come from Maven Central,
 * as the adapter's list names them, each checked against the list's SHA-256 and size.
 *
 * <p>Before it writes anything, it lists the Lucene jars it would download, each with its URL,
 * SHA-256 and size, and downloads them only once the user says yes, or with {@code --yes} (ADR
 * 0077). With nothing to download, it asks nothing.
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

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(10);

    private final Path runtimesDir;
    private final URI base;
    private final ClassLoader resources;
    private final PrintStream out;
    private final PrintStream err;
    private final Prompt prompt;

    /**
     * @param runtimesDir the folder {@code runtimes} beside {@code leia.jar}
     * @param base the repository the Lucene jars are downloaded from, such as {@link #CENTRAL}
     * @param resources the class loader that sees {@value #RESOURCES}
     * @param out gets a line per jar
     * @param err gets what failed
     * @param prompt asks whether to download
     */
    public RuntimeInstall(
            Path runtimesDir, URI base, ClassLoader resources, PrintStream out, PrintStream err, Prompt prompt) {
        this.runtimesDir = runtimesDir;
        this.base = base.toString().endsWith("/") ? base : URI.create(base + "/");
        this.resources = resources;
        this.out = out;
        this.err = err;
        this.prompt = prompt;
    }

    /** A failure that ends the install, with what to say about it. */
    private static final class InstallException extends Exception {

        @Serial
        private static final long serialVersionUID = 1L;

        InstallException(String message) {
            super(message);
        }
    }

    /** Asks the user a question; empty if there is no one to ask, such as without a terminal. */
    @FunctionalInterface
    public interface Prompt {

        /**
         * @param question what to ask, ending in a space
         * @return the answer, or empty if there is no one to ask or no answer came
         */
        Optional<String> ask(String question);
    }

    /**
     * A runtime's folder as {@code leia.jar} has it, and the Lucene jars of it that need
     * downloading.
     */
    private record Planned(
            RuntimeFolders.Known known, byte[] adapter, List<RuntimeFolders.ListedJar> listed, Set<String> missing) {}

    /** A Lucene jar to download. */
    private record Download(String folder, RuntimeFolders.ListedJar jar, URI uri) {}

    /**
     * Installs every runtime leia knows. First it lists the Lucene jars that aren't there or don't
     * match, and asks before it downloads them, unless {@code yes} (ADR 0077).
     *
     * @param yes download without asking ({@code --yes})
     * @return the exit status: 0, or 1 if a runtime couldn't be installed or the download wasn't
     *     agreed to
     */
    public int run(boolean yes) {
        List<Planned> plan = new ArrayList<>();
        List<Download> downloads = new ArrayList<>();
        try {
            for (RuntimeFolders.Known known : RuntimeFolders.KNOWN) {
                plan.add(plan(known, downloads));
            }
        } catch (InstallException e) {
            err.println("leia: " + e.getMessage());
            return 1;
        }
        long lucene = plan.stream()
                .flatMap(planned -> planned.listed().stream())
                .filter(jar -> jar.mavenPath().isPresent())
                .count();
        int kept = (int) lucene - downloads.size();
        if (downloads.isEmpty()) {
            out.println("Every Lucene jar is there and matches; nothing to download.");
        } else {
            printDownloads(downloads, kept);
            if (yes) {
                out.println("--yes given; downloading.");
            } else {
                long total = downloads.stream().mapToLong(d -> d.jar().size()).sum();
                out.flush();
                Optional<String> answer = prompt.ask(
                        "Download " + jars(downloads.size()) + ", " + TextFormat.size(total) + " in all? [y/N] ");
                if (answer.isEmpty()) {
                    err.println("leia: nothing downloaded: --download-runtimes asks before it downloads, and there is"
                            + " no terminal to ask on; --yes downloads without asking");
                    return 1;
                }
                String said = answer.get().strip().toLowerCase(Locale.ROOT);
                if (!said.equals("y") && !said.equals("yes")) {
                    err.println("leia: nothing downloaded");
                    return 1;
                }
            }
        }
        HttpClient.Builder builder =
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).followRedirects(HttpClient.Redirect.NORMAL);
        // -Djava.net.useSystemProxies=true makes it the system's.
        ProxySelector proxies = ProxySelector.getDefault();
        if (proxies != null) {
            builder.proxy(proxies);
        }
        try (HttpClient client = builder.build()) {
            for (Planned planned : plan) {
                install(client, planned);
            }
        } catch (InstallException e) {
            err.println("leia: " + e.getMessage());
            return 1;
        }
        out.println("Installed the runtimes into " + runtimesDir);
        return 0;
    }

    /**
     * Reads the folder of {@code known} from {@code leia.jar}, and adds the Lucene jars that aren't
     * there or don't match to {@code downloads}. Reads and writes nothing else.
     */
    private Planned plan(RuntimeFolders.Known known, List<Download> downloads) throws InstallException {
        String folderName = known.folder();
        byte[] adapter = resource(folderName, known.adapterJar());
        List<RuntimeFolders.ListedJar> listed = list(folderName, known.adapterJar(), adapter);
        Path folder = runtimesDir.resolve(folderName);
        Set<String> missing = new HashSet<>();
        for (RuntimeFolders.ListedJar jar : listed) {
            if (jar.mavenPath().isEmpty()) {
                continue;
            }
            Path target = folder.resolve(jar.name());
            if (!Files.isRegularFile(target) || !sha256(target).equals(jar.sha256())) {
                missing.add(jar.name());
                downloads.add(new Download(
                        folderName, jar, base.resolve(jar.mavenPath().get())));
            }
        }
        return new Planned(known, adapter, listed, missing);
    }

    /** What will be downloaded, with where from and what each jar is checked against. */
    private void printDownloads(List<Download> downloads, int kept) {
        if (base.equals(CENTRAL)) {
            out.println("leia downloads these official Lucene jars from Maven Central (" + CENTRAL + "), unchanged,");
        } else {
            out.println("leia downloads these official Lucene jars of Maven Central, unchanged, from " + base
                    + " (-Dleia.download.url),");
        }
        out.println("and checks each against the SHA-256 and size that leia's build recorded:");
        out.println();
        for (Download download : downloads) {
            out.println("  " + download.folder() + "/" + download.jar().name() + ", "
                    + TextFormat.size(download.jar().size()));
            out.println("    " + download.uri());
            out.println("    SHA-256 " + download.jar().sha256());
        }
        out.println();
        out.println("leia's own jars, the adapters and leia-core, come from leia.jar and aren't downloaded.");
        if (kept > 0) {
            out.println(jars(kept) + " already there and matching " + (kept == 1 ? "is" : "are") + " kept.");
        }
    }

    private static String jars(int count) {
        return count + (count == 1 ? " jar" : " jars");
    }

    private void install(HttpClient client, Planned planned) throws InstallException {
        RuntimeFolders.Known known = planned.known();
        String folderName = known.folder();
        Path folder = runtimesDir.resolve(folderName);
        try {
            Files.createDirectories(folder);
        } catch (IOException e) {
            throw new InstallException("cannot create " + folder + ", so runtimes/ isn't writable: " + e);
        }
        Set<String> names = new HashSet<>();
        names.add(known.adapterJar());
        writeOwn(folder, known.adapterJar(), planned.adapter());
        for (RuntimeFolders.ListedJar jar : planned.listed()) {
            names.add(jar.name());
            if (jar.mavenPath().isEmpty()) {
                writeOwn(folder, jar.name(), resource(folderName, jar.name()));
            } else if (planned.missing().contains(jar.name())) {
                download(client, folder, jar, base.resolve(jar.mavenPath().get()));
            } else {
                out.println(folderName + "/" + jar.name() + ": kept");
            }
        }
        removeStale(folder, names);
    }

    /** One of leia's jars, embedded in {@code leia.jar}. */
    private byte[] resource(String folder, String jar) throws InstallException {
        String name = RESOURCES + folder + "/" + jar;
        try (InputStream in = resources.getResourceAsStream(name)) {
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
                    return RuntimeFolders.list(jar);
                }
            }
        } catch (IOException e) {
            throw new InstallException("cannot read the list of " + folder + "/" + name + ": " + e.getMessage());
        }
        throw new InstallException(folder + "/" + name + " in leia.jar has no " + RuntimeFolders.LIST);
    }

    /** Writes one of leia's jars, unless it is there already. */
    private void writeOwn(Path folder, String name, byte[] content) throws InstallException {
        Path target = folder.resolve(name);
        String label = folder.getFileName() + "/" + name;
        if (Files.isRegularFile(target) && sha256(target).equals(sha256(content))) {
            out.println(label + ": kept");
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
        out.println(label + ": written, " + TextFormat.size(content.length));
    }

    /** Downloads a Lucene jar from {@code uri}, and moves it into place once it matches. */
    private void download(HttpClient client, Path folder, RuntimeFolders.ListedJar jar, URI uri)
            throws InstallException {
        Path target = folder.resolve(jar.name());
        String label = folder.getFileName() + "/" + jar.name();
        Path part = null;
        try {
            part = Files.createTempFile(folder, jar.name(), PART);
            HttpResponse<InputStream> response = client.send(
                    HttpRequest.newBuilder(uri).timeout(DOWNLOAD_TIMEOUT).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            MessageDigest digest = newDigest();
            long size;
            try (InputStream body = new DigestInputStream(response.body(), digest);
                    OutputStream file = Files.newOutputStream(part)) {
                if (response.statusCode() != 200) {
                    throw new InstallException(
                            "cannot download " + jar.name() + " from " + uri + ": HTTP " + response.statusCode());
                }
                size = body.transferTo(file);
            }
            String sha256 = HexFormat.of().formatHex(digest.digest());
            if (size != jar.size() || !sha256.equals(jar.sha256())) {
                throw new InstallException("the download of " + jar.name() + " from " + uri
                        + " doesn't match: " + size + " bytes with SHA-256 " + sha256 + ", expected " + jar.size()
                        + " bytes with SHA-256 " + jar.sha256());
            }
            move(part, target);
            out.println(label + ": downloaded, " + TextFormat.size(size));
        } catch (InstallException e) {
            delete(part);
            throw e;
        } catch (IOException e) {
            delete(part);
            throw new InstallException("cannot download " + jar.name() + " from " + uri + " to " + target + ": " + e);
        } catch (InterruptedException e) {
            delete(part);
            Thread.currentThread().interrupt();
            throw new InstallException("interrupted while downloading " + jar.name() + " from " + uri);
        }
    }

    /** Removes the jars and unfinished downloads in {@code folder} that aren't {@code names}. */
    private void removeStale(Path folder, Set<String> names) throws InstallException {
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
                out.println(folder.getFileName() + "/" + file.getFileName() + ": removed");
            }
        } catch (IOException e) {
            throw new InstallException("cannot remove the old jars of " + folder + ": " + e);
        }
    }

    private static void move(Path part, Path target) throws IOException {
        try {
            Files.move(part, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void delete(Path part) {
        if (part == null) {
            return;
        }
        try {
            Files.deleteIfExists(part);
        } catch (IOException e) {
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
