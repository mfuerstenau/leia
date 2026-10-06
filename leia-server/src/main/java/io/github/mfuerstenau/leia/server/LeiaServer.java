package io.github.mfuerstenau.leia.server;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Entry point of {@code leia-server.jar}: serves JSON-RPC on stdin/stdout until stdin is closed,
 * then closes the open index. Logs go to stderr.
 */
public final class LeiaServer {

    private LeiaServer() {}

    public static void main(String[] args) {
        // Only the protocol writer may use the real stdout. Anything else that prints to System.out
        // (a library, a stray debug print) ends up on stderr instead of corrupting the protocol.
        Writer out = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(FileDescriptor.out), StandardCharsets.UTF_8));
        System.setOut(System.err);
        ServerLogging.configure();

        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        ServerComponent component = DaggerServerComponent.create();
        boolean clientLost = false;
        try {
            component.server().serve(in, out);
        } catch (IOException e) {
            Logger.getLogger(LeiaServer.class.getName()).log(Level.SEVERE, "Connection to the client lost", e);
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
