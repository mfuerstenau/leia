package io.github.mfuerstenau.leia.app;

import java.lang.reflect.InvocationTargetException;

/**
 * Entry point of {@code leia.jar}, compiled for Java 8 (ADR 0034 §8): checks the Java version, then
 * starts {@link Main}. An older Java gets a message that says what is needed, instead of an {@code
 * UnsupportedClassVersionError} from loading the rest.
 */
public final class Launcher {

    static final int REQUIRED_JAVA = 25;

    /** When the launcher started, in epoch millis: the spike measures its startup from here. */
    public static final String STARTED_PROPERTY = "leia.launcher.started";

    private static final String MAIN = "io.github.mfuerstenau.leia.app.Main";

    private Launcher() {}

    public static void main(String[] args) throws Throwable {
        System.setProperty(STARTED_PROPERTY, Long.toString(System.currentTimeMillis()));
        int feature = feature(System.getProperty("java.specification.version"));
        if (feature < REQUIRED_JAVA) {
            System.err.println("leia needs Java " + REQUIRED_JAVA + " or newer; this is Java "
                    + System.getProperty("java.version") + " (" + System.getProperty("java.home") + ").");
            System.err.println("Choose another Java with -j/--java or JAVA_HOME.");
            System.exit(1);
            return;
        }
        try {
            Class.forName(MAIN).getMethod("main", String[].class).invoke(null, (Object) args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    /** The feature release of a {@code java.specification.version}: "1.8" is 8, "25" is 25. */
    static int feature(String specificationVersion) {
        String version =
                specificationVersion.startsWith("1.") ? specificationVersion.substring(2) : specificationVersion;
        try {
            return Integer.parseInt(version);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
