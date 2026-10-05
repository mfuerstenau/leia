package io.github.mfuerstenau.leia.server;

/** Entry point of {@code leia-server.jar}. The JSON-RPC stdio loop is not implemented yet. */
public final class LeiaServer {

    private LeiaServer() {}

    public static void main(String[] args) {
        DaggerServerComponent.create();
    }
}
