package org.elasticsearch.common.logging;

/**
 * A stand-in for Elasticsearch's, whose {@code configureESLogging()} its bootstrap calls before any
 * class of it logs (ADR 0090). The fake server's postings format fails without it, as Elasticsearch's
 * vector formats do.
 */
public final class LogConfigurator {

    private static volatile boolean configured;

    private LogConfigurator() {}

    public static void configureESLogging() {
        configured = true;
    }

    public static boolean configured() {
        return configured;
    }
}
