package io.github.mfuerstenau.leia.runtime.support;

/**
 * Runs a runtime's service lookups with the thread's context class loader set to the runtime's own
 * (ADR 0073). Lucene 5.5 finds its codecs, formats and analysis factories through the context class
 * loader, which in leia is the jar chain's and sees Lucene 10's services files: its lists have to
 * be built once, early, through this.
 */
public final class Spi {

    private Spi() {}

    /**
     * Runs {@code init}, such as the first lookups that build Lucene's lists, with the context class
     * loader set to {@code anchor}'s loader, and sets it back afterwards.
     */
    public static void initialize(Class<?> anchor, Runnable init) {
        Thread thread = Thread.currentThread();
        ClassLoader before = thread.getContextClassLoader();
        thread.setContextClassLoader(anchor.getClassLoader());
        try {
            init.run();
        } finally {
            thread.setContextClassLoader(before);
        }
    }
}
