package io.github.mfuerstenau.leia.server;

import io.github.mfuerstenau.leia.protocol.ProtocolJson;
import io.github.mfuerstenau.leia.protocol.Response;
import jakarta.inject.Inject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.util.logging.Logger;

/**
 * The JSON-RPC loop: reads one request per line, writes one response per line, and returns when the
 * input ends. Requests are handled one at a time, in order.
 */
final class RpcServer {

    private static final Logger LOG = Logger.getLogger(RpcServer.class.getName());

    private final Dispatcher dispatcher;
    private final BuildInfo buildInfo;

    @Inject
    RpcServer(Dispatcher dispatcher, BuildInfo buildInfo) {
        this.dispatcher = dispatcher;
        this.buildInfo = buildInfo;
    }

    /**
     * Serves requests from {@code in} until it ends.
     *
     * @throws IOException if reading a request or writing a response fails (the client is gone)
     */
    void serve(BufferedReader in, Writer out) throws IOException {
        LOG.info(() -> "leia-server " + buildInfo.version() + " (" + buildInfo.commit() + ") ready on Java "
                + Runtime.version());
        String line;
        while ((line = in.readLine()) != null) {
            if (!line.isBlank()) {
                respond(line, out);
            }
        }
        LOG.info("stdin closed, exiting");
    }

    private void respond(String request, Writer out) throws IOException {
        Response<?> response = dispatcher.dispatch(request);
        // Jackson escapes control characters in strings, so the response is a single line.
        String json = ProtocolJson.mapper().writeValueAsString(response);
        LOG.fine(() -> "<- " + request.strip() + System.lineSeparator() + "-> " + json);
        out.write(json);
        out.write('\n');
        out.flush();
    }
}
