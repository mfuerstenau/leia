package io.github.mfuerstenau.leia.server;

import io.github.mfuerstenau.leia.core.AnalysisJarException;
import io.github.mfuerstenau.leia.core.AnalysisJars;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Entry point of {@code leia-server.jar}: loads the analysis jars of its command line (ADR 0016),
 * then serves JSON-RPC on stdin/stdout until stdin is closed, and closes the open index. Logs go to
 * stderr.
 */
public final class LeiaServer {

    private static final Logger LOG = Logger.getLogger(LeiaServer.class.getName());

    private LeiaServer() {}

    public static void main(String[] args) {
        // Only the protocol writer may use the real stdout. Anything else that prints to System.out
        // (a library, a stray debug print) ends up on stderr instead of corrupting the protocol.
        Writer out = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(FileDescriptor.out), StandardCharsets.UTF_8));
        System.setOut(System.err);
        ServerLogging.configure();

        ServerArgs serverArgs;
        try {
            serverArgs = ServerArgs.parse(args);
        } catch (IllegalArgumentException e) {
            LOG.severe(e.getMessage() + "; " + ServerArgs.USAGE);
            System.exit(2);
            return;
        }
        try {
            List<Path> jars = AnalysisJars.load(serverArgs.analysisJars());
            if (!jars.isEmpty()) {
                LOG.info(() -> "Loaded the analysis jars " + jars);
            }
        } catch (AnalysisJarException e) {
            // The TUI shows the end of stderr, so the reason is the last line; the trace only at FINE.
            LOG.log(Level.FINE, "Cannot load the analysis jars", e);
            LOG.severe("Cannot load the analysis jars: " + e.getMessage());
            System.exit(1);
            return;
        }
        // For libraries in the analysis jars that load through the context class loader; threads
        // started later, such as the job threads, inherit it.
        Thread.currentThread().setContextClassLoader(AnalysisJars.classLoader());

        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        ServerComponent component = DaggerServerComponent.create();
        boolean clientLost = false;
        try {
            component.server().serve(in, out);
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Connection to the client lost", e);
            clientLost = true;
        } finally {
            // A client that quits without closeIndex leaves the index open.
            component.currentIndex().close();
        }
        if (clientLost) {
            System.exit(1);
        }
    }
}
