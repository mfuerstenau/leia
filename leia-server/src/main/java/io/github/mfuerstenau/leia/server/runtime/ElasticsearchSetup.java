package io.github.mfuerstenau.leia.server.runtime;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * What Elasticsearch's bootstrap sets up before its codecs and formats work, done for a server's class
 * loader (ADR 0090): its logging, without which every class of it that logs fails to initialize, such
 * as its vector formats, whose failure then breaks Lucene's list of them; and where its native
 * libraries are, {@code lib/platform/<os>-<arch>}, for zstd and its vector scorers, which it takes
 * from {@code es.nativelibs.path}, else from {@code es.path.home} (8.x) or the working directory
 * (9.0). Nothing for OpenSearch, which logs through Log4j's API and needs neither. Elasticsearch
 * 9.1 and later find their native providers through the thread's context class loader, which
 * {@code CurrentIndex} sets to the server's while its index is open.
 *
 * <p>Best effort: what fails is logged, and the formats that need it fail when they are read.
 */
final class ElasticsearchSetup {

    private static final Logger LOG = Logger.getLogger(ElasticsearchSetup.class.getName());

    /** The class whose {@code configureESLogging()} sets Elasticsearch's logger factory. */
    static final String LOG_CONFIGURATOR = "org.elasticsearch.common.logging.LogConfigurator";

    /**
     * The class that finds the native libraries, from {@code es.nativelibs.path}, when it is
     * initialized: in {@code org.elasticsearch.nativeaccess.lib} up to 9.4, in {@code
     * org.elasticsearch.foreign} from 9.5.
     */
    static final List<String> LOADER_HELPERS =
            List.of("org.elasticsearch.nativeaccess.lib.LoaderHelper", "org.elasticsearch.foreign.LoaderHelper");

    static final String LIBS_PROPERTY = "es.nativelibs.path";

    private ElasticsearchSetup() {}

    /**
     * Sets up the Elasticsearch whose classes {@code loader} sees, from the jars of {@code lib}; does
     * nothing if they aren't Elasticsearch's.
     */
    // The property is global, so it is set only while the class reads it, under the class's lock
    static synchronized void setUp(ClassLoader loader, Path lib) {
        Path parent = lib.getParent();
        Path installation = parent != null ? parent : lib;
        Class<?> configurator;
        try {
            configurator = Class.forName(LOG_CONFIGURATOR, false, loader);
        } catch (ClassNotFoundException _) {
            return;
        }
        try {
            configurator.getMethod("configureESLogging").invoke(null);
        } catch (NoSuchMethodException _) {
            // Elasticsearch 8.0 has none: it logs through Log4j's API, which needs nothing.
            LOG.fine(() -> "Elasticsearch in " + installation + " has no logger factory to set");
        } catch (InvocationTargetException e) {
            LOG.log(Level.WARNING, e.getCause(), () -> "Cannot set up the logging of Elasticsearch in " + installation);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            LOG.log(Level.WARNING, e, () -> "Cannot set up the logging of Elasticsearch in " + installation);
        }
        // Read once, as the class initializes; set by the user, it is left alone.
        String previous = System.getProperty(LIBS_PROPERTY);
        if (previous == null) {
            System.setProperty(LIBS_PROPERTY, platformDir(lib).toString());
        }
        try {
            for (String helper : LOADER_HELPERS) {
                initialize(loader, helper, installation);
            }
        } finally {
            if (previous == null) {
                System.clearProperty(LIBS_PROPERTY);
            }
        }
    }

    private static void initialize(ClassLoader loader, String helper, Path installation) {
        try {
            Class.forName(helper, true, loader);
        } catch (ClassNotFoundException _) {
            // Not of this version, or an Elasticsearch without native libraries.
        } catch (LinkageError e) {
            LOG.log(Level.WARNING, e, () -> "Cannot find the native libraries of Elasticsearch in " + installation);
        }
    }

    /** {@code lib/platform/<os>-<arch>}, named as Elasticsearch names it, such as {@code linux-x64}. */
    static Path platformDir(Path lib) {
        String os = System.getProperty("os.name", "");
        String osName;
        if (os.startsWith("Windows")) {
            osName = "windows";
        } else if (os.startsWith("Mac OS")) {
            osName = "darwin";
        } else {
            osName = "linux";
        }
        String arch = System.getProperty("os.arch", "");
        String archName = arch.equals("amd64") || arch.equals("x86_64") ? "x64" : arch;
        return lib.resolve("platform").resolve(osName + "-" + archName);
    }
}
