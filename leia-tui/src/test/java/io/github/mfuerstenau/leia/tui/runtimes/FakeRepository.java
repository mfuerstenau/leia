package io.github.mfuerstenau.leia.tui.runtimes;

import com.sun.net.httpserver.HttpServer;
import io.github.mfuerstenau.leia.server.runtime.RuntimeFolders;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

/**
 * A Maven repository on loopback that serves the Lucene jars of the runtimes' folders that the
 * build puts together (ADR 0073). It can hold back every download after its first {@value #CHUNK}
 * bytes until it is {@link #release released}, for a cancel while a download runs.
 */
public final class FakeRepository implements AutoCloseable {

    static final Path TEST_RUNTIMES = Path.of(System.getProperty("leia.test.runtimes"));

    /** The bytes sent before a held-back download waits. */
    static final int CHUNK = 64 * 1024;

    /** What the repository serves by Maven path; missing paths are 404. */
    final Map<String, byte[]> served = new ConcurrentHashMap<>();
    /** How many requests it got. */
    public final AtomicInteger requests = new AtomicInteger();

    private final HttpServer server;
    private volatile CountDownLatch gate = new CountDownLatch(0);

    public FakeRepository() throws IOException {
        for (RuntimeFolders.Known known : RuntimeFolders.KNOWN) {
            for (RuntimeFolders.ListedJar jar : listed(known)) {
                if (jar.mavenPath().isPresent()) {
                    served.put(
                            jar.mavenPath().get(),
                            Files.readAllBytes(built(known).resolve(jar.name())));
                }
            }
        }
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/maven2/", exchange -> {
            requests.incrementAndGet();
            String path = exchange.getRequestURI().getPath().substring("/maven2/".length());
            byte[] body = served.get(path);
            try (exchange) {
                if (body == null) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                exchange.sendResponseHeaders(200, body.length);
                OutputStream response = exchange.getResponseBody();
                int first = Math.min(CHUNK, body.length);
                response.write(body, 0, first);
                response.flush();
                if (!gate.await(30, TimeUnit.SECONDS)) {
                    return;
                }
                response.write(body, first, body.length - first);
                response.close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                // The client went away, as on a cancel.
            }
        });
        server.start();
    }

    /** Holds back every download after its first bytes, until {@link #release}. */
    void holdBack() {
        gate = new CountDownLatch(1);
    }

    /** Lets the downloads held back go on. */
    void release() {
        gate.countDown();
    }

    public URI base() {
        return URI.create("http://" + server.getAddress().getHostString() + ":"
                + server.getAddress().getPort() + "/maven2/");
    }

    @Override
    public void close() {
        release();
        server.stop(0);
    }

    static RuntimeFolders.Known known(String folder) {
        return RuntimeFolders.KNOWN.stream()
                .filter(known -> known.folder().equals(folder))
                .findFirst()
                .orElseThrow();
    }

    /** The list in the embedded adapter of {@code known}. */
    static List<RuntimeFolders.ListedJar> listed(RuntimeFolders.Known known) throws IOException {
        String name = RuntimeInstall.RESOURCES + known.folder() + "/" + known.adapterJar();
        try (InputStream adapter = FakeRepository.class.getClassLoader().getResourceAsStream(name);
                JarInputStream jar = new JarInputStream(adapter)) {
            for (JarEntry entry = jar.getNextJarEntry(); entry != null; entry = jar.getNextJarEntry()) {
                if (entry.getName().equals(RuntimeFolders.LIST)) {
                    return RuntimeFolders.listedJars(jar);
                }
            }
        }
        throw new IllegalStateException("no list in " + name);
    }

    /** The Lucene jars of {@code known}, as its list names them. */
    static List<RuntimeFolders.ListedJar> lucene(RuntimeFolders.Known known) throws IOException {
        return listed(known).stream().filter(jar -> jar.mavenPath().isPresent()).toList();
    }

    /**
     * The folder of {@code known} as the build puts it together: the tests' runtimes have the old
     * ones only, Lucene 10's is a folder of its own.
     */
    static Path built(RuntimeFolders.Known known) {
        return known.equals(RuntimeFolders.LATEST)
                ? Path.of(System.getProperty("leia.test.lucene10"))
                : TEST_RUNTIMES.resolve(known.folder());
    }
}
